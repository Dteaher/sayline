"""Research re-clustering of model-bounded turns; no source labels enter clustering."""
import importlib.util,json,time
from pathlib import Path
import numpy as np
import sherpa_onnx as s
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('compare',ROOT/'scripts/compare-asr.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
OUT=ROOT/'tests/evidence/quality-improvement'
base=json.loads((OUT/'dialogs-diarization.json').read_text(encoding='utf-8'))['results']
rows=json.loads((ROOT/'tests/audio/quality-improvement/provenance.json').read_text(encoding='utf-8'))['rows']
audio={i:c.read(ROOT/f'tests/audio/quality-improvement/dialogs-{i}-16k.wav') for i in range(1,9)}
def exchange(indices,gap=.5,overlap=0):
    cursor=0;pieces=[];windows=[]
    for i in indices:
        if pieces:cursor+=int((gap-overlap)*16000)
        pieces.append((cursor,audio[i]));windows.append((cursor/16000,(cursor+len(audio[i]))/16000,rows[i]['speaker_id']));cursor+=len(audio[i])
    x=np.zeros(cursor,dtype=np.float32)
    for start,v in pieces:x[start:start+len(v)]+=v
    return x,windows
dev=exchange([3,1,4]);held,win=exchange([5,6,3,7,4]);over=exchange([5,6,3],gap=0,overlap=.7)
rng=np.random.default_rng(20261107);noise=rng.normal(size=len(held)).astype(np.float32);noise*=np.sqrt(np.mean(held*held))/np.sqrt(np.mean(noise*noise))*.316228
cases={'dev_male_female':dev,'heldout_two_female':(held,win),'heldout_noise10':(held+noise,win),'overlap_700ms':over}
report=[]
for model,path in [('resnet',ROOT/'app/src/main/assets/models/speaker.onnx'),('eres2net',ROOT/'.work/quality-research/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx')]:
    ext=s.SpeakerEmbeddingExtractor(s.SpeakerEmbeddingExtractorConfig(model=c.native(path),num_threads=2))
    for name,(x,windows) in cases.items():
        turns=next(r['turns'] for r in base if r['case']==name and r['config']==[.5,.3,.4]);vectors=[]
        started=time.perf_counter()
        for a,b,k in turns:
            stream=ext.create_stream();stream.accept_waveform(16000,x[int(a*16000):int(b*16000)]);stream.input_finished()
            if ext.is_ready(stream):
                v=np.array(ext.compute(stream));vectors.append(v/max(1e-9,np.linalg.norm(v)))
            else:vectors.append(None)
        for threshold in [.3,.4,.5,.6]:
            clusters=[[i] for i,v in enumerate(vectors) if v is not None]
            # Average linkage; merge distances are derived only from neural embeddings.
            while len(clusters)>1:
                pairs=[(float(np.mean([1-np.dot(vectors[a],vectors[b]) for a in clusters[i] for b in clusters[j]])),i,j) for i in range(len(clusters)) for j in range(i)]
                distance,i,j=min(pairs)
                if distance>threshold:break
                clusters[j]+=clusters[i];del clusters[i]
            labels={i:k for k,group in enumerate(clusters) for i in group}
            # Missing embedding does not invent a new participant: retain the original cluster's dominant refined ID.
            for i,v in enumerate(vectors):
                if v is None:
                    totals={}
                    for j,(a,b,k) in enumerate(turns):
                        if j in labels and k==turns[i][2]:totals[labels[j]]=totals.get(labels[j],0)+(b-a)
                    labels[i]=max(totals,key=totals.get) if totals else -1
            refined=[(a,b,labels[i]) for i,(a,b,k) in enumerate(turns)]
            ids=[]
            for a,b,actor in windows:
                score={}
                for left,right,k in refined:score[k]=score.get(k,0)+max(0,min(b,right)-max(a,left))
                ids.append(max(score,key=score.get) if score and max(score.values())>0 else None)
            errors=sum((ids[i]==ids[j])!=(windows[i][2]==windows[j][2]) or ids[i] is None or ids[j] is None for i in range(len(ids)) for j in range(i))
            item=dict(model=model,case=name,threshold=threshold,count=len(clusters),dominant=ids,pair_identity_errors=errors,extra_seconds=time.perf_counter()-started,turns=refined)
            report.append(item);print({k:v for k,v in item.items() if k!='turns'},flush=True)
            (OUT/'refined-turns-research.json').write_text(json.dumps({'limits':'Research on known-actor constructed exchanges; no source labels enter clustering, but this corpus is small and not independent after development. Not DER, not Android performance.','results':report},indent=2),encoding='utf-8')

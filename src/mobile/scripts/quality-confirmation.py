"""Fixed new utterances selected before this confirmation, same three known actors."""
import ast, importlib.util, json, time, wave
from pathlib import Path
import numpy as np
import sherpa_onnx as s
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('compare',ROOT/'scripts/compare-asr.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
M=ROOT/'app/src/main/assets/models';OUT=ROOT/'tests/evidence/quality-improvement'
# Reuse the reviewed decoder function without importing the benchmark's top-level sweep.
node=next(n for n in ast.parse((ROOT/'scripts/quality-dialogs.py').read_text(encoding='utf-8')).body if isinstance(n,ast.FunctionDef) and n.name=='read')
exec(compile(ast.Module(body=[node],type_ignores=[]),'quality-dialogs.py','exec'))
selection=ROOT/'.work/quality-research/confirmation-selection.json'
if selection.exists():rows=json.loads(selection.read_text(encoding='utf-8'))
else:
    import urllib.request,hashlib
    rows=json.loads((OUT/'confirmation.json').read_text(encoding='utf-8'))['rows']
    for row in rows:
        target=ROOT/row['file'];target.parent.mkdir(parents=True,exist_ok=True)
        if not target.exists():
            data=urllib.request.urlopen(row['url'],timeout=30).read(10000000)
            if hashlib.sha256(data).hexdigest()!=row['sha256']:raise ValueError('Source checksum mismatch')
            target.write_bytes(data)
assert all('file' in r for r in rows), 'Do not silently replace failed fixed selections'
audio=[read(r) for r in rows];pieces=[];windows=[];cursor=0
for i in [0,2,4,1,3,5]:
    if pieces:pieces.append(np.zeros(8000,dtype=np.float32));cursor+=8000
    windows.append((cursor/16000,(cursor+len(audio[i]))/16000,rows[i]['speaker_id']));pieces.append(audio[i]);cursor+=len(audio[i])
x=np.concatenate(pieces).astype(np.float32)
rng=np.random.default_rng(20261108);noise=rng.normal(size=len(x)).astype(np.float32);noise*=np.sqrt(np.mean(x*x))/np.sqrt(np.mean(noise*noise))*.316228
echo=x.copy();echo[2400:]+=.35*x[:-2400]
report=[]
for name,y in [('clean',x),('noise10dB',x+noise),('quiet30dB',x*.0316228),('echo150ms',echo)]:
    sd=s.OfflineSpeakerDiarization(s.OfflineSpeakerDiarizationConfig(segmentation=s.OfflineSpeakerSegmentationModelConfig(pyannote=s.OfflineSpeakerSegmentationPyannoteModelConfig(model=c.native(M/'segmentation.onnx')),num_threads=2),embedding=s.SpeakerEmbeddingExtractorConfig(model=c.native(M/'speaker.onnx'),num_threads=2),clustering=s.FastClusteringConfig(num_clusters=-1,threshold=.5),min_duration_on=.3,min_duration_off=.4))
    started=time.perf_counter();turns=[(float(t.start),float(t.end),int(t.speaker)) for t in sd.process(y).sort_by_start_time()];del sd
    ext=s.SpeakerEmbeddingExtractor(s.SpeakerEmbeddingExtractorConfig(model=c.native(M/'speaker-refinement.onnx'),num_threads=2));vectors=[]
    for a,b,k in turns:
        begin=int(a*16000);stop=int(b*16000);begin=begin+(stop-begin-160000)//2 if stop-begin>160000 else begin
        st=ext.create_stream();st.accept_waveform(16000,y[begin:min(stop,begin+160000)]);st.input_finished()
        if ext.is_ready(st):
            v=np.array(ext.compute(st));vectors.append(v/max(1e-9,np.linalg.norm(v)))
        else:vectors.append(None)
    del ext
    anchors=[i for i,v in enumerate(vectors) if v is not None and turns[i][1]-turns[i][0]>=1.5]
    clusters=[[i] for i in anchors]
    while len(clusters)>1:
        distance,i,j=min((float(np.mean([1-np.dot(vectors[a],vectors[b]) for a in clusters[i] for b in clusters[j]])),i,j) for i in range(len(clusters)) for j in range(i))
        if distance>.5:break
        clusters[j]+=clusters[i];del clusters[i]
    labels={i:k for k,group in enumerate(clusters) for i in group}
    fallback={}
    for i,v in enumerate(vectors):
        if i not in labels:
            eligible={labels[j] for j in anchors if turns[j][2]==turns[i][2]}
            if v is not None and eligible:
                labels[i]=min(eligible,key=lambda k:float(np.mean([1-np.dot(v,vectors[j]) for j in anchors if labels[j]==k])))
            else:
                if turns[i][2] not in fallback:
                    totals={}
                    for j in anchors:
                        if turns[j][2]==turns[i][2]:totals[labels[j]]=totals.get(labels[j],0)+(turns[j][1]-turns[j][0])
                    fallback[turns[i][2]]=max(totals,key=totals.get) if totals else max(labels.values(),default=-1)+1
                labels[i]=fallback[turns[i][2]]
    for variant,chosen in [('native',turns),('refined',[(a,b,labels[i]) for i,(a,b,k) in enumerate(turns)])]:
        ids=[]
        for a,b,actor in windows:
            scores={}
            for left,right,k in chosen:scores[k]=scores.get(k,0)+max(0,min(b,right)-max(a,left))
            ids.append(max(scores,key=scores.get) if scores and max(scores.values())>0 else None)
        errors=sum((ids[i]==ids[j])!=(windows[i][2]==windows[j][2]) or ids[i] is None or ids[j] is None for i in range(len(ids)) for j in range(i))
        item=dict(condition=name,variant=variant,count=len({t[2] for t in chosen}),ids=ids,pair_identity_errors=errors,total_seconds=time.perf_counter()-started,turns=chosen)
        report.append(item);print({k:v for k,v in item.items() if k!='turns'},flush=True)
        (OUT/'confirmation.json').write_text(json.dumps({'selection':'Next two test.csv utterances per D,M,S (positions3:5), fixed before confirmation. Same actors, different utterances. No threshold retuning.','rows':rows,'limits':'Composed recorded acting, not meetings; no DER. Host CPU only.','results':report},ensure_ascii=False,indent=2),encoding='utf-8')

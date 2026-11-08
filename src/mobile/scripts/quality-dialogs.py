"""Actor-identity checks on licensed Dialogs utterances; constructed exchanges, not meetings."""
import argparse, csv, hashlib, importlib.util, json, shutil, time, wave
from pathlib import Path
import numpy as np
import sherpa_onnx as s
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('compare', ROOT/'scripts/compare-asr.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
OUT=ROOT/'tests/evidence/quality-improvement';OUT.mkdir(exist_ok=True,parents=True)
DATA=ROOT/'tests/audio/quality-improvement';DATA.mkdir(exist_ok=True,parents=True)
M=ROOT/'app/src/main/assets/models'
p=argparse.ArgumentParser();p.add_argument('--embedding',choices=['resnet','cam','resnet_lm','eres2net'],default='resnet');p.add_argument('--fine',action='store_true');p.add_argument('--shift',type=float,default=.1);args=p.parse_args()
embedding={'resnet':M/'speaker.onnx','cam':ROOT/'.work/stack-comparison-research/wespeaker_en_voxceleb_CAM++_LM.onnx','resnet_lm':ROOT/'.work/quality-research/wespeaker_en_voxceleb_resnet34_LM.onnx','eres2net':ROOT/'.work/quality-research/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx'}[args.embedding]
rows=json.loads((ROOT/'.work/quality-research/dialogs-selection.json').read_text(encoding='utf-8'))
shutil.copy2(ROOT/'.work/quality-research/dialogs-license',DATA/'LICENSE-Dialogs.md')

def read(row):
    with wave.open(str(ROOT/row['file'])) as w:
        rate=w.getframerate();ch=w.getnchannels();assert w.getsampwidth()==2
        x=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32).reshape(-1,ch).mean(axis=1)/32768
    # Same 48-tap windowed sinc / 512-phase lowpass design as the Android decoder.
    pos=np.arange(int(len(x)*16000/rate))*rate/16000;center=pos.astype(np.int64)
    phases=np.minimum(511,((pos-center)*512).astype(np.int64));cutoff=min(1,16000/rate)*.94
    value=np.zeros(len(pos));weight=np.zeros(len(pos))
    for tap in range(48):
        offset=tap-23;phase_x=offset-phases/512
        kernel=cutoff*np.sinc(phase_x*cutoff)*np.maximum(0,.5+.5*np.cos(np.pi*phase_x/24))
        idx=center+offset;valid=(idx>=0)&(idx<len(x));v=np.clip(idx,0,len(x)-1)
        value+=x[v]*kernel*valid;weight+=kernel*valid
    return (value/np.maximum(weight,1e-9)).astype(np.float32)

audio={i:read(r) for i,r in enumerate(rows) if 'file' in r}
for i,x in audio.items():
    target=DATA/f'dialogs-{i}-16k.wav'
    with wave.open(str(target),'wb') as w:w.setparams((1,2,16000,len(x),'NONE','not compressed'));w.writeframes((np.clip(x,-1,1)*32767).astype('<i2').tobytes())
(DATA/'provenance.json').write_text(json.dumps({'source':'https://huggingface.co/datasets/langswap/dialogs-ru-emotional-conversations','license':'Dialogs OpenRAIL, LICENSE-Dialogs.md','selection':'first three test.csv rows per actor D,M,S before inference; first D download failed, retained failure, no replacement','limits':'Actors performing scripted utterances, composed exchanges with new returning utterances. NOT spontaneous meetings. No human speech/overlap timestamp annotations. No DER.','rows':rows},ensure_ascii=False,indent=2),encoding='utf-8')

def exchange(indices,gap=.5,overlap=0):
    cursor=0;sources=[];starts=[]
    for i in indices:
        if sources:cursor+=int(gap*16000)-int(overlap*16000)
        starts.append(cursor);sources.append((i,audio[i]));cursor+=len(audio[i])
    x=np.zeros(cursor,dtype=np.float32);windows=[]
    for start,(i,v) in zip(starts,sources):x[start:start+len(v)]+=v;windows.append((start/16000,(start+len(v))/16000,rows[i]['speaker_id']))
    return x,windows

dev,devwin=exchange([3,1,4]); held,heldwin=exchange([5,6,3,7,4])
over,overwin=exchange([5,6,3],gap=0,overlap=.7)
rng=np.random.default_rng(20261107);noise=rng.normal(size=len(held)).astype(np.float32);noise*=np.sqrt(np.mean(held*held))/np.sqrt(np.mean(noise*noise))*.316228
cases={'dev_male_female':(dev,devwin),'heldout_two_female':(held,heldwin),'heldout_noise10':(held+noise,heldwin),'overlap_700ms':(over,overwin)}
report=[]
configs=[(.25,.1,.2),(.3,.1,.2),(.35,.1,.2),(.4,.1,.2)] if args.fine else ([(.5,.1,.2),(.7,.1,.2),(.85,.1,.2),(.95,.1,.2)] if args.embedding=='cam' else [(.5,.3,.4),(.5,.1,.2),(.45,.1,.2),(.55,.1,.2),(.6,.1,.2)])
for threshold,on,off in configs:
    sd=s.OfflineSpeakerDiarization(s.OfflineSpeakerDiarizationConfig(segmentation=s.OfflineSpeakerSegmentationModelConfig(pyannote=s.OfflineSpeakerSegmentationPyannoteModelConfig(model=c.native(M/'segmentation.onnx'),window_shift_ratio=args.shift),num_threads=2),embedding=s.SpeakerEmbeddingExtractorConfig(model=c.native(embedding),num_threads=2),clustering=s.FastClusteringConfig(num_clusters=-1,threshold=threshold),min_duration_on=on,min_duration_off=off))
    for name,(x,windows) in cases.items():
        started=time.perf_counter();turns=[(float(t.start),float(t.end),int(t.speaker)) for t in sd.process(x).sort_by_start_time()]
        ids=[]
        for a,b,actor in windows:
            scores={}
            for start,end,k in turns:scores[k]=scores.get(k,0)+max(0,min(b,end)-max(a,start))
            ids.append(max(scores,key=scores.get) if scores and max(scores.values())>0 else None)
        # Every pair of source utterances must have the same/different cluster as their known source actor.
        errors=sum((ids[i]==ids[j]) != (windows[i][2]==windows[j][2]) or ids[i] is None or ids[j] is None for i in range(len(ids)) for j in range(i))
        item=dict(config=[threshold,on,off],case=name,seconds=time.perf_counter()-started,count=len({t[2] for t in turns}),dominant=ids,actors=[w[2] for w in windows],pair_identity_errors=errors,turns=turns)
        report.append(item);print({k:v for k,v in item.items() if k!='turns'},flush=True)
        (OUT/f'dialogs-diarization-{args.embedding}{"-fine" if args.fine else ""}-shift{args.shift}.json').write_text(json.dumps({'limits':'Known source-actor pair identity checks on composed recorded acting, not full DER. No global threshold selected solely from these files.','embedding_sha256':hashlib.sha256(embedding.read_bytes()).hexdigest(),'shift':args.shift,'results':report},ensure_ascii=False,indent=2),encoding='utf-8')
    del sd

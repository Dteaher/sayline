"""Known Windows TTS voices, including independent short yes/no/wait replies."""
import importlib.util,json,time,wave
from pathlib import Path
import numpy as np
import sherpa_onnx as s
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('compare',ROOT/'scripts/compare-asr.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
M=ROOT/'app/src/main/assets/models'
paths=[ROOT/'tests/audio/a1.wav',ROOT/'tests/audio/b1.wav',ROOT/'.work/quality-research/short-yes.wav',ROOT/'.work/quality-research/short-no.wav',ROOT/'tests/audio/a2.wav',ROOT/'.work/quality-research/short-wait.wav']
pieces=[c.read(p) for p in paths];ids=[0,1,0,1,0,1];windows=[];start=0;joined=[]
for i,x in enumerate(pieces):
    if i:joined.append(np.zeros(8000,dtype=np.float32));start+=8000
    windows.append((start/16000,(start+len(x))/16000));joined.append(x);start+=len(x)
x=np.concatenate(joined).astype(np.float32);report=[]
rec=s.OfflineRecognizer.from_nemo_ctc(model=c.native(M/'gigaam-v3.int8.onnx'),tokens=c.native(M/'tokens.txt'),num_threads=2,sample_rate=16000,feature_dim=64)
for on,off in [(.3,.4),(.15,.3),(.05,.2)]:
    sd=s.OfflineSpeakerDiarization(s.OfflineSpeakerDiarizationConfig(segmentation=s.OfflineSpeakerSegmentationModelConfig(pyannote=s.OfflineSpeakerSegmentationPyannoteModelConfig(model=c.native(M/'segmentation.onnx')),num_threads=2),embedding=s.SpeakerEmbeddingExtractorConfig(model=c.native(M/'speaker.onnx'),num_threads=2),clustering=s.FastClusteringConfig(num_clusters=-1,threshold=.5),min_duration_on=on,min_duration_off=off))
    started=time.perf_counter();turns=[(float(t.start),float(t.end),int(t.speaker)) for t in sd.process(x).sort_by_start_time()]
    results=[]
    for i,(a,b) in enumerate(windows):
        score={}
        for left,right,k in turns:score[k]=score.get(k,0)+max(0,min(right,b)-max(left,a))
        cluster=max(score,key=score.get) if score and max(score.values())>.05 else None
        texts=[]
        for left,right,k in turns:
            if min(right,b)-max(left,a)>.05:
                st=rec.create_stream();st.accept_waveform(16000,x[max(0,int((left-.3)*16000)):min(len(x),int((right+.3)*16000))]);rec.decode_stream(st);texts.append(st.result.text)
        results.append(dict(file=paths[i].name,expected_voice=ids[i],cluster=cluster,text=' '.join(texts)))
    report.append(dict(config=[.5,on,off],seconds=time.perf_counter()-started,speaker_count=len({t[2] for t in turns}),results=results,turns=turns));print(report[-1],flush=True)
    (ROOT/'tests/evidence/quality-improvement/short-turns.json').write_text(json.dumps({'limits':'Synthetic fixed Windows TTS, independent new returning phrases. Not real conversation.','results':report},ensure_ascii=False,indent=2),encoding='utf-8')
    del sd

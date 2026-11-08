"""Fixed perturbations and diarization sweep. Host research, not Android accuracy claims.

Uses existing licensed FLEURS clips and clearly labelled Windows TTS. Speaker-count
checks on constructed mixtures are not DER or evidence of natural conversation quality.
"""
import ctypes, importlib.util, json, os, time
from pathlib import Path
import numpy as np
import sherpa_onnx as s

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/evidence/quality-improvement'
OUT.mkdir(parents=True, exist_ok=True)
spec = importlib.util.spec_from_file_location('compare', ROOT / 'scripts/compare-asr.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
M = ROOT / 'app/src/main/assets/models'

def norm(x):
    peak = float(np.max(np.abs(x))) if len(x) else 0
    return x * min(10, .85 / peak) if peak > 1e-6 else x

def recognizer():
    return s.OfflineRecognizer.from_nemo_ctc(model=c.native(M/'gigaam-v3.int8.onnx'), tokens=c.native(M/'tokens.txt'), num_threads=2, sample_rate=16000, feature_dim=64)

def text(rec, x):
    st=rec.create_stream(); st.accept_waveform(16000, x.astype(np.float32)); rec.decode_stream(st)
    return st.result.text.strip()

def asr():
    rec=recognizer(); report=[]; manifest=json.loads((ROOT/'tests/audio/stack-comparison/provenance.json').read_text(encoding='utf-8'))
    denoiser=s.OfflineSpeechDenoiser(s.OfflineSpeechDenoiserConfig(model=s.OfflineSpeechDenoiserModelConfig(gtcrn=s.OfflineSpeechDenoiserGtcrnModelConfig(model=c.native(ROOT/'.work/quality-research/gtcrn.onnx')),num_threads=2))) if (ROOT/'.work/quality-research/gtcrn.onnx').exists() else None
    # Existing manifest format is retained so references cannot be selected after inference.
    rows=manifest.get('clips', manifest.get('items', manifest.get('fixtures', []))) if isinstance(manifest,dict) else manifest
    if not rows:
        rows=json.loads((ROOT/'.work/stack-comparison-research/fleurs-fixtures-first20.json').read_text(encoding='utf-8'))
        rows=rows.get('fixtures', rows.get('clips', [])) if isinstance(rows,dict) else rows
    rng=np.random.default_rng(20261107)
    for row in rows[args.start:args.start+args.count]:
        fname=row.get('file',row.get('path',row.get('output','')))
        path=ROOT/fname
        if not path.exists(): path=ROOT/'tests/audio/stack-comparison'/Path(fname).name
        x=c.read(path); reference=row.get('text',row.get('transcription',row.get('raw_transcription','')))
        if not reference: raise ValueError(row)
        noise=rng.normal(size=len(x)).astype(np.float32)
        noise*=np.sqrt(np.mean(x*x))/max(1e-9,np.sqrt(np.mean(noise*noise))) / 10**(10/20)
        echo=x.copy(); echo[2400:]+=.35*x[:-2400]
        for name,a in [('clean',x),('quiet_-30dB',x*.0316228),('noise_10dB',x+noise),('echo_150ms',echo)]:
            for method in ['original','peak_gain'] + (['gtcrn'] if denoiser else []):
                v=norm(a) if method=='peak_gain' else (np.array(denoiser.run(a.astype(np.float32),16000).samples,dtype=np.float32) if method=='gtcrn' else a)
                started=time.perf_counter(); hypothesis=text(rec,v)
                report.append(dict(file=path.name,condition=name,method=method,reference=reference,hypothesis=hypothesis,wer=c.metric(reference,hypothesis),seconds=time.perf_counter()-started))
            print(path.name,name,flush=True)
            (OUT/f'asr-conditions-{args.start}.json').write_text(json.dumps({'environment':'Windows host CPU 2 threads','limits':'Fixed read-speech FLEURS clips; deterministic artificial distortions, not conversations','start':args.start,'count':args.count,'results':report},ensure_ascii=False,indent=2),encoding='utf-8')

def diarization():
    a,b,a2=[c.read(ROOT/f'tests/audio/{name}.wav') for name in ['a1','b1','a2']]
    x=np.concatenate([a,np.zeros(16000),b,np.zeros(16000),a2]).astype(np.float32)
    windows=[(0,len(a)/16000),((len(a)+16000)/16000,(len(a)+16000+len(b))/16000),((len(a)+len(b)+32000)/16000,len(x)/16000)]
    rng=np.random.default_rng(20261107)
    n=rng.normal(size=len(x)).astype(np.float32); n*=np.sqrt(np.mean(x*x))/np.sqrt(np.mean(n*n))*.316228
    echo=x.copy(); echo[2400:]+=.35*x[:-2400]
    cases={'dev_clean':x,'dev_quiet':x*.0316228,'heldout_noise10':x+n,'heldout_echo150':echo}
    report=[]
    for threshold,on,off in [(.5,.3,.4),(.5,.1,.2),(.4,.1,.2),(.6,.1,.2)]:
        cfg=s.OfflineSpeakerDiarizationConfig(segmentation=s.OfflineSpeakerSegmentationModelConfig(pyannote=s.OfflineSpeakerSegmentationPyannoteModelConfig(model=c.native(M/'segmentation.onnx')),num_threads=2),embedding=s.SpeakerEmbeddingExtractorConfig(model=c.native(M/'speaker.onnx'),num_threads=2),clustering=s.FastClusteringConfig(num_clusters=-1,threshold=threshold),min_duration_on=on,min_duration_off=off)
        sd=s.OfflineSpeakerDiarization(cfg)
        for name,v in cases.items():
            t=time.perf_counter(); turns=[(float(t.start),float(t.end),int(t.speaker)) for t in sd.process(v).sort_by_start_time()]
            ids=[]
            for left,right in windows:
                score={}
                for start,end,k in turns: score[k]=score.get(k,0)+max(0,min(right,end)-max(left,start))
                ids.append(max(score,key=score.get) if score and max(score.values())>0 else None)
            report.append(dict(config=[threshold,on,off],case=name,seconds=time.perf_counter()-t,turns=turns,count=len({t[2] for t in turns}),dominant=ids,correct_return=ids[0] is not None and ids[0]==ids[2] and ids[0]!=ids[1]))
            print(threshold,on,off,name,ids,flush=True)
            (OUT/'diarization-conditions.json').write_text(json.dumps({'limits':'Constructed TTS A-B-A with a different final utterance. Not natural speech; dominant-identity/count checks, NOT DER. Development/heldout differ by distortion, not independent speakers.','results':report},indent=2),encoding='utf-8')
        del sd

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('part',choices=['asr','diarization']);p.add_argument('--start',type=int,default=0);p.add_argument('--count',type=int,default=8);args=p.parse_args()
    globals()[args.part]()

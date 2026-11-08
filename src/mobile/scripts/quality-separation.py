"""Research-only ONNX speech separation; never shipped based on a host result alone."""
import hashlib, importlib.util, itertools, json, time
from pathlib import Path
import numpy as np
import onnxruntime as ort
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('compare',ROOT/'scripts/compare-asr.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
p=ROOT/'.work/quality-research/sepformer.onnx'
assert hashlib.sha256(p.read_bytes()).hexdigest()=='e7c682ac986efe7136407b8f8bbd093b514f95aca1d2735a3945b40b3470bfb6'
opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
start=time.perf_counter();session=ort.InferenceSession(str(p),sess_options=opts,providers=['CPUExecutionProvider'])
report={'runtime':ort.__version__,'model':'third-party ONNX export of speechbrain/sepformer-wsj02mix, 8k English training, research only','sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'load_seconds':time.perf_counter()-start,'inputs':[(i.name,i.shape) for i in session.get_inputs()],'cases':[]}

def sisdr(x,y):
    x=x-np.mean(x);y=y-np.mean(y)
    target=np.dot(x,y)*y/(np.dot(y,y)+1e-9)
    return float(10*np.log10((np.dot(target,target)+1e-9)/(np.dot(x-target,x-target)+1e-9)))

for name,fa,fb in [('tts',ROOT/'tests/audio/a1.wav',ROOT/'tests/audio/b1.wav'),('recorded_female',ROOT/'tests/audio/quality-improvement/dialogs-3-16k.wav',ROOT/'tests/audio/quality-improvement/dialogs-6-16k.wav')]:
    a,b=c.read(fa)[::2],c.read(fb)[::2]
    # 2 seconds only: this is feasibility and source fidelity, not a conversation benchmark.
    n=min(16000,len(a)-2400,len(b)-2400);a=a[2400:2400+n].astype(np.float32);b=b[2400:2400+n].astype(np.float32)
    b*=np.sqrt(np.mean(a*a))/max(1e-9,np.sqrt(np.mean(b*b)))
    mix=(a+b)*.5
    padded=(int(np.ceil((n-8)/2000))*2000+8)
    v=np.pad(mix,(0,padded-n))[None,:]
    started=time.perf_counter();out=session.run(None,{'mix_sig':v})[0][0,:,:n];wall=time.perf_counter()-started
    best=max(itertools.permutations(range(2)),key=lambda perm:sum(sisdr(out[i],r) for i,r in zip(perm,[a,b])))
    fidelity=[sisdr(out[i],r) for i,r in zip(best,[a,b])];baseline=[sisdr(mix,r) for r in [a,b]]
    report['cases'].append(dict(case=name,seconds=n/8000,inference_seconds=wall,rtf=wall/(n/8000),input_sisdr=baseline,output_sisdr=fidelity,improvement=[x-y for x,y in zip(fidelity,baseline)],shape=list(out.shape)))
    print(report['cases'][-1],flush=True)
    (ROOT/'tests/evidence/quality-improvement/separation-research.json').write_text(json.dumps(report,indent=2),encoding='utf-8')

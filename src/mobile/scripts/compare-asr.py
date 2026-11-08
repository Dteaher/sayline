import argparse,ctypes,gc,hashlib,json,os,re,statistics,sys,time,wave
from pathlib import Path
import numpy as np
import sherpa_onnx as s

ROOT=Path(__file__).resolve().parents[1]
HERE=ROOT/'.work/stack-comparison'
def native(p):
    b=ctypes.create_unicode_buffer(32768)
    if not ctypes.windll.kernel32.GetShortPathNameW(str(p.resolve()),b,len(b)):raise OSError(str(p))
    return b.value
def read(p):
    data=p.read_bytes()
    # Original FLEURS IEEE float32 WAV has a standard fmt/data chunk layout.
    pos=12;fmt=None;pcm=None
    import struct
    while pos+8<=len(data):
        tag,n=struct.unpack_from('<4sI',data,pos);payload=data[pos+8:pos+8+n]
        if tag==b'fmt ':fmt=struct.unpack_from('<HHIIHH',payload)
        if tag==b'data':pcm=payload
        pos+=8+n+(n%2)
    assert fmt[1]==1 and fmt[2]==16000
    return np.frombuffer(pcm,dtype='<f4' if fmt[0]==3 else '<i2').astype(np.float32)/(1 if fmt[0]==3 else 32768)
def metric(a,b,punct=False):
    def norm(t):
        t=t.lower().replace('ё','е')
        t=re.sub(r'7\s*000','семь тысяч',t).replace('7-м','седьмым')
        return re.findall(r'[а-яa-z0-9]+|[^\w\s]' if punct else r'[а-яa-z0-9]+',t)
    a,b=norm(a),norm(b);last=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        row=[i]
        for j,y in enumerate(b,1):row.append(min(row[-1]+1,last[j]+1,last[j-1]+(x!=y)))
        last=row
    return dict(errors=last[-1],reference_tokens=len(a),rate=last[-1]/max(1,len(a)))
def peak_mb():
    class Counters(ctypes.Structure):
        _fields_=[('cb',ctypes.c_ulong),('PageFaultCount',ctypes.c_ulong)]+[(n,ctypes.c_size_t) for n in ['PeakWorkingSetSize','WorkingSetSize','QuotaPeakPagedPoolUsage','QuotaPagedPoolUsage','QuotaPeakNonPagedPoolUsage','QuotaNonPagedPoolUsage','PagefileUsage','PeakPagefileUsage']]
    c=Counters();c.cb=ctypes.sizeof(c)
    ctypes.windll.kernel32.GetCurrentProcess.restype=ctypes.c_void_p
    fn=ctypes.windll.psapi.GetProcessMemoryInfo
    fn.argtypes=[ctypes.c_void_p,ctypes.POINTER(Counters),ctypes.c_ulong]
    if not fn(ctypes.windll.kernel32.GetCurrentProcess(),ctypes.byref(c),c.cb):raise OSError('Peak RSS measurement failed')
    return c.PeakWorkingSetSize/1048576
def main():
    ap=argparse.ArgumentParser();ap.add_argument('--model',choices=['ctc','rnnt'],required=True);ap.add_argument('--manifest',type=Path);ap.add_argument('--rnnt-dir',type=Path);ap.add_argument('--pipeline',action='store_true');ap.add_argument('--output',type=Path);args=ap.parse_args()
    assert s.__version__=='1.13.8', 'Use sherpa-onnx==1.13.8'
    folder=ROOT/'app/src/main/assets/models' if args.model=='ctc' else (args.rnnt_dir or HERE/'sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16')
    started=time.perf_counter()
    if args.model=='ctc':rec=s.OfflineRecognizer.from_nemo_ctc(model=native(folder/'gigaam-v3.int8.onnx'),tokens=native(folder/'tokens.txt'),num_threads=2,sample_rate=16000,feature_dim=64)
    else:rec=s.OfflineRecognizer.from_transducer(encoder=native(folder/'encoder.int8.onnx'),decoder=native(folder/'decoder.onnx'),joiner=native(folder/'joiner.onnx'),tokens=native(folder/'tokens.txt'),num_threads=2,sample_rate=16000,feature_dim=64,model_type='nemo_transducer')
    report=dict(model=args.model,environment='Windows CPU sherpa1.13.8,2threads; not Android',load_seconds=time.perf_counter()-started,numeric_aliases={'7000 / 7 000':'семь тысяч','7-м':'седьмым'},cases=[])
    manifest=args.manifest or ROOT/'tests/audio/stack-comparison/provenance.json';meta=json.loads(manifest.read_text(encoding='utf-8'))
    cases=[]
    for f in meta['fixtures']:
        p=manifest.parent/f['file'];cases.append((str(f['id']),read(p),f['raw_transcription'],False))
    for f in json.loads((ROOT/'tests/audio/utterances.json').read_text(encoding='utf-8-sig')):
        cases.append(('tts_'+f['file'],read(ROOT/'tests/audio'/ (f['file']+'.wav')),f['text'],True))
    bounds={}
    if args.pipeline:
        sd=json.loads((ROOT/'tests/evidence/stack-comparison/diarization-results.json').read_text())['variants']['pyannote_fp32_wespeaker_resnet34']['cases']
        rows={f['id']:f for f in meta['fixtures']}
        utterances=json.loads((ROOT/'tests/audio/utterances.json').read_text(encoding='utf-8-sig'))
        cases=[]
        for name,p,reference,syn,key in [('human_pipeline',ROOT/'tests/audio/human/returning.wav',' '.join(rows[i]['raw_transcription'] for i in [1634,1614,1634]),False,'human_returning'),('tts_returning',ROOT/'tests/audio/returning.wav',' '.join(x['text'] for x in utterances),True,'synthetic_returning'),('tts_noise',ROOT/'tests/audio/noise.wav',' '.join(x['text'] for x in utterances),True,'synthetic_noise')]:
            audio=read(p);grouped=[]
            for a,b,speaker in sd[key]['turns']:
                if grouped and grouped[-1][2]==speaker and a-grouped[-1][1]<=1.2:grouped[-1]=(grouped[-1][0],max(b,grouped[-1][1]),speaker)
                else:grouped.append((a,b,speaker))
            bounds[name]=[(min(a,max(0,grouped[i-1][1] if i else 0,a-.3)),max(b,min(len(audio)/16000,grouped[i+1][0] if i+1<len(grouped) else len(audio)/16000,b+.3))) for i,(a,b,_) in enumerate(grouped)]
            cases.append((name,audio,reference,syn))
        report['scope']='ASR under identical cached current diarizer intervals/grouping/0.3s context; ASR timing excludes diarization'
    for name,pcm,reference,synthetic in cases:
        elapsed=[];text=''
        for _ in range(3):
            start=time.perf_counter();texts=[]
            for a,b in bounds.get(name,[(0,len(pcm)/16000)]):
                stream=rec.create_stream();stream.accept_waveform(16000,pcm[int(a*16000):int(b*16000)]);rec.decode_stream(stream);r=stream.result;texts.append(r.text.strip());token_example=list(zip(r.tokens[:12],r.timestamps[:12]));del stream
            text=' '.join(texts);elapsed.append(time.perf_counter()-start)
        row=dict(id=name,synthetic=synthetic,audio_seconds=len(pcm)/16000,reference=reference,text=text,word_error=metric(reference,text),punctuation_token_error=metric(reference,text,True),median_seconds=statistics.median(elapsed),rtf=statistics.median(elapsed)/(len(pcm)/16000),token_example=token_example)
        report['cases'].append(row);print(args.model,name,row['word_error'],row['punctuation_token_error'],'rtf',round(row['rtf'],3),flush=True)
    report['peak_process_rss_mib']=peak_mb();report['model_bytes']=sum(p.stat().st_size for p in folder.iterdir() if p.suffix=='.onnx' and (args.model=='rnnt' or p.name.startswith('gigaam')))
    (args.output or HERE/(args.model+('-pipeline' if args.pipeline else '')+'-results.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print('MEMORY',report['peak_process_rss_mib'],flush=True)
if __name__=='__main__':main()

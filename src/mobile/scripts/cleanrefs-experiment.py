"""Pinned neural probe -> production Kotlin windows -> neural vectors -> Kotlin A/B -> scoring.
No audio uploads. Holdout permits only frozen baseline and selected native threshold.
"""
import argparse
import ctypes
import hashlib
import json
import shutil
from pathlib import Path
import random
import time
import wave

ROOT=Path(__file__).resolve().parents[1]
MANIFEST=ROOT/'tests/audio/cleanrefs-20261108/manifest.json'
CACHE=ROOT/'.work/cleanrefs-eval'
EVIDENCE=ROOT/'tests/evidence/cleanrefs-20261108'
CONFIRMATION=False


def vector_fingerprint(audio_digest,model_digest,windows):
    header=json.dumps(dict(audio=audio_digest,model=model_digest,extractor='sherpa1.13.8/16k/threads2/integer-sample-windows/schema2'),sort_keys=True).encode()
    return hashlib.sha256(header+b'\0'+windows).hexdigest()


def cached_vectors_match(path,identity):
    try:
        info=json.loads(Path(str(path)+'.identity.json').read_text(encoding='utf-8'))
        return info['input_identity']==identity and info['vector_sha256']==hashlib.sha256(path.read_bytes()).hexdigest()
    except (OSError,ValueError,KeyError,TypeError):return False


def write_vector_identity(path,identity):
    Path(str(path)+'.identity.json').write_text(json.dumps(dict(input_identity=identity,vector_sha256=hashlib.sha256(path.read_bytes()).hexdigest()),indent=2)+'\n',encoding='utf-8')


def validate_native_case(metadata,key,row,split):
    expected=dict(key=key,id=row['id'],split=split,threshold=int(key.split('-native')[-1])/100,
                  audio_sha256=row['audio_sha256'],reference_sha256=row['reference_sha256'])
    if any(metadata.get(k)!=v for k,v in expected.items()):raise ValueError('Cached native case identity/configuration mismatch')


def validate_holdout_keys(keys,rows,selected):
    expected={f'{r["id"]}-native{round(t*100):03}' for r in rows for t in {.5,selected['native_threshold']}}
    if len(keys)!=len(set(keys)) or set(keys)!=expected:raise ValueError('Unexpected or incomplete frozen holdout key set')


def validate_paired_groups(groups,recording_ids,selected):
    expected={(.5,'baseline',1.2),(selected['native_threshold'],selected.get('variant','clean'),selected['grouping_gap'])}
    if set(groups)!=expected:raise ValueError('Missing or exploratory frozen result group')
    if any(sorted(r['id'] for r in values)!=sorted(recording_ids) for values in groups.values()):
        raise ValueError('Incomplete or duplicated recording IDs in paired holdout')


def native(path):
    if not hasattr(ctypes,'windll'):return str(path)
    value=ctypes.create_unicode_buffer(32768)
    if not ctypes.windll.kernel32.GetShortPathNameW(str(path.resolve()),value,len(value)):raise OSError(path)
    return value.value


def read(path):
    import numpy as np
    with wave.open(str(path),'rb') as w:
        assert (w.getframerate(),w.getnchannels(),w.getsampwidth())==(16000,1,2)
        return np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768


def probe(rows,split,thresholds):
    import sherpa_onnx as s
    assert s.__version__=='1.13.8'
    m=ROOT/'app/src/main/assets/models'
    keys=[]
    for row in rows:
        pcm=read(ROOT/row['audio'])
        assert hashlib.sha256((ROOT/row['audio']).read_bytes()).hexdigest()==row['audio_sha256']
        for threshold in thresholds:
            key=f'{row["id"]}-native{round(threshold*100):03}';keys.append(key)
            metadata=CACHE/f'{key}.json'
            prior=ROOT/'.work/cleanrefs-eval'/f'{key}.json'
            if CONFIRMATION and split=='development' and not metadata.exists() and prior.exists():
                old=json.loads(prior.read_text());old['split']=split
                validate_native_case(old,key,row,split)
                old['reused_source_sha256']=hashlib.sha256(prior.read_bytes()).hexdigest()
                metadata.write_text(json.dumps(old,indent=2)+'\n',encoding='utf-8')
                shutil.copy2(prior.with_suffix('.turns.tsv'),CACHE/f'{key}.turns.tsv')
                for suffix in ('baseline.vectors.tsv','clean.vectors.tsv','baseline.vectors.tsv.identity.json','clean.vectors.tsv.identity.json'):
                    path=prior.parent/f'{key}.{suffix}'
                    if path.exists():shutil.copy2(path,CACHE/path.name)
            if metadata.exists():
                validate_native_case(json.loads(metadata.read_text()),key,row,split)
                continue
            started=time.perf_counter()
            sd=s.OfflineSpeakerDiarization(s.OfflineSpeakerDiarizationConfig(
                segmentation=s.OfflineSpeakerSegmentationModelConfig(pyannote=s.OfflineSpeakerSegmentationPyannoteModelConfig(model=native(m/'segmentation.onnx')),num_threads=2),
                embedding=s.SpeakerEmbeddingExtractorConfig(model=native(m/'speaker.onnx'),num_threads=2),
                clustering=s.FastClusteringConfig(num_clusters=-1,threshold=threshold),min_duration_on=.15,min_duration_off=.4))
            turns=[(float(t.start),float(t.end),int(t.speaker)) for t in sd.process(pcm).sort_by_start_time()]
            del sd
            (CACHE/f'{key}.turns.tsv').write_text(''.join(f'{a}\t{b}\t{k}\n' for a,b,k in turns),encoding='utf-8')
            d=dict(key=key,id=row['id'],split=split,threshold=threshold,seconds=time.perf_counter()-started,turns=turns,
                   audio_sha256=row['audio_sha256'],reference_sha256=row['reference_sha256'])
            metadata.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8')
            print(dict(key=key,turns=len(turns),seconds=round(d['seconds'],2)),flush=True)
    (CACHE/f'{split}-keys.txt').write_text(''.join(k+'\n' for k in keys),encoding='utf-8')


def embed(rows,split):
    import numpy as np
    import sherpa_onnx as s
    keys=(CACHE/f'{split}-keys.txt').read_text().splitlines()
    if split=='holdout':validate_holdout_keys(keys,rows,json.loads((EVIDENCE/'selected-policy.json').read_text()))
    by_id={r['id']:r for r in rows}
    models=[('speaker-refinement.onnx',('baseline','clean','anchored') if CONFIRMATION else ('baseline','clean'))]
    if CONFIRMATION:models.append(('speaker.onnx',('cleanresnet','anchoredresnet')))
    if CONFIRMATION and split=='holdout':
        selected=json.loads((EVIDENCE/'selected-policy.json').read_text())['variant']
        if selected=='anchored':models=[('speaker-refinement.onnx',('baseline','anchored'))]
    for model,variants in models:
        path=ROOT/'app/src/main/assets/models'/model
        ext=s.SpeakerEmbeddingExtractor(s.SpeakerEmbeddingExtractorConfig(model=native(path),num_threads=2))
        model_digest=hashlib.sha256(path.read_bytes()).hexdigest()
        current=None;pcm=None
        for key in keys:
            name=key.split('-native')[0]
            if name!=current:pcm=read(ROOT/by_id[name]['audio']);current=name
            for variant in variants:
                output=CACHE/f'{key}.{variant}.vectors.tsv'
                window_variant={'cleanresnet':'clean','anchoredresnet':'anchored'}.get(variant,variant)
                windows=CACHE/f'{key}.{window_variant}.windows.tsv'
                identity=vector_fingerprint(by_id[name]['audio_sha256'],model_digest,windows.read_bytes())
                if cached_vectors_match(output,identity):continue
                lines=[];started=time.perf_counter();valid=0
                for line in windows.read_text().splitlines():
                    if not line.strip():continue
                    index,a,b=line.split('\t');a=int(a);b=int(b)
                    if not 0<=a<b<=len(pcm):raise ValueError('Invalid exact sample window')
                    stream=ext.create_stream();stream.accept_waveform(16000,pcm[a:b]);stream.input_finished()
                    v=ext.compute(stream) if ext.is_ready(stream) else None
                    if v is not None and (not np.isfinite(v).all() or np.linalg.norm(v)<1e-9):v=None
                    if v is not None:valid+=1
                    lines.append('\t'.join([index,str((b-a)/16000),*map(str,[] if v is None else v)]));del stream
                output.write_text('\n'.join(lines)+'\n',encoding='utf-8');write_vector_identity(output,identity)
                print(dict(key=key,variant=variant,valid_vectors=valid,seconds=round(time.perf_counter()-started,2)),flush=True)
        del ext


def score_cases(rows,split):
    from diarization_metrics import score,read_rttm
    from importlib.util import spec_from_file_location,module_from_spec
    spec=spec_from_file_location('aggregation',ROOT/'scripts/score-diarization-experiment.py');a=module_from_spec(spec);spec.loader.exec_module(a)
    keys=(CACHE/f'{split}-keys.txt').read_text().splitlines()
    results=[]
    selected=json.loads((EVIDENCE/'selected-policy.json').read_text()) if split=='holdout' else None
    if selected:validate_holdout_keys(keys,rows,selected)
    for row in rows:
        assert hashlib.sha256((ROOT/row['reference_rttm']).read_bytes()).hexdigest()==row['reference_sha256']
        ref=read_rttm(ROOT/row['reference_rttm'])[row['id']]
        grid=[.5] if CONFIRMATION else [.5,.6,.7]
        expected={f'{row["id"]}-native{round(t*100):03}' for t in (grid if split=='development' else sorted({.5,selected['native_threshold']}))}
        if not expected.issubset(keys):raise ValueError('Incomplete frozen cases')
        for key in sorted(expected):
            metadata=json.loads((CACHE/f'{key}.json').read_text())
            validate_native_case(metadata,key,row,split)
            if split=='development':variants=[(name,1.2) for name in ('baseline','clean','clean_resnet60','clean_fusion60','clean_fusion50','anchored','anchored_consensus')] if CONFIRMATION else [('baseline',1.2),*(('clean',g) for g in (1.2,.4,0.))]
            else:
                variants=[]
                if metadata['threshold']==.5:variants.append(('baseline',1.2))
                if metadata['threshold']==selected['native_threshold']:variants.append((selected.get('variant','clean'),selected['grouping_gap']))
            for variant,gap in variants:
                path=CACHE/f'{key}.{variant}.gap{int(gap*100)}.output.tsv'
                if not path.exists():raise ValueError(f'Missing {path.name}')
                turns=[(float(f[0]),float(f[1]),f[2]) for line in path.read_text().splitlines() if (f:=line.split())]
                r=score(ref,turns,row['duration_seconds'])
                results.append(dict(id=row['id'],native_threshold=metadata['threshold'],variant=variant,gap=gap,**r))
    grouped={}
    for r in results:grouped.setdefault((r['native_threshold'],r['variant'],r['gap']),[]).append(r)
    if selected:validate_paired_groups(grouped,[r['id'] for r in rows],selected)
    baseline=sorted(grouped[(.5,'baseline',1.2)],key=lambda r:r['id'])
    summaries=[]
    for (threshold,variant,gap),values in grouped.items():
        values=sorted(values,key=lambda r:r['id'])
        if len(values)!=len(rows):raise ValueError('Incomplete paired report')
        summaries.append(dict(native_threshold=threshold,variant=variant,gap=gap,
                              **a.aggregate(values),paired_der_delta_ci95=a.paired_ci(baseline,values)))
    EVIDENCE.mkdir(parents=True,exist_ok=True)
    (EVIDENCE/f'{split}.json').write_text(json.dumps(dict(split=split,limits='AMI English meetings, manual-derived only_words reference, not natural Russian. Development reused for tuning; new holdout uses published '+('dev' if CONFIRMATION else 'test')+' partition.',results=results,summaries=summaries),indent=2)+'\n',encoding='utf-8')
    for r in sorted(summaries,key=lambda x:x['der']):print({k:r[k] for k in ('native_threshold','variant','gap','der','jer','count_mae','short_0.5')})


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('phase',choices=['probe','embed','score']);p.add_argument('--split',choices=['development','holdout'],default='development');p.add_argument('--confirmation',action='store_true');args=p.parse_args()
    global MANIFEST,CACHE,EVIDENCE,CONFIRMATION
    CONFIRMATION=args.confirmation
    if CONFIRMATION:
        MANIFEST=ROOT/'tests/audio/cleanrefs-confirmation-20261108/manifest.json'
        CACHE=ROOT/'.work/cleanrefs-confirmation-eval';EVIDENCE=ROOT/'tests/evidence/cleanrefs-confirmation-20261108'
    CACHE.mkdir(parents=True,exist_ok=True);EVIDENCE.mkdir(parents=True,exist_ok=True)
    manifest=json.loads(MANIFEST.read_text())
    rows=[r for r in manifest['recordings'] if r['split']==args.split]
    if len(rows)!=((9 if CONFIRMATION else 6) if args.split=='development' else 3):raise ValueError('Incomplete corpus manifest')
    thresholds=[.5] if CONFIRMATION else [.5,.6,.7]
    if args.split=='holdout':
        chosen=json.loads((EVIDENCE/'selected-policy.json').read_text())
        if not chosen['locked_before_holdout']:raise ValueError('Candidate is not locked')
        thresholds=sorted({.5,chosen['native_threshold']})
        (CACHE/'selected-native.txt').write_text(str(round(chosen['native_threshold']*100)))
        (CACHE/'selected-gap.txt').write_text(str(chosen['grouping_gap']))
        (CACHE/'selected-variant.txt').write_text(chosen.get('variant','clean'))
    if args.phase=='probe':probe(rows,args.split,thresholds)
    elif args.phase=='embed':embed(rows,args.split)
    else:score_cases(rows,args.split)


if __name__=='__main__':main()

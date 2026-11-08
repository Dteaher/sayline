"""Fresh frozen fragment validation; local pinned inference, no tuning."""
import argparse
import importlib.util
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def load(name,file):
    s=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def main():
    phase=argparse.ArgumentParser();phase.add_argument('phase',choices=['probe','embed','score']);args=phase.parse_args()
    x=load('cleanrefs','cleanrefs-experiment.py');x.CACHE=ROOT/'.work/fragment-validation-eval';x.CACHE.mkdir(exist_ok=True)
    manifest=json.loads((ROOT/'tests/audio/fragment-validation-20261108/manifest.json').read_text(encoding='utf-8'))
    rows=manifest['recordings'];assert len(rows)==3
    if args.phase=='probe':x.probe(rows,'holdout',[.5]);return
    if args.phase=='embed':
        import sherpa_onnx as s
        import numpy as np
        assert s.__version__=='1.13.8'
        ext=s.SpeakerEmbeddingExtractor(s.SpeakerEmbeddingExtractorConfig(model=x.native(ROOT/'app/src/main/assets/models/speaker-refinement.onnx'),num_threads=2))
        for row in rows:
            key=row['id']+'-native050';pcm=x.read(ROOT/row['audio']);lines=[]
            for line in (x.CACHE/f'{key}.anchored.windows.tsv').read_text().splitlines():
                i,a,b=map(int,line.split());stream=ext.create_stream();stream.accept_waveform(16000,pcm[a:b]);stream.input_finished()
                v=ext.compute(stream) if ext.is_ready(stream) else None
                if v is not None and (not np.isfinite(v).all() or np.linalg.norm(v)<1e-9):v=None
                lines.append('\t'.join([str(i),str((b-a)/16000),*map(str,[] if v is None else v)]));del stream
            (x.CACHE/f'{key}.anchored.vectors.tsv').write_text('\n'.join(lines)+'\n',encoding='utf-8')
            print(key,flush=True)
        return
    metrics=load('metrics','diarization_metrics.py');a=load('aggregation','score-diarization-experiment.py');result=[]
    for row in rows:
        ref=metrics.read_rttm(ROOT/row['reference_rttm'])[row['id']]
        for variant in ['current','candidate']:
            path=x.CACHE/(row['id']+'-native050.'+variant+'.tsv')
            turns=[(float(f[0]),float(f[1]),f[2]) for l in path.read_text().splitlines() if (f:=l.split())]
            result.append(dict(id=row['id'],variant=variant,**metrics.score(ref,turns,180)))
    report=dict(scope='3 newly frozen English AMI crops; no tuning after validation',results=result,
                summaries={v:a.aggregate([r for r in result if r['variant']==v]) for v in ['current','candidate']})
    (ROOT/'tests/evidence/fragment-validation-20261108/results.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report['summaries'],indent=2))
if __name__=='__main__':main()

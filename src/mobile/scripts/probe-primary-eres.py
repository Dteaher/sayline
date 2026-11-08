"""Diagnostic primary-embedding ablation on observed AMI; never a holdout."""
import importlib.util
import json
from pathlib import Path
import time
import sherpa_onnx as s
from diarization_metrics import read_rttm,score
ROOT=Path(__file__).resolve().parents[1]
def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def main():
    assert s.__version__=='1.13.8'
    c=module('cleanrefs','cleanrefs-experiment.py');a=module('ablation','score-native-ablation.py')
    manifest=json.loads((ROOT/'tests/audio/cleanrefs-confirmation-20261108/manifest.json').read_text(encoding='utf-8'))
    rows={r['id']:r for r in manifest['recordings']};out=ROOT/'tests/evidence/primary-eres-20261108';out.mkdir(exist_ok=True)
    model=ROOT/'app/src/main/assets/models';results=[]
    for name in ['ES2002a','IS1009a','EN2002a']:
        row=rows[name];pcm=c.read(ROOT/row['audio']);ref=read_rttm(ROOT/row['reference_rttm'])[name]
        for threshold in [.5,.6]:
            path=out/f'{name}-{threshold}.json'
            if path.exists():results.append(json.loads(path.read_text(encoding='utf-8')));continue
            started=time.perf_counter()
            sd=s.OfflineSpeakerDiarization(s.OfflineSpeakerDiarizationConfig(
                segmentation=s.OfflineSpeakerSegmentationModelConfig(pyannote=s.OfflineSpeakerSegmentationPyannoteModelConfig(model=c.native(model/'segmentation.onnx')),num_threads=2),
                embedding=s.SpeakerEmbeddingExtractorConfig(model=c.native(model/'speaker-refinement.onnx'),num_threads=2),
                clustering=s.FastClusteringConfig(num_clusters=-1,threshold=threshold),min_duration_on=.15,min_duration_off=.4))
            turns=[(float(t.start),float(t.end),int(t.speaker)) for t in sd.process(pcm).sort_by_start_time()];del sd
            report=dict(id=name,threshold=threshold,seconds=time.perf_counter()-started,scope='Observed English AMI diagnostic; primary ERes only, no second pass',
                turns=turns,**score(ref,a.group_turns(turns),180))
            path.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');results.append(report)
            print({k:report[k] for k in ['id','threshold','der','jer','hypothesis_speakers','seconds']},flush=True)
    (out/'results.json').write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
if __name__=='__main__':main()

"""Diagnose speaker confusion using known-actor, full-utterance embeddings, host only."""
import importlib.util,json
from pathlib import Path
import numpy as np
import sherpa_onnx as s
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('compare',ROOT/'scripts/compare-asr.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
paths=sorted((ROOT/'tests/audio/quality-improvement').glob('dialogs-*-16k.wav'))
rows=json.loads((ROOT/'tests/audio/quality-improvement/provenance.json').read_text(encoding='utf-8'))['rows']
report={}
for name,path in [('resnet34',ROOT/'app/src/main/assets/models/speaker.onnx'),('resnet34_lm',ROOT/'.work/quality-research/wespeaker_en_voxceleb_resnet34_LM.onnx'),('eres2net',ROOT/'.work/quality-research/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx')]:
    ext=s.SpeakerEmbeddingExtractor(s.SpeakerEmbeddingExtractorConfig(model=c.native(path),num_threads=2))
    vectors=[]
    for p in paths:
        st=ext.create_stream();st.accept_waveform(16000,c.read(p));st.input_finished()
        if not ext.is_ready(st): raise ValueError(p)
        v=np.array(ext.compute(st));vectors.append(v/np.linalg.norm(v))
    a=np.array(vectors);distance=1-a@a.T
    labels=[rows[int(p.name.split('-')[1])]['speaker_id'] for p in paths]
    report[name]={'files':[p.name for p in paths],'actors':labels,'cosine_distance':distance.tolist()}
    print(name,labels,np.round(distance,2).tolist(),flush=True)
    (ROOT/'tests/evidence/quality-improvement/speaker-vectors.json').write_text(json.dumps(report,indent=2),encoding='utf-8')

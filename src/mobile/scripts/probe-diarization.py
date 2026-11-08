"""Cache pinned native diarization + independent embeddings for Kotlin A/B evaluation.
Run development first; --split holdout is an explicit one-time final evaluation.
"""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import time
import wave
import numpy as np
import sherpa_onnx as s
from diarization_metrics import read_rttm, score
from diarization_protocol import probe_parameters

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / 'tests/audio/diarization-20261108/manifest.json'
CACHE = ROOT / '.work/diarization-eval'


def native(path):
    if not hasattr(ctypes, 'windll'): return str(path)
    value = ctypes.create_unicode_buffer(32768)
    if not ctypes.windll.kernel32.GetShortPathNameW(str(path.resolve()), value, len(value)): raise OSError(path)
    return value.value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--split', choices=['development', 'holdout'], default='development')
    parser.add_argument('--on', nargs='+', type=float)
    args = parser.parse_args()
    args.on = probe_parameters(args.split,args.on)
    if args.split == 'holdout':
        selected = json.loads((ROOT/'tests/evidence/diarization-20261108/selected-policy.json').read_text(encoding='utf-8'))
        if not selected['selected_before_holdout'] or selected['min_duration_on'] != .15 or selected['refinement_threshold'] != .6:
            raise ValueError('Frozen candidate policy missing or incompatible')
    assert s.__version__ == '1.13.8'
    manifest = json.loads(CORPUS.read_text(encoding='utf-8'))
    assert manifest['recordings'] and manifest['speaker_disjoint']
    CACHE.mkdir(parents=True, exist_ok=True)
    models = ROOT / 'app/src/main/assets/models'
    output = []
    for row in manifest['recordings']:
        if row['split'] != args.split: continue
        wav = ROOT / row['audio']
        assert hashlib.sha256(wav.read_bytes()).hexdigest() == row['audio_sha256']
        ref = read_rttm(ROOT / row['reference_rttm'])[row['id']]
        with wave.open(str(wav), 'rb') as w:
            assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (16000, 1, 2)
            pcm = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32) / 32768
        for on in args.on:
            key = f'{row["id"]}-on{round(on*100):03}'
            metadata = CACHE / f'{key}.json'
            if metadata.exists():
                output.append(json.loads(metadata.read_text(encoding='utf-8'))); continue
            started = time.perf_counter()
            diarizer = s.OfflineSpeakerDiarization(s.OfflineSpeakerDiarizationConfig(
                segmentation=s.OfflineSpeakerSegmentationModelConfig(pyannote=s.OfflineSpeakerSegmentationPyannoteModelConfig(model=native(models/'segmentation.onnx')), num_threads=2),
                embedding=s.SpeakerEmbeddingExtractorConfig(model=native(models/'speaker.onnx'), num_threads=2),
                clustering=s.FastClusteringConfig(num_clusters=-1, threshold=.5, compute_confidence=True),
                min_duration_on=on, min_duration_off=.4))
            turns = [(float(t.start), float(t.end), int(t.speaker), float(t.confidence)) for t in diarizer.process(pcm).sort_by_start_time()]
            native_seconds = time.perf_counter() - started
            del diarizer
            extract_started = time.perf_counter()
            extractor = s.SpeakerEmbeddingExtractor(s.SpeakerEmbeddingExtractorConfig(model=native(models/'speaker-refinement.onnx'), num_threads=2))
            lines = []
            for i, (a, b, speaker, confidence) in enumerate(turns):
                mixed = any(j != i and other != speaker and min(b, d) - max(a, c) > .05 for j, (c, d, other, _) in enumerate(turns))
                vector = None
                if not mixed:
                    start = int(a*16000); stop = min(len(pcm), int(b*16000))
                    if stop-start > 160000: start += (stop-start-160000)//2
                    stream = extractor.create_stream(); stream.accept_waveform(16000, pcm[start:min(stop,start+160000)]); stream.input_finished()
                    if extractor.is_ready(stream): vector = extractor.compute(stream)
                    del stream
                fields = [a, b, speaker, confidence, *([] if vector is None else vector)]
                lines.append('\t'.join(str(x) for x in fields))
            del extractor
            (CACHE / f'{key}.input.tsv').write_text('\n'.join(lines)+'\n', encoding='utf-8')
            result = dict(key=key, id=row['id'], split=row['split'], min_duration_on=on, duration_seconds=len(pcm)/16000,
                          native_seconds=native_seconds, all_turn_embedding_seconds=time.perf_counter()-extract_started,
                          native_turns=[list(t) for t in turns], native_score=score(ref, [t[:3] for t in turns], len(pcm)/16000),
                          hidden_stages='NOT EXPOSED: raw frame segmentation and native embedding retention cannot be counted through this API')
            metadata.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
            output.append(result)
            print(dict(key=key, turns=len(turns), der=result['native_score']['der'], native_seconds=round(native_seconds,2)), flush=True)
    (CACHE / f'{args.split}-native.json').write_text(json.dumps(output, indent=2)+'\n', encoding='utf-8')
    (CACHE / f'{args.split}-keys.txt').write_text(''.join(item['key']+'\n' for item in output), encoding='utf-8')


if __name__ == '__main__': main()

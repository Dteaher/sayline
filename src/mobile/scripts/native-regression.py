"""Offline native diarization safety regression using existing synthetic voices.

Run with the Python environment containing the pinned sherpa-onnx version:
  python scripts/native-regression.py --output tests/evidence/native-regression/current.json

This deliberately combines overlapping voices with one requested cluster. Versions
with an unclamped TopkIndex can crash the Python process. It measures native safety,
not recognition accuracy, and must be run as a separate process.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import time
import wave

import numpy as np
import sherpa_onnx as sherpa

ROOT = Path(__file__).resolve().parents[1]


def native_path(path):
    path = str(path.resolve())
    if os.name == 'nt':
        buffer = ctypes.create_unicode_buffer(32768)
        if ctypes.windll.kernel32.GetShortPathNameW(path, buffer, len(buffer)):
            return buffer.value
    return path


def read_fixture(path):
    with wave.open(str(path)) as audio:
        if (audio.getframerate(), audio.getnchannels(), audio.getsampwidth()) != (16000, 1, 2):
            raise ValueError('The safety fixture requires 16 kHz mono PCM16 WAV files')
        return np.frombuffer(audio.readframes(audio.getnframes()), dtype='<i2').astype(np.float32) / 32768


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models', type=Path, default=ROOT / 'app/src/main/assets/models')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if os.name == 'nt':
        # A deliberately tested native fault must not open an interactive WER window.
        ctypes.windll.kernel32.SetErrorMode(0x0001 | 0x0002)
    utterances = json.loads((ROOT / 'tests/audio/utterances.json').read_text(encoding='utf-8-sig'))
    files = [ROOT / 'tests/audio' / (item['file'] + '.wav') for item in utterances[:2]]
    a, b = map(read_fixture, files)
    samples = np.zeros(max(len(a), len(b)) + 32000, dtype=np.float32)
    samples[:len(a)] += .5 * a
    samples[16000:16000 + len(b)] += .5 * b
    config = sherpa.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=native_path(args.models / 'segmentation.onnx')), num_threads=2),
        embedding=sherpa.SpeakerEmbeddingExtractorConfig(
            model=native_path(args.models / 'speaker.onnx'), num_threads=2),
        clustering=sherpa.FastClusteringConfig(num_clusters=1, threshold=.5),
        min_duration_on=.3, min_duration_off=.4)
    assert config.validate(), 'Invalid local model configuration'
    print('START', sherpa.__version__, 'synthetic-overlap-forced-one-cluster', flush=True)
    diarizer = sherpa.OfflineSpeakerDiarization(config)
    started = time.perf_counter()
    turns = [{'start': float(t.start), 'end': float(t.end), 'speaker': int(t.speaker)}
             for t in diarizer.process(samples).sort_by_start_time()]
    elapsed = time.perf_counter() - started
    del diarizer
    assert turns and {t['speaker'] for t in turns} == {0}, 'Expected one nonempty model-derived cluster'
    report = {
        'sherpa_onnx': sherpa.__version__, 'environment': 'host CPU; not an Android test',
        'purpose': 'native safety; synthetic voices; not accuracy evidence',
        'audio_seconds': len(samples) / 16000, 'wall_seconds': elapsed,
        'input_float32_sha256': hashlib.sha256(samples.astype('<f4').tobytes()).hexdigest(),
        'source_fixtures': [{'file': str(p.relative_to(ROOT)),
                             'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in files],
        'turns': turns, 'passed': True}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()

"""Repeatable host CPU smoke tests; synthetic fixtures are NOT a conversation accuracy claim."""
import json
import os
import shutil
from pathlib import Path
import time
import wave
import numpy as np
import sherpa_onnx as s

ROOT = Path(__file__).resolve().parents[1]
SOURCE_MODELS = ROOT / 'app/src/main/assets/models'
# sherpa-onnx's Windows C++ file reader requires an ASCII filesystem path.
MODELS = Path(os.environ.get('LOCALAPPDATA', str(ROOT / '.work'))) / 'NeuroScribe' / 'host-models'
AUDIO = ROOT / 'tests/audio'

def read(path):
    with wave.open(str(path)) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2
        return np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32) / 32768

def write(name, samples):
    with wave.open(str(AUDIO / (name + '.wav')), 'wb') as w:
        w.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        w.writeframes((np.clip(samples, -1, 1) * 32767).astype('<i2').tobytes())

def normalize(text):
    import re
    return re.findall(r'[а-яa-z0-9]+', text.lower().replace('ё', 'е'))

def punctuation_tokens(text):
    import re
    return re.findall(r'[а-яa-z0-9]+|[^\w\s]', text.lower().replace('ё', 'е'))

def wer(reference, text, tokenizer=normalize):
    a, b = tokenizer(reference), tokenizer(text)
    row = list(range(len(b) + 1))
    for i, word in enumerate(a, 1):
        nxt = [i]
        for j, other in enumerate(b, 1):
            nxt.append(min(nxt[-1] + 1, row[j] + 1, row[j-1] + (word != other)))
        row = nxt
    return row[-1] / max(1, len(a))

def main():
    assert s.__version__ == '1.13.8', 'Use pinned sherpa-onnx1.13.8: earlier versions have a native diarization crash'
    MODELS.mkdir(parents=True, exist_ok=True)
    for model in SOURCE_MODELS.iterdir():
        target = MODELS / model.name
        if model.is_file() and (not target.exists() or target.stat().st_size != model.stat().st_size):
            shutil.copy2(model, target)
    utterances = json.loads((AUDIO / 'utterances.json').read_text(encoding='utf-8-sig'))
    a1, b1, a2 = [read(AUDIO / (u['file'] + '.wav')) for u in utterances]
    gap = np.zeros(16000, dtype=np.float32)
    two = np.concatenate([a1, gap, b1])
    returning = np.concatenate([two, gap, a2])
    noise = np.random.default_rng(42).normal(0, .012, returning.size).astype(np.float32)
    cases = {'single': a1, 'two': two, 'returning': returning, 'noise': np.clip(returning + noise, -1, 1), 'silence': np.zeros(80000, np.float32)}
    for name, pcm in cases.items(): write(name, pcm)
    expected = {
        'single': utterances[0]['text'], 'two': ' '.join(u['text'] for u in utterances[:2]),
        'returning': ' '.join(u['text'] for u in utterances), 'noise': ' '.join(u['text'] for u in utterances), 'silence': ''}
    report = {'environment': 'Windows x64 CPU, sherpa-onnx ' + s.__version__, 'synthetic': True, 'cases': {}}
    for name, samples in cases.items():
        print('INFERENCE', name, flush=True)
        start = time.perf_counter()
        config = s.OfflineSpeakerDiarizationConfig(
            segmentation=s.OfflineSpeakerSegmentationModelConfig(pyannote=s.OfflineSpeakerSegmentationPyannoteModelConfig(model=str(MODELS / 'segmentation.onnx')), num_threads=2),
            embedding=s.SpeakerEmbeddingExtractorConfig(model=str(MODELS / 'speaker.onnx'), num_threads=2),
            clustering=s.FastClusteringConfig(num_clusters=-1, threshold=.5), min_duration_on=.3, min_duration_off=.4)
        assert config.validate()
        sd = s.OfflineSpeakerDiarization(config)
        segments = sd.process(samples).sort_by_start_time()
        turns = [(max(0, float(t.start)), min(len(samples)/16000, float(t.end)), int(t.speaker)) for t in segments]
        del sd
        rec = s.OfflineRecognizer.from_nemo_ctc(
            model=str(MODELS/'gigaam-v3.int8.onnx'), tokens=str(MODELS/'tokens.txt'),
            num_threads=2, sample_rate=16000, feature_dim=64, decoding_method='greedy_search')
        grouped = []
        for begin, end, speaker in turns:
            if end - begin <= .001: continue
            if grouped and grouped[-1][2] == speaker and begin - grouped[-1][1] <= 1.2:
                grouped[-1] = (grouped[-1][0], max(grouped[-1][1], end), speaker)
            else: grouped.append((begin, end, speaker))
        replicas = []
        for index, (begin, end, speaker) in enumerate(grouped):
            # Fixtures are shorter than one recognition window; Android owns long-window seam tests.
            assert end-begin <= 20, 'Add a measured long-window fixture before extending this host benchmark'
            context_begin = min(begin, max(0, grouped[index-1][1] if index else 0, begin-.3))
            stop = max(end, min(len(samples)/16000, grouped[index+1][0] if index+1<len(grouped) else len(samples)/16000, end+.3))
            if begin < end:
                stream = rec.create_stream()
                stream.accept_waveform(16000, samples[int(context_begin*16000):int(stop*16000)])
                rec.decode_stream(stream)
                text = stream.result.text.strip()
                if text: replicas.append({'start': begin, 'end': end, 'speaker': speaker, 'text': text})
        del rec
        elapsed = time.perf_counter() - start
        actual = ' '.join(r['text'] for r in replicas)
        entry = {'audio_seconds': len(samples)/16000, 'processing_seconds': elapsed, 'rtf': elapsed/(len(samples)/16000),
                 'speaker_count': len({r['speaker'] for r in replicas}), 'wer': wer(expected[name], actual) if expected[name] else None,
                 'punctuation_token_error': wer(expected[name], actual, punctuation_tokens) if expected[name] else None,
                 'reference': expected[name], 'replicas': replicas, 'diarization_turns': turns}
        if name in ('returning', 'noise'):
            windows = [(0, len(a1)/16000), ((len(a1)+16000)/16000, (len(a1)+16000+len(b1))/16000), ((len(a1)+len(b1)+32000)/16000, len(samples)/16000)]
            ids = []
            for left, right in windows:
                totals = {}
                for begin, end, speaker in turns:
                    totals[speaker] = totals.get(speaker, 0) + max(0, min(right, end)-max(left, begin))
                ids.append(max(totals, key=totals.get) if totals and max(totals.values()) > 0 else None)
            entry['dominant_cluster_per_utterance'] = ids
            entry['returning_speaker_preserved'] = ids[0] is not None and ids[0] == ids[2] and ids[0] != ids[1]
        report['cases'][name] = entry
        (ROOT/'tests/host-results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(name, json.dumps({k:v for k,v in entry.items() if k not in ['reference','replicas','diarization_turns']}, ensure_ascii=False), flush=True)
    c = report['cases']
    report['checks'] = {
        'single_voice': c['single']['speaker_count'] == 1 and c['single']['wer'] < .35,
        'two_voices': c['two']['speaker_count'] == 2 and c['two']['wer'] < .35,
        'returning_voice': c['returning']['returning_speaker_preserved'] and c['returning']['wer'] < .35,
        'noise': c['noise']['returning_speaker_preserved'] and c['noise']['wer'] < .35,
        'silence': not c['silence']['replicas'] and not c['silence']['diarization_turns'],
    }
    (ROOT/'tests/host-results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('CHECKS', report['checks'], flush=True)
    assert all(report['checks'].values()), 'Host smoke-test failed; inspect tests/host-results.json'

if __name__ == '__main__': main()

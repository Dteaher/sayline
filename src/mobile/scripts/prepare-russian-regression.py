"""Extract fixed FLEURS ru_ru validation rows 20--31, without ASR selection.

Uses the existing original Parquet byte-range cache; never uploads audio.
Requires pyarrow (existing research deps may be passed with --deps).
Missing/corrupt cache or rows fail the run: no replacement recordings.
"""
import argparse
import array
import hashlib
import io
import json
import math
from pathlib import Path
import struct
import sys
import wave

ROOT = Path(__file__).resolve().parents[1]
ROWS = tuple(range(20, 32))
SOURCE_SIZE = 243616673
SOURCE_URL = 'https://huggingface.co/datasets/google/fleurs/resolve/refs%2Fconvert%2Fparquet/ru_ru/validation/0000.parquet'


def sha(data):
    return hashlib.sha256(data).hexdigest()


class CachedParquet(io.RawIOBase):
    """Read only verified, present bytes; never fill a cache hole with zeroes."""
    def __init__(self, directory):
        self.pos = 0
        self.blocks = []
        self.used = set()
        for path in sorted(directory.glob('range-*.bin')):
            _, start, stop = path.stem.split('-')
            start, stop = int(start), int(stop)
            if path.stat().st_size != stop - start:
                raise ValueError(f'Incomplete cache block: {path}')
            self.blocks.append((start, stop, path))

    def readable(self): return True
    def seekable(self): return True
    def tell(self): return self.pos

    def seek(self, offset, whence=0):
        self.pos = offset if whence == 0 else self.pos + offset if whence == 1 else SOURCE_SIZE + offset
        if self.pos < 0: raise ValueError('Negative source seek')
        return self.pos

    def read(self, count=-1):
        count = max(0, min(SOURCE_SIZE - self.pos, SOURCE_SIZE if count < 0 else count))
        data, coverage = bytearray(count), bytearray(count)
        start, stop = self.pos, self.pos + count
        for left, right, path in self.blocks:
            a, b = max(left, start), min(right, stop)
            if a >= b: continue
            with path.open('rb') as stream:
                stream.seek(a - left)
                part = stream.read(b - a)
            if len(part) != b - a: raise ValueError(f'Truncated cache: {path}')
            x, y = a - start, b - start
            # Duplicate overlapping original ranges must agree exactly.
            if any(coverage[x:y]):
                for offset, value in enumerate(part, x):
                    if coverage[offset] and data[offset] != value:
                        raise ValueError(f'Conflicting cache bytes: {path}')
            data[x:y], coverage[x:y] = part, b'\1' * len(part)
            self.used.add(path)
        if not all(coverage): raise ValueError(f'Missing original source bytes: {start}..{stop}')
        self.pos = stop
        return bytes(data)


def pcm16_wav(source, expected_samples):
    """Convert FLEURS IEEE float WAV to PCM16; retain the 16 kHz sample grid."""
    if source[:4] != b'RIFF' or source[8:12] != b'WAVE':
        raise ValueError('Expected original WAV')
    if struct.unpack_from('<I', source, 4)[0] + 8 != len(source):
        raise ValueError('Invalid RIFF size')
    fmt, samples = None, None
    offset = 12
    while offset + 8 <= len(source):
        tag, size = struct.unpack_from('<4sI', source, offset)
        begin, end = offset + 8, offset + 8 + size
        if end > len(source): raise ValueError('Truncated WAV chunk')
        if tag == b'fmt ': fmt = struct.unpack_from('<HHIIHH', source, begin)
        if tag == b'data': samples = source[begin:end]
        offset = end + size % 2
    if fmt != (3, 1, 16000, 64000, 4, 32) or samples is None:
        raise ValueError(f'Unexpected original float WAV format: {fmt}')
    if len(samples) != expected_samples * 4: raise ValueError('Sample count mismatch')
    values = [value for (value,) in struct.iter_unpack('<f', samples)]
    if not all(math.isfinite(value) for value in values): raise ValueError('Non-finite audio')
    converted = array.array('h', (min(32767, max(-32768, round(value * 32768))) for value in values))
    if sys.byteorder != 'little': converted.byteswap()
    output = io.BytesIO()
    with wave.open(output, 'wb') as wav:
        wav.setparams((1, 2, 16000, len(values), 'NONE', 'not compressed'))
        wav.writeframes(converted.tobytes())
    return output.getvalue(), sum(value < -1 or value > 1 for value in values)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, default=ROOT / '.work/stack-comparison-research')
    parser.add_argument('--deps', type=Path, help='Existing directory containing pyarrow; no install/download')
    parser.add_argument('--output', type=Path, default=ROOT / 'tests/audio/cleanrefs-russian')
    args = parser.parse_args()
    if args.deps: sys.path.insert(0, str(args.deps.resolve()))
    import pyarrow.parquet as pq

    cached = CachedParquet(args.cache)
    parquet = pq.ParquetFile(cached)
    columns = ['id', 'num_samples', 'transcription', 'raw_transcription', 'gender', 'audio']
    first = next(parquet.iter_batches(batch_size=32, columns=columns, use_threads=False)).to_pylist()
    if len(first) != 32: raise ValueError('Required fixed rows unavailable')
    fixtures, prepared = [], {}
    for index in ROWS:
        row = first[index]
        original = row.pop('audio')
        audio, out_of_range = pcm16_wav(original['bytes'], row['num_samples'])
        name = f'fleurs_ru_validation_{index}_pcm16.wav'
        location = args.output.resolve() / name
        audio_path = location.relative_to(ROOT).as_posix() if location.is_relative_to(ROOT) else str(location)
        entry = dict(row, row_index=index, recording_id=f'fleurs_ru_validation_{index}', file=name, audio=audio_path,
                     sample_rate=16000, channels=1, sample_width_bytes=2,
                     duration_seconds=row['num_samples'] / 16000,
                     sha256=sha(audio), audio_sha256=sha(audio), bytes=len(audio),
                     source_audio_path=original['path'], source_audio_sha256=sha(original['bytes']),
                     source_audio_bytes=len(original['bytes']), source_out_of_range_samples=out_of_range,
                     reference=row['transcription'])
        fixtures.append(entry)
        prepared[name] = audio

    manifest = dict(dataset='google/fleurs', config='ru_ru', split='validation', license='CC-BY-4.0',
                    dataset_card='https://huggingface.co/datasets/google/fleurs', source_url=SOURCE_URL,
                    selection='All fixed consecutive zero-based rows 20..31, selected before inference; no replacements.',
                    purpose='Russian ASR regression only; read speech, not spontaneous conversation or diarization gold.',
                    reference_note='Unmodified published transcription and raw_transcription; no manual listening/alignment validation.',
                    independence_note='New recordings, not fully text-independent: row23 sentence id1640 shares text with earlier evaluated row19; rows26 and27 share sentence id1514. Key records by recording_id or row_index, not sentence id.',
                    conversion='IEEE float32 mono 16000 Hz -> PCM16 via round(x*32768), saturating signed int16; no resampling/crops.',
                    total_duration_seconds=round(sum(row['duration_seconds'] for row in fixtures), 6), fixtures=fixtures)
    provenance = dict(dataset='FLEURS: Few-shot Learning Evaluation of Universal Representations of Speech',
                      authors='Alexis Conneau et al. / Google', license='CC-BY-4.0',
                      license_url='https://creativecommons.org/licenses/by/4.0/',
                      attribution_url='https://huggingface.co/datasets/google/fleurs',
                      license_evidence='https://huggingface.co/datasets/google/fleurs/raw/main/README.md (license: cc-by-4.0)',
                      source_url=SOURCE_URL, source_size=SOURCE_SIZE, source_revision='Unknown original cache revision; per-range and embedded-audio SHA-256 retained.',
                      extraction='Local original Parquet range cache; every accessed byte required, overlapping bytes checked equal.',
                      source_ranges=[dict(file=path.name, bytes=path.stat().st_size, sha256=sha(path.read_bytes())) for path in sorted(cached.used)],
                      changes=manifest['conversion'], selection=manifest['selection'])
    prepared['manifest.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    prepared['provenance.json'] = (json.dumps(provenance, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    args.output.mkdir(parents=True, exist_ok=True)
    for name, data in prepared.items():
        path = args.output / name
        if path.exists() and path.read_bytes() != data: raise ValueError(f'Refusing to replace different frozen fixture: {path}')
    for name, data in prepared.items(): (args.output / name).write_bytes(data)
    print(json.dumps(dict(rows=list(ROWS), recordings=len(fixtures), seconds=manifest['total_duration_seconds'], output=str(args.output)), ensure_ascii=False))


if __name__ == '__main__': main()

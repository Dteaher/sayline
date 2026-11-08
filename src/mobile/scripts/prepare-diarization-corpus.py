"""Download a fixed, small AMI headset-mix subset and published reference crops.

No predictions or model scores are consulted during selection. Recordings are
English scenario meetings with unscripted speech, not Russian conversation gold.
Only HTTP byte ranges needed for the fixed 60--180 second crop are fetched.
"""
import concurrent.futures
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import urllib.request
import wave

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/audio/diarization-20261108'
SELECTION = {'development': ['ES2002a', 'ES2003a', 'ES2004a', 'IS1000a', 'IS1001a', 'TS3005a'],
             'holdout': ['ES2005a', 'IS1003a', 'TS3006a']}
START, STOP = 60, 180


def get(url, start=None, count=None):
    headers = {'User-Agent': 'NeuroScribe-local-evaluation'}
    if start is not None: headers['Range'] = f'bytes={start}-{start + count - 1}'
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=50) as r:
        if start is not None:
            expected = f'bytes {start}-{start + count - 1}/'
            if r.status != 206 or not r.headers.get('Content-Range', '').startswith(expected):
                raise ValueError('Server did not honor the requested byte range')
        data = r.read(count + 1 if count else 10_000_000)
        if count and len(data) != count: raise ValueError('Incomplete range')
        return data


def sha(data): return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cleanrefs', action='store_true', help='New fixed validation for the clean-reference iteration')
    parser.add_argument('--cleanrefs-confirmation', action='store_true', help='Fresh final validation after first candidate failed')
    args = parser.parse_args()
    global OUT, SELECTION, START, STOP
    if args.cleanrefs:
        OUT = ROOT / 'tests/audio/cleanrefs-20261108'
        SELECTION = {'development': ['ES2002a', 'ES2003a', 'ES2004a', 'IS1000a', 'IS1001a', 'TS3005a'],
                     'holdout': ['IS1009a', 'TS3003a', 'EN2002a']}
        START, STOP = 60, 240
    if args.cleanrefs_confirmation:
        OUT = ROOT / 'tests/audio/cleanrefs-confirmation-20261108'
        SELECTION = {'development': ['ES2002a','ES2003a','ES2004a','IS1000a','IS1001a','TS3005a','IS1009a','TS3003a','EN2002a'],
                     'holdout': ['IS1008a','ES2011a','TS3004a']}
        START, STOP = 60, 240
    OUT.mkdir(parents=True, exist_ok=True)
    manifest_path = OUT / 'manifest.json'
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    else:
        commit = json.loads(get('https://api.github.com/repos/pyannote/AMI-diarization-setup/commits/main'))['sha']
        manifest = dict(selection=SELECTION, start_seconds=START, stop_seconds=STOP, reference_commit=commit,
                        purpose='Fixed before candidate tuning; holdout scored once after candidate selection',
                        reference='AMI manual orthographic annotation with forced-aligned word timings; only_words RTTM',
                        limits='English AMI meetings, not natural Russian gold; no Russian WER claims', recordings=[])
        if args.cleanrefs:
            manifest['selection_note'] = 'New final holdout IS1009a/TS3003a/EN2002a from published AMI test list, not previously scored. Existing 0.4 holdout is NOT reused as independent validation. Development speakers must be disjoint from new holdout.'
        if args.cleanrefs_confirmation:
            manifest['selection_note']='First candidate .7 native/clean failed on IS1009a; former validation3 is now development. New unseen final holdout IS1008a/ES2011a/TS3004a from published dev partition. Selected before revised-candidate inference.'
        manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    assert manifest['selection'] == SELECTION
    pinned = {row['id']: row for row in manifest['recordings']}
    base = f'https://raw.githubusercontent.com/pyannote/AMI-diarization-setup/{manifest["reference_commit"]}'
    tree = json.loads(get(f'https://api.github.com/repos/pyannote/AMI-diarization-setup/git/trees/{manifest["reference_commit"]}?recursive=1'))['tree']
    paths = {Path(x['path']).stem: x['path'] for x in tree if x['path'].startswith('only_words/rttms/') and x['path'].endswith('.rttm')}
    (OUT / 'LICENSE-reference.txt').write_bytes(get(base + '/LICENSE'))
    (OUT / 'REFERENCE_README.md').write_bytes(get(base + '/README.md'))
    reusable={}
    if args.cleanrefs_confirmation:
        prior=json.loads((ROOT/'tests/audio/cleanrefs-20261108/manifest.json').read_text(encoding='utf-8'))
        reusable={r['id']:r for r in prior['recordings']}

    def download(item):
        split, name = item
        if name in reusable:
            row=dict(reusable[name]);row['split']=split
            for path,key in [('audio','audio_sha256'),('reference_rttm','reference_sha256')]:
                if sha((ROOT/row[path]).read_bytes())!=row[key]:raise ValueError('Reused corpus digest mismatch')
            return row
        reference_url = base + '/' + paths[name]
        raw = get(reference_url)
        segments = []; speakers = set()
        for line in raw.decode().splitlines():
            f = line.split(); a = float(f[3]); b = a + float(f[4]); speakers.add(f[7])
            if b > START and a < STOP:
                segments.append((max(a, START) - START, min(b, STOP) - START, f[7]))
        url = f'https://groups.inf.ed.ac.uk/ami/AMICorpusMirror/amicorpus/{name}/audio/{name}.Mix-Headset.wav'
        target = OUT / f'{name}.wav'
        if not target.exists():
            header = get(url, 0, 4096)
            assert header[:4] == b'RIFF' and header[8:12] == b'WAVE'
            pos = 12; fmt = None
            while pos + 8 <= len(header):
                chunk, size = struct.unpack_from('<4sI', header, pos)
                if chunk == b'fmt ': fmt = struct.unpack_from('<HHIIHH', header, pos + 8)
                if chunk == b'data': offset = pos + 8; break
                pos += 8 + size + (size % 2)
            else: raise ValueError('WAV data chunk missing in header')
            assert fmt and (fmt[0], fmt[1], fmt[2], fmt[5]) == (1, 1, 16000, 16)
            pcm = get(url, offset + START * 32000, (STOP - START) * 32000)
            with wave.open(str(target), 'wb') as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(pcm)
        reference = ''.join(f'SPEAKER {name} 1 {a:.6f} {b-a:.6f} <NA> <NA> {speaker} <NA> <NA>\n' for a, b, speaker in segments)
        ref_path = OUT / f'{name}.rttm'; ref_path.write_text(reference, encoding='utf-8')
        result = dict(id=name, split=split, duration_seconds=STOP-START, audio=target.relative_to(ROOT).as_posix(),
                    reference_rttm=ref_path.relative_to(ROOT).as_posix(), source_audio_url=url,
                    audio_sha256=sha(target.read_bytes()), source_reference_url=reference_url,
                    source_reference_sha256=sha(raw), reference_sha256=sha(ref_path.read_bytes()),
                    all_meeting_speakers=sorted(speakers))
        if name in pinned and any(result[key] != pinned[name][key] for key in
                                  ('audio_sha256','source_reference_sha256','reference_sha256')):
            raise ValueError(f'Pinned corpus digest changed: {name}')
        return result

    items = [(split, name) for split, names in SELECTION.items() for name in names]
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        rows = list(pool.map(download, items))
    dev = {s for r in rows if r['split'] == 'development' for s in r['all_meeting_speakers']}
    held = {s for r in rows if r['split'] == 'holdout' for s in r['all_meeting_speakers']}
    if dev & held: raise ValueError(f'Speaker leakage in frozen selection: {dev & held}')
    manifest['recordings'] = rows
    manifest['speaker_disjoint'] = True
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    (OUT / 'PROVENANCE.md').write_text(
        f'# AMI evaluation subset\n\nFixed {START}–{STOP} s crops of {len(rows)} headset-mix recordings; '
        f'{len(SELECTION["development"])} development, {len(SELECTION["holdout"])} speaker-disjoint holdout. '
        'English AMI meetings with unscripted speech; the corpus includes scenario and non-scenario meetings. '
        'Not Russian accuracy evidence.\n\n'
        'Audio: AMI Meeting Corpus, https://groups.inf.ed.ac.uk/ami/corpus/overview.shtml ; '
        'Creative Commons Attribution, attribution to AMI project/Carletta et al. (2006). '
        'Edinburgh dataset card identifies CC-BY-4.0: https://huggingface.co/datasets/edinburghcstr/ami .\n\n'
        'Reference: pyannote/BUTSpeechFIT AMI-diarization-setup, only_words, derived from manual annotations 1.6.2. '
        'Word timing uses forced alignment; not newly hand-timed gold. Setup license/README included. '
        'Exact commit, URLs and SHA-256 in manifest.json. No reference is generated by our diarizer. '
        'Short-reference-interval metrics include word fragments separated by annotated pauses, not only conversational backchannels.\n', encoding='utf-8')
    print(json.dumps(dict(recordings=len(rows), minutes=len(rows)*(STOP-START)/60, speaker_disjoint=True)))


if __name__ == '__main__': main()

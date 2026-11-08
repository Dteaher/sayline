"""Two fixed licensed natural calls; NO human time/text reference is supplied."""
import hashlib
import json
from pathlib import Path
import urllib.request
ROOT=Path(__file__).resolve().parents[1]
def get(url):return urllib.request.urlopen(url,timeout=60).read()
def main():
    out=ROOT/'tests/audio/natural-russian-20261108';out.mkdir(exist_ok=True)
    if (out/'manifest.json').exists():print('Already frozen');return
    repo='datahiveai/multilingual-conversations-sample'
    commit=json.loads(get('https://huggingface.co/api/datasets/'+repo))['sha']
    base=f'https://huggingface.co/datasets/{repo}/resolve/{commit}/'
    metadata=get(base+'preview/metadata.jsonl');(out/'metadata-source.jsonl').write_bytes(metadata)
    card=get(base+'README.md');(out/'DATASET_CARD.md').write_bytes(card)
    rows={r['file_name']:r for l in metadata.decode().splitlines() if (r:=json.loads(l))}
    recordings=[]
    for name in ['ru_000001.mp3','ru_000002.mp3']:
        row=rows[name];assert row['language']=='ru'
        target=out/name;target.write_bytes(get(base+'preview/'+name))
        recordings.append(dict(id='natural_'+target.stem,audio=target.relative_to(ROOT).as_posix(),
            audio_sha256=hashlib.sha256(target.read_bytes()).hexdigest(),source_audio_url=base+'preview/'+name,
            duration_seconds=row['duration_seconds'],producer_metadata=row,expected_participant_tracks=2))
    manifest=dict(dataset=repo,commit=commit,license='CC-BY-4.0',attribution='DataHive AI',
        selection='First2 fixed Russian sample filenames; no inference or replacement selection',
        source='https://datahive.ai/datasets/multilingual-conversations',
        limits='Natural calls per producer. Stereo channels correspond to caller/callee. Metadata automated; no transcripts or human interval labels. NOT Russian DER/WER gold.',
        recordings=recordings)
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    (out/'PROVENANCE.md').write_text('# Natural Russian diagnostic calls\n\nDataHive AI multilingual-conversations-sample, CC BY4.0. Fixed first2 Russian filenames; exact commit/URLs/SHA256 in manifest. Publisher: unscripted calls between acquaintances, stereo caller/callee tracks. The application downmixes them through its normal decoder; no speaker-count hint is supplied. Metadata automatic, no human transcript or time labels. Do not report DER/WER from this material. Dataset card saved alongside audio.\n',encoding='utf-8')
    print([r['id'] for r in recordings])
if __name__=='__main__':main()

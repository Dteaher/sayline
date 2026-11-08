"""Freeze speaker-disjoint AMI validation before any inference on these files."""
import concurrent.futures
import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import wave
import argparse
ROOT=Path(__file__).resolve().parents[1]
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--centered',action='store_true');args=parser.parse_args()
    spec=importlib.util.spec_from_file_location('corpus',ROOT/'scripts/prepare-diarization-corpus.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
    name='native-centered-validation-20261108' if args.centered else 'native-profile-validation-20261108'
    out=ROOT/'tests/audio'/name;out.mkdir(exist_ok=True)
    evidence=ROOT/'tests/evidence'/name;evidence.mkdir(exist_ok=True)
    if (out/'manifest.json').exists():print('Already frozen');return
    prior=[]
    for name in ['diarization-20261108','cleanrefs-confirmation-20261108','fragment-validation-20261108']+(['native-profile-validation-20261108'] if args.centered else []):
        prior+=json.loads((ROOT/'tests/audio'/name/'manifest.json').read_text(encoding='utf-8'))['recordings']
    excluded={s for r in prior for s in r['all_meeting_speakers']};excluded_ids={r['id'] for r in prior}
    commit='67c2d539286e89f68952d5dcf83912bd9f01dfae'
    tree=json.loads(c.get(f'https://api.github.com/repos/pyannote/AMI-diarization-setup/git/trees/{commit}?recursive=1'))['tree']
    paths={Path(r['path']).stem:r['path'] for r in tree if r['path'].startswith('only_words/rttms/') and r['path'].endswith('.rttm')}
    base=f'https://raw.githubusercontent.com/pyannote/AMI-diarization-setup/{commit}'
    selected=[];used=set(excluded)
    families=[['ES2016a','ES2017a','ES2018a'],['IS1005a','IS1004a','IS1002a'],['TS3010a','TS3011a','TS3012a']] if args.centered else [['ES2015a','ES2016a','ES2017a'],['IS1006a','IS1005a','IS1004a'],['TS3009a','TS3010a','TS3011a']]
    for family in families:
        for name in family:
            if name in excluded_ids or name not in paths:continue
            raw=c.get(base+'/'+paths[name]);speakers={l.split()[7] for l in raw.decode().splitlines() if l.strip()}
            if speakers&used:continue
            selected.append((name,raw,speakers));used|=speakers;break
        else:raise ValueError('No speaker-disjoint meeting in predetermined family')
    policy=dict(locked_at_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),recordings=[n for n,_,_ in selected],
        native_profile_similarity=.55,margin=.1,clean_assignment_purity=.7,clean_assignment_margin=.3,
        core_seconds=5,core_references=2,fragment_references=2,overlap_veto=.2,
        recover_native_references=False,split_native_conflicts=False,retuning_after_validation=False,
        source_sha256={p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in ['app/src/main/java/ru/neuroscribe/core/NativeVoiceProfiles.kt','app/src/main/java/ru/neuroscribe/core/NativeDiarizationTrace.kt']})
    if args.centered:policy.update(center_profiles=True,centered_similarity=.35,raw_target_agreement=True,
        baseline='Current uncentered native profiles; separate exploratory follow-up, earlier validation already observed')
    (evidence/'selected-policy.json').write_text(json.dumps(policy,indent=2)+'\n',encoding='utf-8')
    def download(item):
        name,raw,speakers=item;url=f'https://groups.inf.ed.ac.uk/ami/AMICorpusMirror/amicorpus/{name}/audio/{name}.Mix-Headset.wav'
        header=c.get(url,0,4096);pos=12;fmt=None
        while pos+8<=len(header):
            chunk,size=struct.unpack_from('<4sI',header,pos)
            if chunk==b'fmt ':fmt=struct.unpack_from('<HHIIHH',header,pos+8)
            if chunk==b'data':offset=pos+8;break
            pos+=8+size+(size%2)
        else:raise ValueError('Missing data')
        assert fmt and (fmt[0],fmt[1],fmt[2],fmt[5])==(1,1,16000,16)
        target=out/(name+'.wav');pcm=c.get(url,offset+60*32000,180*32000)
        with wave.open(str(target),'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(pcm)
        lines=[]
        for line in raw.decode().splitlines():
            f=line.split();a=float(f[3]);b=a+float(f[4])
            if b>60 and a<240:lines.append(f'SPEAKER {name} 1 {max(a,60)-60:.6f} {min(b,240)-max(a,60):.6f} <NA> <NA> {f[7]} <NA> <NA>\n')
        ref=out/(name+'.rttm');ref.write_text(''.join(lines),encoding='utf-8')
        return dict(id=name,split='holdout',duration_seconds=180,audio=target.relative_to(ROOT).as_posix(),reference_rttm=ref.relative_to(ROOT).as_posix(),
            audio_sha256=c.sha(target.read_bytes()),reference_sha256=c.sha(ref.read_bytes()),source_audio_url=url,
            source_reference_url=base+'/'+paths[name],source_reference_sha256=c.sha(raw),all_meeting_speakers=sorted(speakers))
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:rows=list(pool.map(download,selected))
    for name,remote in [('LICENSE-reference.txt','LICENSE'),('REFERENCE_README.md','README.md')]: (out/name).write_bytes(c.get(base+'/'+remote))
    (out/'manifest.json').write_text(json.dumps(dict(selection=dict(holdout=[r['id'] for r in rows]),start_seconds=60,stop_seconds=240,
        reference_commit=commit,records_selected_without_inference=True,speaker_disjoint_from_all_prior_AMI=True,recordings=rows,
        limits='English AMI, manual-derived only_words/forced word timings; not Russian gold or independent of pretraining'),indent=2)+'\n',encoding='utf-8')
    (out/'PROVENANCE.md').write_text('# AMI native-profile validation\n\nThree new speaker-disjoint180s English AMI headset-mix crops. Audio attribution: AMI project/Carletta et al. (2006), CC BY4.0 as identified by Edinburgh AMI dataset card. Reference: pyannote/BUTSpeechFIT AMI setup, pinned commit in manifest, manual orthographic annotation with forced-aligned word timings. Exact URLs/hashes included. No prediction used for selection; policy frozen before inference. Not natural Russian accuracy evidence.\n',encoding='utf-8')
    print(policy['recordings'])
if __name__=='__main__':main()

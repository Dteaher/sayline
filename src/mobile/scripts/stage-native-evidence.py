"""Stage only licensed QA copies for explicit emulator evidence collection."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser();p.add_argument('--ids',nargs='+',default=['ES2002a','ES2003a','IS1009a','EN2002a','ES2014a','TS3008a']);p.add_argument('--validation',action='store_true');p.add_argument('--natural-russian',action='store_true');p.add_argument('--centered-validation',action='store_true');p.add_argument('--experiment');args=p.parse_args()
    rows={}
    for name in (['native-centered-validation-20261108'] if args.centered_validation else ['natural-russian-20261108'] if args.natural_russian else ['native-profile-validation-20261108'] if args.validation else ['cleanrefs-confirmation-20261108','fragment-validation-20261108']):
        for row in json.loads((ROOT/'tests/audio'/name/'manifest.json').read_text(encoding='utf-8'))['recordings']:rows[row['id']]=row
    if args.validation or args.natural_russian or args.centered_validation:args.ids=list(rows)
    selected=[rows[i] for i in args.ids]
    adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb.exe')
    def run(*a):return subprocess.run([adb,'-s','emulator-5554',*map(str,a)],check=True,capture_output=True)
    run('shell','run-as','ru.neuroscribe','mkdir','-p','files/qa-native-evidence')
    cases=[]
    for row in selected:
        path=ROOT/row['audio'];assert hashlib.sha256(path.read_bytes()).hexdigest()==row['audio_sha256']
        name=row['id']+path.suffix;assert name.replace('.','').replace('_','').isalnum()
        remote='/data/local/tmp/ns-trace-'+name
        run('push',path,remote)
        run('shell','run-as','ru.neuroscribe','cp',remote,'files/qa-native-evidence/'+name)
        run('shell','rm','-f',remote)
        for prefix in ['native-trace-','native-recovery-','native-profiles-','native-centered-']:
            run('shell','run-as','ru.neuroscribe','rm','-f','files/'+prefix+row['id']+'.json')
        cases.append(dict(id=row['id'],file=name,audio_sha256=row['audio_sha256']))
    out=ROOT/('tests/evidence/native-centered-validation-20261108' if args.centered_validation else 'tests/evidence/natural-russian-20261108' if args.natural_russian else 'tests/evidence/native-profile-validation-20261108' if args.validation else 'tests/evidence/native-trace-20261108');out.mkdir(exist_ok=True)
    if args.experiment:
        assert args.experiment.replace('-','').isalnum()
        out=ROOT/'tests/evidence'/(args.experiment+'-20261108');out.mkdir(exist_ok=True)
    manifest=dict(scope='Natural Russian calls without human references' if args.natural_russian else 'Fresh frozen AMI validation' if args.validation or args.centered_validation else 'Observed AMI diagnostics',cases=cases,recordings=selected)
    local=out/'selection.json';local.write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    run('push',local,'/data/local/tmp/ns-trace-manifest.json')
    run('shell','run-as','ru.neuroscribe','cp','/data/local/tmp/ns-trace-manifest.json','files/qa-native-evidence/manifest.json')
    run('shell','rm','-f','/data/local/tmp/ns-trace-manifest.json')
    print('Staged',','.join(args.ids))
if __name__=='__main__':main()

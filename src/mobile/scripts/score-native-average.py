"""Compare explicit native-linkage experiments with the proven current pipeline."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
from diarization_metrics import read_rttm,score
ROOT=Path(__file__).resolve().parents[1]
def module(name,file):
    s=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def main():
    p=argparse.ArgumentParser();p.add_argument('--experiment',default='native-average025');p.add_argument('--pull',action='store_true');args=p.parse_args()
    assert args.experiment.replace('-','').isalnum()
    out=ROOT/'tests/evidence'/(args.experiment+'-20261108');selection=json.loads((out/'selection.json').read_text(encoding='utf-8'))
    if args.pull:
        adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb.exe')
        for row in selection['recordings']:
            for prefix in ['native-trace-','native-profiles-']:
                name=prefix+row['id']+'.json';r=subprocess.run([adb,'-s','emulator-5554','exec-out','run-as','ru.neuroscribe','cat','files/'+name],check=True,capture_output=True)
                json.loads(r.stdout);(out/name).write_bytes(r.stdout)
    t=module('traces','score-native-traces.py');group=module('ablation','score-native-ablation.py').group_turns
    aggregate=module('aggregation','score-diarization-experiment.py').aggregate;results=[]
    for row in selection['recordings']:
        name=row['id'];ref=read_rttm(ROOT/row['reference_rttm'])[name]
        old=json.loads((ROOT/'tests/evidence/native-trace-20261108'/('native-trace-'+name+'.json')).read_text(encoding='utf-8'))
        mapping,_=t.proposals(old,.55,.1);baseline=group([(a,b,mapping.get(int(g),int(g))) for a,b,g in old['refined']])
        new=json.loads((out/('native-trace-'+name+'.json')).read_text(encoding='utf-8'))
        profiled=json.loads((out/('native-profiles-'+name+'.json')).read_text(encoding='utf-8'))
        for variant,turns in [('current',baseline),('average_native_only',group(new['raw'])),('average_with_eres',new['turns']),('average_with_profiles',profiled['turns'])]:
            results.append(dict(id=name,variant=variant,**score(ref,turns,180)))
    report=dict(scope='3 observed diagnostic English AMI cases; primary linkage experiment, not validation',results=results,
        summaries={v:aggregate([r for r in results if r['variant']==v]) for v in ['current','average_native_only','average_with_eres','average_with_profiles']})
    (out/'results.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print({v:{k:s[k] for k in ['der','jer','count_mae']} for v,s in report['summaries'].items()})
    print([(r['id'],r['variant'],round(r['der']*100,2),r['hypothesis_speakers']) for r in results])
if __name__=='__main__':main()

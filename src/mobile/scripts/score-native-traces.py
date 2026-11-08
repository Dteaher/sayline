"""Score actual Android traces; all tuning data explicitly diagnostic."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import struct
import hashlib
import numpy as np
from diarization_metrics import read_rttm, score
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/evidence/native-trace-20261108'
def module(name,file):
    s=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def union(spans):
    end=-1.;total=0.
    for a,b in sorted(spans):total+=max(0.,b-max(a,end));end=max(end,b)
    return total
def intersection(a,b):return union([(max(x,u),min(y,v)) for x,y in a for u,v in b if min(y,v)>max(x,u)])
def profiles(trace):
    groups={}
    for a,b,g in trace['refined']:groups.setdefault(int(g),[]).append((a,b))
    refs={g:[] for g in groups}
    for r in trace['references']:
        compatible={}
        for original,final in zip(trace['raw'],trace['refined']):
            if int(original[2])==r['speaker']:compatible.setdefault(int(final[2]),[]).append((original[0],original[1]))
        ranked=sorted([(intersection(r['clean'],spans),g) for g,spans in compatible.items()],reverse=True)
        mass=union(r['clean'])
        if not ranked or mass<=0 or ranked[0][0]/mass<.7:continue
        if len(ranked)>1 and (ranked[0][0]-ranked[1][0])/mass<.3:continue
        v=np.array(r['embedding'],dtype=np.float64);v/=np.linalg.norm(v)
        refs[ranked[0][1]].append(dict(vector=v,window=r['window'],clean=r['clean'],mass=ranked[0][0],active=r['active'],local=r['local']))
    return groups,refs
def proposals(trace,threshold,margin):
    groups,refs=profiles(trace)
    mass={g:union(spans) for g,spans in groups.items()}
    cores=[g for g in groups if mass[g]>=5 and len(refs[g])>=2]
    if len(cores)<2:return {},[]
    result={};details=[]
    for source in groups:
        if source in cores or len(refs[source])<2:continue
        ranked=[]
        for target in cores:
            values=[];weights=[]
            for r in refs[source]:
                best=sorted([float(r['vector']@q['vector']) for q in refs[target]],reverse=True)[:2]
                values.append(sum(best)/len(best));weights.append(min(2.,r['mass']))
            ranked.append((float(np.average(values,weights=weights)),target))
        ranked.sort(reverse=True);s,g=ranked[0];gap=s-ranked[1][0]
        local_overlap=union([(max(a,u),min(b,v)) for r in refs[source] for q in refs[g]
            if r['window']==q['window'] and r['local']!=q['local']
            for a,b in r['active'] for u,v in q['active'] if min(b,v)>max(a,u)])
        if s>=threshold and gap>=margin and intersection(groups[source],groups[g])<=.2 and local_overlap<=.2:
            result[source]=g;details.append(dict(source=source,target=g,similarity=s,margin=gap,references=len(refs[source])))
    reject=set()
    for left in result:
        for right in result:
            if left<right and result[left]==result[right] and intersection(groups[left],groups[right])>.2:reject.update([left,right])
    return {s:g for s,g in result.items() if s not in reject},[d for d in details if d['source'] not in reject]
def split_conflicts(trace,minimum=1.):
    spans={}
    by_window={}
    for r in trace['references']:by_window.setdefault(r['window'],[]).append(r)
    for values in by_window.values():
        for i,left in enumerate(values):
            for right in values[:i]:
                if left['speaker']==right['speaker'] or left['local']==right['local']:continue
                pair=tuple(sorted([left['speaker'],right['speaker']]))
                spans.setdefault(pair,[]).extend((max(a,u),min(b,v)) for a,b in left['active'] for u,v in right['active'] if min(b,v)>max(a,u))
    conflicts={pair for pair,ss in spans.items() if union(ss)>=minimum}
    colors={};canonical={};result=[]
    for raw,refined in zip(trace['raw'],trace['refined']):
        native=int(raw[2]);group=int(refined[2]);key=(group,native)
        if key not in colors:
            forbidden={color for (g,n),color in colors.items() if g==group and tuple(sorted([n,native])) in conflicts}
            color=0
            while color in forbidden:color+=1
            colors[key]=color
        newkey=(group,colors[key]);canonical.setdefault(newkey,len(canonical))
        result.append((refined[0],refined[1],canonical[newkey]))
    return result
def main():
    p=argparse.ArgumentParser();p.add_argument('--pull',action='store_true');p.add_argument('--prepare-kotlin',action='store_true');p.add_argument('--validation',action='store_true');p.add_argument('--centered-validation',action='store_true');args=p.parse_args()
    global OUT
    if args.validation:OUT=ROOT/'tests/evidence/native-profile-validation-20261108'
    if args.centered_validation:OUT=ROOT/'tests/evidence/native-centered-validation-20261108'
    selection=json.loads((OUT/'selection.json').read_text(encoding='utf-8'))
    if args.pull:
        adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb.exe')
        for row in selection['cases']:
            for prefix in (['native-trace-','native-profiles-','native-centered-'] if args.centered_validation else ['native-trace-','native-profiles-'] if args.validation else ['native-trace-','native-recovery-']):
                name=prefix+row['id']+'.json';r=subprocess.run([adb,'-s','emulator-5554','exec-out','run-as','ru.neuroscribe','cat','files/'+name],capture_output=True)
                if r.returncode==0:
                    try:json.loads(r.stdout)
                    except ValueError:continue
                    (OUT/name).write_bytes(r.stdout)
    aggregate=module('aggregation','score-diarization-experiment.py').aggregate
    group=module('ablation','score-native-ablation.py').group_turns
    if args.validation or args.centered_validation:
        policy=json.loads((OUT/'selected-policy.json').read_text(encoding='utf-8'))
        for p,h in policy['source_sha256'].items():
            current=ROOT/p
            if hashlib.sha256(current.read_bytes()).hexdigest()!=h:current=OUT/'source'/Path(p).name
            assert hashlib.sha256(current.read_bytes()).hexdigest()==h
        results=[]
        assert sorted(r['id'] for r in selection['recordings'])==sorted(policy['recordings'])
        for row in selection['recordings']:
            assert hashlib.sha256((ROOT/row['reference_rttm']).read_bytes()).hexdigest()==row['reference_sha256']
            ref=read_rttm(ROOT/row['reference_rttm'])[row['id']]
            variants=[('native-profiles-','current'),('native-centered-','candidate')] if args.centered_validation else [('native-trace-','current'),('native-profiles-','candidate')]
            for prefix,variant in variants:
                trace=json.loads((OUT/(prefix+row['id']+'.json')).read_text(encoding='utf-8'))
                assert trace['id']==row['id'] and trace['duration']==180
                results.append(dict(id=row['id'],variant=variant,**score(ref,trace['turns'],180)))
        report=dict(scope='3 newly frozen English AMI crops; actual Android inference; no retuning',results=results,
            summaries={v:aggregate([r for r in results if r['variant']==v]) for v in ['current','candidate']})
        (OUT/'results.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print({v:{k:s[k] for k in ['der','jer','count_mae','confusion_seconds']} for v,s in report['summaries'].items()})
        return
    results=[];cases=[]
    for row in selection['recordings']:
        path=OUT/('native-trace-'+row['id']+'.json')
        if not path.exists():continue
        trace=json.loads(path.read_text(encoding='utf-8'));ref=read_rttm(ROOT/row['reference_rttm'])[row['id']]
        cases.append((row,trace,ref))
        base=score(ref,trace['turns'],180)
        results.append(dict(id=row['id'],variant='current',native_references=len(trace['references']),**base))
        for threshold in [.5,1.]:
            results.append(dict(id=row['id'],variant=f'split{threshold}',**score(ref,group(split_conflicts(trace,threshold)),180)))
        recovery=OUT/('native-recovery-'+row['id']+'.json')
        if recovery.exists():
            candidate=json.loads(recovery.read_text(encoding='utf-8'))
            results.append(dict(id=row['id'],variant='recovery',**score(ref,candidate['turns'],180)))
    grid=[]
    for threshold in [.55,.65,.75]:
        for margin in [.05,.1,.15]:
            scores=[];details=[]
            for row,trace,ref in cases:
                mapping,merges=proposals(trace,threshold,margin)
                actual=score(ref,group([(a,b,mapping.get(int(g),int(g))) for a,b,g in trace['refined']]),180)
                baseline=score(ref,trace['turns'],180)
                scores.append(dict(id=row['id'],der_delta=actual['der']-baseline['der'],**actual))
                details.append(dict(id=row['id'],merges=merges))
            if scores:grid.append(dict(threshold=threshold,margin=margin,summary=aggregate(scores),
                regressed=sum(r['der_delta']>1e-6 for r in scores),cases=details,results=scores))
    summaries={v:aggregate([r for r in results if r['variant']==v]) for v in ['current','recovery','split0.5','split1.0'] if any(r['variant']==v for r in results)}
    report=dict(scope='Actual Android x86 native traces; observed diagnostic data, no new validation',complete=len(cases)==len(selection['cases']),results=results,summaries=summaries,grid=grid)
    (OUT/'diagnostic.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print('complete',report['complete'],'files',len(cases))
    print({v:{k:s[k] for k in ['der','jer','count_mae']} for v,s in summaries.items()})
    for g in sorted(grid,key=lambda g:g['summary']['der'])[:4]:print(g['threshold'],g['margin'],g['summary']['der'],g['regressed'],sum(len(c['merges']) for c in g['cases']))
    if args.prepare_kotlin:
        cache=ROOT/'.work/native-profiles-eval';cache.mkdir(exist_ok=True)
        for row,trace,_ in cases:
            key=row['id'];refs=trace['references'];values=[1,16000,len(refs[0]['embedding']) if refs else 0,len(refs),len(trace['raw'])]
            for a,b,g in trace['raw']:values.extend([a,b,g,-2])
            for r in refs:
                values.extend([r['window'],r['local'],r['speaker'],len(r['clean']),len(r['active'])]);values.extend(r['embedding'])
                for a,b in r['clean']+r['active']:values.extend([a,b])
            (cache/f'{key}.trace.f32').write_bytes(struct.pack('<'+'f'*len(values),*values))
            (cache/f'{key}.refined.tsv').write_text(''.join(f'{a}\t{b}\t{int(g)}\n' for a,b,g in trace['refined']),encoding='utf-8')
            mapping,_=proposals(trace,.55,.1)
            (cache/f'{key}.expected-proposals.tsv').write_text(''.join(f'{s}\t{g}\n' for s,g in mapping.items()),encoding='utf-8')
            centered,_=module('centering','probe-native-profile-centering.py').propose(trace,True,False,.35,.1)
            (cache/f'{key}.expected-centered-proposals.tsv').write_text(''.join(f'{s}\t{g}\n' for s,g in centered.items()),encoding='utf-8')
        (cache/'keys.txt').write_text(''.join(row['id']+'\n' for row,_,_ in cases),encoding='utf-8')
if __name__=='__main__':main()

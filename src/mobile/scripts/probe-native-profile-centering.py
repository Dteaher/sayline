"""Diagnostic ablation of recording centering and limited core reconciliation."""
import importlib.util
import json
from pathlib import Path
import numpy as np
from diarization_metrics import read_rttm,score
ROOT=Path(__file__).resolve().parents[1]
def load(name,file):
    s=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def propose(trace,center,core_merge,threshold,margin):
    m=load('traces','score-native-traces.py');groups,refs=m.profiles(trace)
    mass={g:m.union(spans) for g,spans in groups.items()};cores=[g for g in groups if mass[g]>=5 and len(refs[g])>=2]
    if len(cores)<2:return {},[]
    if center:
        for rr in refs.values():
            for r in rr:r['raw']=r['vector'].copy()
        centroids=[]
        for g in cores:
            v=np.average([r['vector'] for r in refs[g]],axis=0,weights=[min(2,r['mass']) for r in refs[g]])
            centroids.append(v/max(np.linalg.norm(v),1e-9))
        mean=np.mean(centroids,axis=0)
        for rr in refs.values():
            for r in rr:
                v=r['vector']-mean;r['vector']=v/max(np.linalg.norm(v),1e-9)
    mapping={};details=[]
    for source in groups:
        if len(refs[source])<2 or (source in cores and not core_merge):continue
        targets=[g for g in cores if g!=source and (source not in cores or mass[g]>=2*mass[source])]
        # Still compare the best eligible candidate with every other established
        # voice; target eligibility must not artificially increase the margin.
        ranked=[]
        for target in cores:
            if target==source:continue
            vals=[];weights=[]
            for r in refs[source]:
                vals.append(np.mean(sorted([float(r['vector']@q['vector']) for q in refs[target]],reverse=True)[:2]));weights.append(min(2,r['mass']))
            ranked.append((float(np.average(vals,weights=weights)),target))
        ranked.sort(reverse=True)
        if len(ranked)<2:continue
        sim,target=ranked[0];gap=sim-ranked[1][0]
        local=m.union([(max(a,u),min(b,v)) for r in refs[source] for q in refs[target]
                      if r['window']==q['window'] and r['local']!=q['local'] for a,b in r['active'] for u,v in q['active'] if min(b,v)>max(a,u)])
        agree=True
        if center:
            raw=[]
            for h in cores:
                if h==source:continue
                vv=[];ww=[]
                for r in refs[source]:
                    vv.append(np.mean(sorted([float(r['raw']@q['raw']) for q in refs[h]],reverse=True)[:2]));ww.append(min(2,r['mass']))
                raw.append((float(np.average(vv,weights=ww)),h))
            raw.sort(reverse=True);agree=raw[0][1]==target and raw[0][0]>=.55
        if agree and target in targets and sim>=threshold and gap>=margin and m.intersection(groups[source],groups[target])<=.2 and local<=.2:
            mapping[source]=target;details.append(dict(source=source,target=target,similarity=sim,margin=gap))
    rejected={s for s,t in mapping.items() if t in mapping}
    for a in mapping:
        for b in mapping:
            if a<b and mapping[a]==mapping[b] and m.intersection(groups[a],groups[b])>.2:rejected.update([a,b])
    return {s:t for s,t in mapping.items() if s not in rejected},[d for d in details if d['source'] not in rejected]
def main():
    out=ROOT/'tests/evidence/native-trace-20261108';sel=json.loads((out/'selection.json').read_text(encoding='utf-8'))
    group=load('ablation','score-native-ablation.py').group_turns;aggregate=load('aggregation','score-diarization-experiment.py').aggregate
    cases=[]
    for row in sel['recordings']:
        trace=json.loads((out/('native-trace-'+row['id']+'.json')).read_text(encoding='utf-8'));ref=read_rttm(ROOT/row['reference_rttm'])[row['id']];cases.append((row,trace,ref))
    reports=[]
    for center in [False,True]:
        for core in [False,True]:
            for threshold in ([.35,.55,.75] if center else [.55,.75]):
                results=[];merges=[]
                for row,trace,ref in cases:
                    mapping,details=propose(trace,center,core,threshold,.1)
                    actual=score(ref,group([(a,b,mapping.get(int(g),int(g))) for a,b,g in trace['refined']]),180)
                    base=score(ref,trace['turns'],180)
                    results.append(dict(id=row['id'],der_delta=actual['der']-base['der'],**actual));merges.append(dict(id=row['id'],proposals=details))
                reports.append(dict(center=center,core_merge=core,threshold=threshold,margin=.1,
                    summary=aggregate(results),regressions=sum(r['der_delta']>1e-6 for r in results),results=results,merges=merges))
    reports.sort(key=lambda r:r['summary']['der'])
    (out/'centering-diagnostic.json').write_text(json.dumps(dict(scope='6 already observed AMI diagnostic cases; no validation claim',reports=reports),indent=2)+'\n',encoding='utf-8')
    for r in reports:print(r['center'],r['core_merge'],r['threshold'],r['summary']['der'],r['summary']['jer'],r['regressions'],sum(len(m['proposals']) for m in r['merges']))
if __name__=='__main__':main()

"""Diagnostic conservative fragment merge grid on observed cached ERes vectors.

No production change. All 12 meetings already observed; no validation claim.
"""
import importlib.util
import json
from pathlib import Path
import numpy as np
from diarization_metrics import read_rttm, score

ROOT=Path(__file__).resolve().parents[1]
CACHE=ROOT/'.work/cleanrefs-confirmation-eval'
OUT=ROOT/'tests/evidence/native-ablation-20261108'


def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result)
    return result


def union_duration(spans):
    result=0.;end=-1.
    for a,b in sorted(spans):
        result+=max(0.,b-max(a,end));end=max(end,b)
    return result


def proposed_merges(turns,vectors,durations,threshold,margin,min_refs,core_seconds):
    groups={}
    for i,t in enumerate(turns):groups.setdefault(t[2],[]).append(i)
    mass={g:union_duration([(turns[i][0],turns[i][1]) for i in indices]) for g,indices in groups.items()}
    evidence={g:[i for i in indices if i in vectors] for g,indices in groups.items()}
    cores=[g for g in groups if mass[g]>=core_seconds and len(evidence[g])>=2]
    if len(cores)<2:return {},[]
    proposals={};details=[]
    for source in groups:
        if source in cores or len(evidence[source])<min_refs:continue
        ranked=[];conflicts=set()
        for target in cores:
            # A repeated/certain overlap should prevent merges. Here any >0.2s
            # union is conservatively excluded, and effect is measured explicitly.
            intersections=[(max(turns[i][0],turns[j][0]),min(turns[i][1],turns[j][1]))
                           for i in groups[source] for j in groups[target]
                           if min(turns[i][1],turns[j][1])>max(turns[i][0],turns[j][0])]
            if union_duration(intersections)>.2:conflicts.add(target)
            sims=[];weights=[]
            for i in evidence[source]:
                matches=sorted((float(vectors[i]@vectors[j]) for j in evidence[target]),reverse=True)
                sims.append(sum(matches[:2])/min(2,len(matches)))
                weights.append(min(2.,durations[i]))
            ranked.append((float(np.average(sims,weights=weights)),target))
        ranked.sort(reverse=True)
        if len(ranked)<2:continue
        best,target=ranked[0]
        if target not in conflicts and best>=threshold and best-ranked[1][0]>=margin:
            proposals[source]=target
            details.append(dict(source=source,target=target,similarity=best,margin=best-ranked[1][0],
                                source_seconds=mass[source],references=len(evidence[source])))
    # Independently plausible fragments must not collapse a simultaneous pair.
    reject=set()
    sources=list(proposals)
    for n,left in enumerate(sources):
        for right in sources[:n]:
            if proposals[left]!=proposals[right]:continue
            intersections=[(max(turns[i][0],turns[j][0]),min(turns[i][1],turns[j][1]))
                           for i in groups[left] for j in groups[right]
                           if min(turns[i][1],turns[j][1])>max(turns[i][0],turns[j][0])]
            if union_duration(intersections)>.2:reject.update((left,right))
    return {s:t for s,t in proposals.items() if s not in reject},[d for d in details if d['source'] not in reject]


def main():
    ab=module('ablation',ROOT/'scripts/score-native-ablation.py')
    ag=module('aggregation',ROOT/'scripts/score-diarization-experiment.py')
    manifest=json.loads((ROOT/'tests/audio/cleanrefs-confirmation-20261108/manifest.json').read_text(encoding='utf-8'))
    cases=[]
    for row in manifest['recordings']:
        key=row['id']+'-native050'
        turns=ab.read_turns(CACHE/(key+'.anchored.refined.tsv'))
        vectors={};durations={}
        for line in (CACHE/(key+'.anchored.vectors.tsv')).read_text().splitlines():
            f=line.split();i=int(f[0]);durations[i]=float(f[1]);v=np.array([float(x) for x in f[2:]])
            if len(v) and np.isfinite(v).all() and np.linalg.norm(v)>1e-9:vectors[i]=v/np.linalg.norm(v)
        ref=read_rttm(ROOT/row['reference_rttm'])[row['id']]
        cases.append((row,turns,vectors,durations,ref))
    reports=[]
    policies=[(t,m,n,c) for t in (.35,.45,.55,.65,.75,.85) for m in (.05,.1) for n in (1,2) for c in (5.,10.)]
    for threshold,margin,min_refs,core_seconds in policies:
        results=[]
        for row,turns,vectors,durations,ref in cases:
            mapping,details=proposed_merges(turns,vectors,durations,threshold,margin,min_refs,core_seconds)
            candidate=ab.group_turns([(a,b,mapping.get(g,g)) for a,b,g in turns])
            baseline=score(ref,ab.group_turns(turns),180)
            actual=score(ref,candidate,180)
            results.append(dict(id=row['id'],prior_split=row['split'],merges=details,
                                der_delta=actual['der']-baseline['der'],jer_delta=actual['jer']-baseline['jer'],**actual))
        reports.append(dict(policy=dict(threshold=threshold,margin=margin,min_refs=min_refs,core_seconds=core_seconds),
                            summary=ag.aggregate(results),regressed_files=sum(r['der_delta']>1e-6 for r in results),
                            merged_groups=sum(len(r['merges']) for r in results),results=results))
    reports.sort(key=lambda r:r['summary']['der'])
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'fragment-grid.json').write_text(json.dumps(dict(scope='Diagnostic 12 previously observed English AMI meetings, not independent validation',
         method='Frozen original core profiles; top2 prototype similarity, margin, overlap exclusion; no forced short merges',
         reports=reports),indent=2)+'\n',encoding='utf-8')
    for r in reports[:12]:print(dict(**r['policy'],der=r['summary']['der'],jer=r['summary']['jer'],count_mae=r['summary']['count_mae'],regressed=r['regressed_files'],merges=r['merged_groups']))


if __name__=='__main__':main()


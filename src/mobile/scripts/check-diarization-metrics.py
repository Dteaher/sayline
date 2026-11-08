"""Cross-check our exact DER against pinned pyannote.metrics, including overlap."""
import json
from pathlib import Path
import random
from pyannote.core import Annotation, Segment, Timeline
from pyannote.metrics.diarization import DiarizationErrorRate
from scipy.optimize import linear_sum_assignment
import numpy as np
from diarization_metrics import score


def annotation(turns):
    value=Annotation()
    for i,(a,b,speaker) in enumerate(turns): value[Segment(a,b),str(i)]=str(speaker)
    return value


def main():
    randomizer=random.Random(20261108)
    max_error=0.;cases=0;jer_cases=0;jer_max_error=0.
    for _ in range(70):
        ref=[];hyp=[]
        for target in (ref,hyp):
            for _ in range(8):
                a=randomizer.randrange(0,90)/10; b=min(10.,a+randomizer.randrange(1,25)/10)
                target.append((a,b,str(randomizer.randrange(3))))
        # Merge same-speaker overlap: official Annotation permits duplicate tracks.
        from diarization_metrics import normalized
        ref,hyp=normalized(ref,10),normalized(hyp,10)
        for collar in (0.,.25):
            for skip in (False,True):
                expected=DiarizationErrorRate(collar=collar,skip_overlap=skip)(annotation(ref),annotation(hyp),uem=Timeline([Segment(0,10)]))
                actual=score(ref,hyp,10,collar,skip)['der']
                if actual is None: continue
                error=abs(expected-actual); max_error=max(max_error,error); cases+=1
                if error>1e-8: raise AssertionError((expected,actual,collar,skip))
        # dscore uses minimum Jaccard-cost assignment. pyannote.metrics 3.2.1
        # instead maps by maximum intersection, so its JER may legitimately differ.
        ra,ha=annotation(ref),annotation(hyp)
        matrix=np.zeros((len(ra.labels()),len(ha.labels())))
        for i,r in enumerate(ra.labels()):
            for j,h in enumerate(ha.labels()):
                rt,ht=ra.label_timeline(r),ha.label_timeline(h)
                intersection=rt.crop(ht).support().duration()
                union=rt.union(ht).support().duration()
                matrix[i,j]=1-intersection/union
        rows,cols=linear_sum_assignment(matrix)
        errors=np.ones(len(ra.labels()))
        errors[rows]=matrix[rows,cols]
        expected_jer=float(errors.mean())
        actual_jer=score(ref,hyp,10)['jer']
        error=abs(expected_jer-actual_jer);jer_cases+=1;jer_max_error=max(jer_max_error,error)
        if error>1e-8:raise AssertionError(('JER',expected_jer,actual_jer))
    report=dict(cases=cases,maximum_absolute_difference=max_error,reference='pyannote.metrics==3.2.1',
                jer_cases=jer_cases,jer_maximum_absolute_difference=jer_max_error,
                jer_reference='dscore Jaccard-cost mapping, independently computed with pyannote timelines and scipy.optimize.linear_sum_assignment',
                scope='DER random multi-speaker intervals, collars 0/.25, overlap included/excluded; JER collar0 overlap included')
    path=Path(__file__).resolve().parents[1]/'tests/evidence/diarization-20261108/metrics-crosscheck.json'
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(report)


if __name__=='__main__':main()

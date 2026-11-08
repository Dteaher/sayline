"""Score actual Kotlin A/B outputs. Holdout is selected explicitly, never tuned here."""
import argparse
import json
from pathlib import Path
import random
from diarization_metrics import read_rttm, score
from diarization_protocol import variants, require_holdout_inputs

ROOT = Path(__file__).resolve().parents[1]


def aggregate(rows):
    denominator = sum(r['reference_speaker_seconds'] for r in rows)
    errors = sum(r['miss_seconds']+r['false_alarm_seconds']+r['confusion_seconds'] for r in rows)
    speakers = sum(r['reference_speakers'] for r in rows)
    output = dict(files=len(rows), der=errors/denominator if denominator else None,
                  jer=sum(r['jer']*r['reference_speakers'] for r in rows if r['jer'] is not None)/speakers if speakers else None,
                  count_mae=sum(r['count_absolute_error'] for r in rows)/len(rows),
                  count_signed_mean=sum(r['count_signed_error'] for r in rows)/len(rows),
                  exact_speaker_count_files=sum(r['count_absolute_error']==0 for r in rows),
                  reference_speaker_seconds=denominator)
    for key in ('miss_seconds','false_alarm_seconds','confusion_seconds','overlap_miss_seconds','overlap_reference_speaker_seconds'):
        output[key] = sum(r[key] for r in rows)
    for limit in (.5,1.):
        key=f'short_{limit}'
        output[key]={k:sum(r[key][k] for r in rows) for k in ('total','detected','correct_speaker')}
    return output


def paired_ci(baseline, candidate):
    if len(baseline) < 2: return None
    rng=random.Random(20261108); differences=[]
    for _ in range(2000):
        indices=[rng.randrange(len(baseline)) for _ in baseline]
        differences.append(aggregate([candidate[i] for i in indices])['der']-aggregate([baseline[i] for i in indices])['der'])
    differences.sort()
    return dict(low=differences[49], high=differences[1949], samples=2000, unit='fraction, candidate minus baseline',
                caveat='Few independent recordings; bootstrap interval is descriptive, not a broad accuracy guarantee')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--split',choices=['development','holdout'],default='development')
    parser.add_argument('--cache',type=Path,default=ROOT/'.work/diarization-eval')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    manifest=json.loads((ROOT/'tests/audio/diarization-20261108/manifest.json').read_text(encoding='utf-8'))
    recordings={r['id']:r for r in manifest['recordings'] if r['split']==args.split}
    results=[]
    for row in recordings.values():
        reference=read_rttm(ROOT/row['reference_rttm'])[row['id']]
        if args.split=='holdout':
            require_holdout_inputs(args.cache,row['id'])
        for path in sorted(args.cache.glob(f'{row["id"]}-on*.json')):
            metadata=json.loads(path.read_text(encoding='utf-8'));key=metadata['key']
            if key!=path.stem or metadata['id']!=row['id'] or metadata['split']!=args.split:
                raise ValueError('Cached case identity mismatch')
            allowed=variants(args.split,metadata['min_duration_on'])
            if args.split=='holdout':
                for file in args.cache.glob(f'{key}.*.grouped.tsv'):
                    if file.name.removeprefix(key+'.').split('.')[0] not in allowed: raise ValueError('Exploratory holdout output present')
            inputs=(args.cache/f'{key}.input.tsv').read_text().splitlines()
            invalid=sum(any(not __import__('math').isfinite(float(x)) for x in line.split('\t')[4:]) for line in inputs)
            candidates={'native':[(a,b,k) for a,b,k,_ in metadata['native_turns']]}
            for variant in allowed:
                for stage in ('refined','grouped'):
                    file=args.cache/f'{key}.{variant}.{stage}.tsv'
                    if file.exists(): candidates[f'{variant}_{stage}']=[(float(f[0]),float(f[1]),f[2]) for line in file.read_text().splitlines() if (f:=line.split())]
            for variant, turns in candidates.items():
                for collar in (0.,.25):
                    result=score(reference,turns,row['duration_seconds'],collar=collar)
                    results.append(dict(id=row['id'],on=metadata['min_duration_on'],variant=variant,collar=collar,
                                        invalid_neural_vectors=invalid,**result))
    groups={}
    for r in results: groups.setdefault((r['on'],r['variant'],r['collar']),[]).append(r)
    summaries=[]
    baseline=sorted(groups.get((.3,'baseline_grouped',0.),[]),key=lambda r:r['id'])
    if args.split=='holdout':
        for key in ((.3,'baseline_grouped',0.),(.15,'selected_grouped',0.)):
            if sorted(r['id'] for r in groups.get(key,[]))!=sorted(recordings): raise ValueError('Incomplete paired holdout report')
    for (on,variant,collar),rows in groups.items():
        rows=sorted(rows,key=lambda r:r['id'])
        item=dict(on=on,variant=variant,collar=collar,**aggregate(rows))
        if not collar and len(rows)==len(baseline) and [r['id'] for r in rows]==[r['id'] for r in baseline]:
            item['paired_der_delta_ci95']=paired_ci(baseline,rows)
        summaries.append(item)
    report=dict(split=args.split,corpus='AMI fixed 60–180s English headset mix, manual-derived only_words RTTM',
                limits=manifest['limits'],hidden_funnel_stages='NOT EXPOSED by the public native process API',
                rate_units='fractions; primary DER collar=0, overlap included; JER speaker-weighted, DER pooled',
                short_intervals='Annotated contiguous speech spans, not a manual taxonomy of conversational short replies',
                summaries=summaries,results=results)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n',encoding='utf-8')
    for item in sorted((r for r in summaries if not r['collar'] and r['variant'].endswith('grouped')),key=lambda r:r['der']):
        print({k:item[k] for k in ('on','variant','files','der','jer','count_mae','short_0.5')})


if __name__=='__main__':main()

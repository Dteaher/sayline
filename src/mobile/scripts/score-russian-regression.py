"""Score frozen Android outputs against the unmodified published FLEURS text.
WER ignores speaker IDs here; this read-speech corpus is NOT diarization gold.
"""
import argparse
import json
from pathlib import Path
from diarization_metrics import cpwer


def score_report(report,corpus):
    expected={row['recording_id']:row for row in corpus['fixtures']}
    rows=report['cases']
    if len(expected)!=len(corpus['fixtures']) or sorted(row['id'] for row in rows)!=sorted(expected):
        raise ValueError('Incomplete or duplicated Russian recording IDs')
    totals={name:dict(errors=0,reference_words=0,elapsed_ms=0,audio_seconds=0.) for name in ('baseline','candidate')}
    results=[]
    for row in rows:
        ref=expected[row['id']]['reference']
        if row.get('reference')!=ref:raise ValueError('Published reference was changed')
        output=dict(id=row['id'],reference=ref)
        for variant in totals:
            replicas=row[variant]['transcript']
            text=' '.join(r['text'] for r in replicas)
            result=cpwer({'reference':ref},{'hypothesis':text})
            output[variant]=dict(text=text,wer=result['cpwer'],errors=result['errors'],reference_words=result['reference_words'],
                                 reported_speakers=len({r['speaker_id'] for r in replicas}))
            total=totals[variant];total['errors']+=result['errors'];total['reference_words']+=result['reference_words']
            total['elapsed_ms']+=row[f'{variant}_elapsed_ms'];total['audio_seconds']+=row['audio_seconds']
        results.append(output)
    for total in totals.values():
        total['wer']=total['errors']/total['reference_words'] if total['reference_words'] else None
        total['rtf']=total['elapsed_ms']/1000/total['audio_seconds'] if total['audio_seconds'] else None
    return dict(scope='12 fixed new FLEURS recorded clips, read Russian, not spontaneous conversations or speaker gold',
                independence_note=corpus.get('independence_note'),normalization='lowercase Unicode word tokens, ё→е; punctuation ignored, no numeral aliases',
                timing_scope='One sequential baseline-then-candidate run per clip; PCM already decoded; includes model load/diarization/refinement/ASR, not UI/import; emulator host load varies',
                totals=totals,results=results)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--report',type=Path,required=True);p.add_argument('--corpus',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    result=score_report(json.loads(a.report.read_text(encoding='utf-8')),json.loads(a.corpus.read_text(encoding='utf-8')))
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result['totals'],indent=2))

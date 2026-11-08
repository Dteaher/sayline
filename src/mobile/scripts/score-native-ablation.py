"""Direct stage ablation on already observed AMI recordings; NOT new holdout."""
import hashlib
import importlib.util
import json
from pathlib import Path

from diarization_metrics import read_rttm, score

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.work/cleanrefs-confirmation-eval'
OUT = ROOT / 'tests/evidence/native-ablation-20261108'


def read_turns(path):
    return [(float(f[0]), float(f[1]), f[2]) for line in path.read_text(encoding='utf-8').splitlines()
            if (f := line.split())]


def group_turns(turns, gap=1.2):
    """Same sorted adjacent-label grouping as production; no relabeling."""
    result = []
    for a, b, label in sorted(turns, key=lambda t: t[0]):
        if result and result[-1][2] == label and a-result[-1][1] <= gap:
            result[-1] = (result[-1][0], max(result[-1][1], b), label)
        else:
            result.append((a, b, label))
    return result


def main():
    spec = importlib.util.spec_from_file_location('aggregate', ROOT/'scripts/score-diarization-experiment.py')
    aggregation = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(aggregation)
    manifest = json.loads((ROOT/'tests/audio/cleanrefs-confirmation-20261108/manifest.json').read_text(encoding='utf-8'))
    assert len(manifest['recordings']) == 12
    results = []
    for row in manifest['recordings']:
        refpath = ROOT/row['reference_rttm']
        assert hashlib.sha256(refpath.read_bytes()).hexdigest() == row['reference_sha256']
        key = row['id']+'-native050'
        metadata = json.loads((CACHE/(key+'.json')).read_text(encoding='utf-8'))
        assert metadata['id'] == row['id'] and metadata['threshold'] == .5
        assert metadata['audio_sha256'] == row['audio_sha256']
        native = read_turns(CACHE/(key+'.turns.tsv'))
        variants = {'native_raw': native, 'native_grouped': group_turns(native),
                    'eres_grouped': read_turns(CACHE/(key+'.baseline.gap120.output.tsv')),
                    'anchored_grouped': read_turns(CACHE/(key+'.anchored.gap120.output.tsv'))}
        ref = read_rttm(refpath)[row['id']]
        for variant, turns in variants.items():
            results.append(dict(id=row['id'], prior_split=row['split'], variant=variant,
                                **score(ref, turns, row['duration_seconds'])))
    summaries = []
    for subset in ['all_observed', 'prior_development', 'prior_holdout']:
        for variant in variants:
            values = [r for r in results if r['variant'] == variant and
                      (subset == 'all_observed' or r['prior_split'] == subset.removeprefix('prior_'))]
            summaries.append(dict(subset=subset, variant=variant, **aggregation.aggregate(values)))
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT/'ablation.json').write_text(json.dumps(dict(
        scope='12 already observed English AMI180s crops; diagnostic ablation, NOT independent validation',
        inference='Cached real neural outputs. native grouping mirrors production; ERes/anchored outputs from production Kotlin research harness.',
        summaries=summaries, results=results), indent=2)+'\n', encoding='utf-8')
    for r in summaries:
        print({k:r[k] for k in ['subset','variant','der','jer','count_mae','miss_seconds','false_alarm_seconds','confusion_seconds']})


if __name__ == '__main__':
    main()

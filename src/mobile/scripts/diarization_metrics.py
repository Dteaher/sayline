"""Exact interval-based DER/JER with global speaker assignment, not frame sampling.

Rates are fractions. Default: no collar, overlaps scored. No reference is inferred
from model output. JER does not penalize every unmatched hypothesis speaker; DER
and count errors are always reported alongside it.
"""
import argparse
import json
import math
import re
from pathlib import Path


def assignment(cost):
    """Minimum-cost row assignment (Hungarian), with caller-supplied dummy columns."""
    n = len(cost)
    if not n: return []
    m = len(cost[0])
    if m < n or any(len(row) != m for row in cost): raise ValueError('Invalid cost matrix')
    u, v, p, way = [0.] * (n + 1), [0.] * (m + 1), [0] * (m + 1), [0] * (m + 1)
    for i in range(1, n + 1):
        p[0] = i; j0 = 0
        best, used = [math.inf] * (m + 1), [False] * (m + 1)
        while True:
            used[j0] = True; i0 = p[j0]; delta = math.inf; j1 = 0
            for j in range(1, m + 1):
                if not used[j]:
                    cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                    if cur < best[j]: best[j], way[j] = cur, j0
                    if best[j] < delta: delta, j1 = best[j], j
            for j in range(m + 1):
                if used[j]: u[p[j]] += delta; v[j] -= delta
                else: best[j] -= delta
            j0 = j1
            if p[j0] == 0: break
        while True:
            j1 = way[j0]; p[j0] = p[j1]; j0 = j1
            if j0 == 0: break
    result = [-1] * n
    for j in range(1, m + 1):
        if p[j]: result[p[j] - 1] = j - 1
    return result


def normalized(turns, duration):
    groups = {}
    for a, b, speaker in turns:
        if not all(math.isfinite(float(t)) for t in (a, b)) or not 0 <= a < b <= duration + 1e-3:
            raise ValueError('Invalid or out-of-bounds interval')
        groups.setdefault(str(speaker), []).append((float(a), min(float(b), duration)))
    result = []
    for speaker, intervals in groups.items():
        merged = []
        for a, b in sorted(intervals):
            if merged and a <= merged[-1][1]: merged[-1] = (merged[-1][0], max(b, merged[-1][1]))
            else: merged.append((a, b))
        result.extend((a, b, speaker) for a, b in merged)
    return sorted(result)


def score(reference, hypothesis, duration, collar=0., skip_overlap=False):
    if not math.isfinite(duration) or duration <= 0 or not math.isfinite(collar) or collar < 0:
        raise ValueError('Invalid evaluation duration/collar')
    ref, hyp = normalized(reference, duration), normalized(hypothesis, duration)
    rs, hs = sorted({t[2] for t in ref}), sorted({t[2] for t in hyp})
    # pyannote convention: collar is TOTAL width centered on a reference boundary.
    collars = [(max(0., t - collar/2), min(duration, t + collar/2)) for a, b, _ in ref for t in (a, b)] if collar else []
    edges = sorted({0., duration, *(t for a, b, _ in ref + hyp for t in (a, b)), *(t for pair in collars for t in pair)})
    cells = []
    for a, b in zip(edges, edges[1:]):
        center = (a + b) / 2
        r = {s for l, h, s in ref if l <= center < h}
        if skip_overlap and len(r) > 1 or any(l <= center < h for l, h in collars): continue
        h = {s for l, h, s in hyp if l <= center < h}
        cells.append((b - a, r, h))
    intersections = [[sum(dt for dt, r, h in cells if a in r and b in h) for b in hs] for a in rs]
    width = max(len(rs), len(hs))
    chosen = assignment([[-x for x in row] + [0.] * (width - len(hs)) for row in intersections])
    mapping = {hs[j]: rs[i] for i, j in enumerate(chosen) if j < len(hs)}
    reference_time = sum(dt * len(r) for dt, r, _ in cells)
    missed = sum(dt * max(0, len(r) - len(h)) for dt, r, h in cells)
    false_alarm = sum(dt * max(0, len(h) - len(r)) for dt, r, h in cells)
    confusion = sum(dt * (min(len(r), len(h)) - sum(mapping.get(s) in r for s in h)) for dt, r, h in cells)
    rtime = [sum(dt for dt, r, _ in cells if s in r) for s in rs]
    htime = [sum(dt for dt, _, h in cells if s in h) for s in hs]
    costs = []
    for i, row in enumerate(intersections):
        costs.append([1 - x / (rtime[i] + htime[j] - x) if rtime[i] + htime[j] - x else 0. for j, x in enumerate(row)] + [1.] * (width - len(hs)))
    jer_assignment = assignment(costs)
    jer = sum(costs[i][j] for i, j in enumerate(jer_assignment)) / len(rs) if rs else None
    overlap_time = sum(dt * len(r) for dt, r, _ in cells if len(r) > 1)
    overlap_miss = sum(dt * max(0, len(r) - len(h)) for dt, r, h in cells if len(r) > 1)
    result = dict(der=(missed + false_alarm + confusion) / reference_time if reference_time else None,
                  jer=jer, jer_mapping='minimum Jaccard cost, dscore convention',
                  miss_seconds=missed, false_alarm_seconds=false_alarm,
                  confusion_seconds=confusion, reference_speaker_seconds=reference_time,
                  reference_speakers=len(rs), hypothesis_speakers=len(hs),
                  count_signed_error=len(hs) - len(rs), count_absolute_error=abs(len(hs) - len(rs)),
                  overlap_reference_speaker_seconds=overlap_time, overlap_miss_seconds=overlap_miss,
                  overlap_miss_rate=overlap_miss / overlap_time if overlap_time else None,
                  collar_seconds=collar, collar_definition='total centered width, pyannote convention',
                  skip_overlap=skip_overlap, mapping=mapping)
    for limit in (.5, 1.):
        short = [(a, b, s) for a, b, s in ref if b - a <= limit + 1e-9]
        detected = correct = 0
        for a, b, s in short:
            bounds = sorted({a, b, *(t for l, h, _ in hyp for t in (l, h) if a < t < b)})
            speech = match = 0.
            for left, right in zip(bounds, bounds[1:]):
                active = {k for l, h, k in hyp if l <= (left + right) / 2 < h}
                if active: speech += right - left
                if any(mapping.get(k) == s for k in active): match += right - left
            detected += speech >= (b - a) * .5 - 1e-9
            correct += match >= (b - a) * .5 - 1e-9
        result[f'short_{limit}'] = dict(total=len(short), detected=detected, correct_speaker=correct,
                                       definition='>=50% of each reference interval; full intervals, independent of collar')
    return result


def read_rttm(path):
    records = {}
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.startswith('#'): continue
        fields = line.split()
        if len(fields) < 9 or fields[0] != 'SPEAKER': raise ValueError('Expected SPEAKER RTTM line')
        a, duration = float(fields[3]), float(fields[4])
        records.setdefault(fields[1], []).append((a, a + duration, fields[7]))
    return records


def cpwer(reference, hypothesis):
    """Concatenate per speaker, choose global minimum word-error permutation.
    Normalization: Unicode word tokens, lowercase, Russian ё→е. No numeral aliases.
    Missing/extra voices contribute deletions/insertions, including dummy rows.
    """
    def tokens(text): return re.findall(r'\w+', text.lower().replace('ё', 'е'), flags=re.UNICODE)
    ref, hyp = [tokens(t) for t in reference.values()], [tokens(t) for t in hypothesis.values()]
    n = max(len(ref), len(hyp))
    ref += [[] for _ in range(n-len(ref))]; hyp += [[] for _ in range(n-len(hyp))]
    def edit(a, b):
        last = list(range(len(b)+1))
        for i, word in enumerate(a, 1):
            row = [i]
            for j, other in enumerate(b, 1): row.append(min(row[-1]+1, last[j]+1, last[j-1]+(word != other)))
            last = row
        return last[-1]
    costs = [[edit(a,b) for b in hyp] for a in ref]
    chosen = assignment(costs)
    errors = sum(costs[i][j] for i,j in enumerate(chosen))
    words = sum(map(len,ref))
    return dict(cpwer=errors/words if words else None, errors=errors, reference_words=words,
                normalization='lowercase Unicode word tokens, ё→е; no numeral equivalence')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ref', required=True); parser.add_argument('--hyp', required=True)
    parser.add_argument('--duration', required=True, type=float)
    parser.add_argument('--collar', type=float, default=0.); parser.add_argument('--skip-overlap', action='store_true')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    ref, hyp = read_rttm(args.ref), read_rttm(args.hyp)
    if len(ref) != 1 or not set(hyp).issubset(ref): raise ValueError('CLI expects one recording with matching IDs')
    name = next(iter(ref))
    report = score(ref[name], hyp.get(name, []), args.duration, args.collar, args.skip_overlap)
    output = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output: args.output.write_text(output + '\n', encoding='utf-8')
    else: print(output)

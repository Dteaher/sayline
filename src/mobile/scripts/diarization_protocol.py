"""Frozen experiment allowlist. Holdout is not a second parameter sweep."""


def probe_parameters(split, requested):
    if split not in ('development','holdout'): raise ValueError('Unknown split')
    values = list(requested) if requested is not None else ([.3,.15] if split=='holdout' else [.3,.15,.05])
    if not values or any(not 0 <= x <= 1 for x in values): raise ValueError('Invalid minimum duration')
    if split=='holdout' and (len(values)!=2 or set(values)!={.3,.15}):
        raise ValueError('Holdout requires exactly baseline .3 and frozen candidate .15')
    return values


def variants(split,on):
    if split=='holdout':
        if on==.3:return ('baseline',)
        if on==.15:return ('selected',)
        raise ValueError('Unexpected holdout setting')
    return ('baseline','fast','bounded','guard','global','threshold45','threshold55','threshold60','threshold65')


def require_holdout_inputs(cache,recording):
    for suffix in ('on030.json','on030.input.tsv','on030.baseline.grouped.tsv',
                   'on015.json','on015.input.tsv','on015.selected.grouped.tsv'):
        if not (cache/f'{recording}-{suffix}').is_file(): raise ValueError(f'Incomplete frozen holdout: {recording}-{suffix}')

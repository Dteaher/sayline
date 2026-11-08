import importlib.util
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('cleanrefs',Path(__file__).resolve().parents[1]/'scripts/cleanrefs-experiment.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class CleanReferenceCacheTest(unittest.TestCase):
    def test_each_input_changes_fingerprint(self):
        baseline=m.vector_fingerprint('audio','model',b'0\t0\t2\n')
        for a,b,c in [('other','model',b'0\t0\t2\n'),('audio','other',b'0\t0\t2\n'),('audio','model',b'0\t1\t2\n')]:
            self.assertNotEqual(baseline,m.vector_fingerprint(a,b,c))

    def test_existing_vector_without_identity_is_not_reused(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'vectors.tsv';path.write_text('old')
            self.assertFalse(m.cached_vectors_match(path,'current'))

    def test_changed_windows_or_corrupt_vector_forces_recompute(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'vectors.tsv';path.write_text('0\t2\t.5\n')
            m.write_vector_identity(path,'current')
            self.assertTrue(m.cached_vectors_match(path,'current'))
            self.assertFalse(m.cached_vectors_match(path,'changed'))
            path.write_text('broken')
            self.assertFalse(m.cached_vectors_match(path,'current'))

    def test_corrupt_identity_is_not_a_valid_cache(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'vectors.tsv';path.write_text('x')
            Path(str(path)+'.identity.json').write_text('{broken')
            self.assertFalse(m.cached_vectors_match(path,'current'))

    def test_native_threshold_must_match_exact_cached_case(self):
        row={'id':'meeting','audio_sha256':'audio','reference_sha256':'reference'}
        metadata=dict(key='meeting-native060',id='meeting',split='holdout',threshold=.7,audio_sha256='audio',reference_sha256='reference')
        with self.assertRaises(ValueError):m.validate_native_case(metadata,'meeting-native060',row,'holdout')
        metadata['threshold']=.6
        m.validate_native_case(metadata,'meeting-native060',row,'holdout')

    def test_holdout_requires_exact_baseline_and_selected_keys(self):
        rows=[{'id':'a'},{'id':'b'}];selected={'native_threshold':.7,'grouping_gap':1.2}
        expected={'a-native050','a-native070','b-native050','b-native070'}
        m.validate_holdout_keys(list(expected),rows,selected)
        for keys in (expected-{'a-native070'},expected|{'a-native060'}):
            with self.assertRaises(ValueError):m.validate_holdout_keys(list(keys),rows,selected)

    def test_missing_selected_group_cannot_produce_baseline_only_report(self):
        selected={'native_threshold':.7,'grouping_gap':1.2}
        groups={(.5,'baseline',1.2):[{'id':'a'},{'id':'b'}]}
        with self.assertRaises(ValueError):m.validate_paired_groups(groups,['a','b'],selected)
        groups[(.7,'clean',1.2)]=[{'id':'a'},{'id':'b'}]
        m.validate_paired_groups(groups,['a','b'],selected)
        groups[(.7,'clean',1.2)]=[{'id':'a'},{'id':'a'}]
        with self.assertRaises(ValueError):m.validate_paired_groups(groups,['a','b'],selected)


if __name__=='__main__':unittest.main()

import importlib.util
from pathlib import Path
import unittest
import tempfile

spec=importlib.util.spec_from_file_location('protocol',Path(__file__).resolve().parents[1]/'scripts/diarization_protocol.py')
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)


class DiarizationProtocolTest(unittest.TestCase):
    def test_holdout_default_contains_only_baseline_and_frozen_candidate(self):
        self.assertEqual([.3,.15],p.probe_parameters('holdout',None))

    def test_exploratory_or_incomplete_holdout_parameters_are_rejected(self):
        for values in ([.3,.15,.05],[.15],[.3,.3]):
            with self.assertRaises(ValueError):p.probe_parameters('holdout',values)

    def test_holdout_variant_cannot_be_changed_by_cached_filename(self):
        self.assertEqual(('baseline',),p.variants('holdout',.3))
        self.assertEqual(('selected',),p.variants('holdout',.15))
        with self.assertRaises(ValueError):p.variants('holdout',.05)

    def test_development_can_sweep_explicit_parameters(self):
        self.assertEqual([.3,.15,.05],p.probe_parameters('development',None))
        self.assertIn('guard',p.variants('development',.15))

    def test_stale_tsv_cannot_hide_missing_holdout_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            for suffix in ('on030.baseline.grouped.tsv','on015.selected.grouped.tsv'):
                (root/f'case-{suffix}').write_text('')
            with self.assertRaises(ValueError):p.require_holdout_inputs(root,'case')
            for suffix in ('on030.json','on030.input.tsv','on015.json','on015.input.tsv'):
                (root/f'case-{suffix}').write_text('')
            p.require_holdout_inputs(root,'case')


if __name__=='__main__':unittest.main()

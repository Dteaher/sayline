import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('diarization_metrics', Path(__file__).resolve().parents[1] / 'scripts/diarization_metrics.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class DiarizationMetricsTest(unittest.TestCase):
    def test_permutation_and_duplicate_intervals_are_not_errors(self):
        ref = [(0, 2, 'A'), (2, 4, 'B'), (4, 5, 'A')]
        hyp = [(0, 2, '7'), (0, 1, '7'), (2, 4, '3'), (4, 5, '7')]
        r = m.score(ref, hyp, duration=6)
        self.assertEqual(0, r['der'])
        self.assertEqual(0, r['jer'])
        self.assertEqual(0, r['count_signed_error'])

    def test_missing_overlap_has_one_second_miss(self):
        r = m.score([(0, 3, 'A'), (1, 2, 'B')], [(0, 3, 'X')], duration=3)
        self.assertAlmostEqual(.25, r['der'])
        self.assertEqual(1, r['miss_seconds'])
        self.assertAlmostEqual(.5, r['overlap_miss_rate'])
        self.assertEqual(0, m.score([(0, 3, 'A'), (1, 2, 'B')], [(0, 3, 'X')], duration=3, skip_overlap=True)['der'])

    def test_ghost_speaker_is_penalized_even_when_jer_is_zero(self):
        r = m.score([(0, 2, 'A')], [(0, 2, 'X'), (2, 3, 'ghost')], duration=3)
        self.assertEqual(.5, r['der'])
        self.assertEqual(0, r['jer'])
        self.assertEqual(1, r['count_absolute_error'])

    def test_short_rare_voice_confusion_is_visible(self):
        r = m.score([(0, 3, 'A'), (4, 4.25, 'B')], [(0, 3, 'X'), (4, 4.25, 'X')], duration=5)
        self.assertEqual(1, r['short_0.5']['total'])
        self.assertEqual(1, r['short_0.5']['detected'])
        self.assertEqual(0, r['short_0.5']['correct_speaker'])
        self.assertEqual(.25, r['confusion_seconds'])

    def test_collar_is_explicit_and_can_remove_boundary_error(self):
        ref, hyp = [(1, 2, 'A')], [(1.1, 2.1, 'X')]
        self.assertGreater(m.score(ref, hyp, duration=3)['der'], 0)
        self.assertGreater(m.score(ref, hyp, duration=3, collar=.15)['der'], 0)
        self.assertEqual(0, m.score(ref, hyp, duration=3, collar=.3)['der'])

    def test_invalid_reference_fails_instead_of_silently_disappearing(self):
        with self.assertRaises(ValueError): m.score([(2, 1, 'A')], [], duration=3)

    def test_cpwer_handles_permutation_and_extra_voice_words(self):
        r = m.cpwer({'A': 'Да согласен', 'B': 'Нет'}, {'9': 'Нет', '2': 'да, согласен!'})
        self.assertEqual(0, r['errors'])
        r = m.cpwer({'A': 'Да согласен'}, {'9': 'Да согласен', 'ghost': 'ошибка'})
        self.assertEqual(1, r['errors'])
        self.assertEqual(.5, r['cpwer'])


if __name__ == '__main__': unittest.main()

import importlib.util
from pathlib import Path
import sys
import unittest

SCRIPTS=Path(__file__).resolve().parents[1]/'scripts'
sys.path.insert(0,str(SCRIPTS))
spec=importlib.util.spec_from_file_location('ruscore',SCRIPTS/'score-russian-regression.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class RussianScoreTest(unittest.TestCase):
    def fixture(self):
        transcript={'transcript':[{'text':'Да, согласен!','speaker_id':'speaker_1'}]}
        report={'cases':[{'id':'row20','reference':'да согласен','audio_seconds':2.,'baseline':transcript,'candidate':transcript,'baseline_elapsed_ms':400,'candidate_elapsed_ms':300}]}
        corpus={'fixtures':[{'recording_id':'row20','reference':'да согласен'}]}
        return report,corpus

    def test_public_text_is_scored_without_punctuation_or_speaker_id_penalty(self):
        report,corpus=self.fixture();r=m.score_report(report,corpus)
        self.assertEqual(0,r['totals']['candidate']['errors'])
        self.assertEqual(.15,r['totals']['candidate']['rtf'])

    def test_missing_or_duplicate_recording_cannot_silently_score(self):
        report,corpus=self.fixture();report['cases']*=2
        with self.assertRaises(ValueError):m.score_report(report,corpus)
        report['cases']=[]
        with self.assertRaises(ValueError):m.score_report(report,corpus)

    def test_modified_reference_is_rejected(self):
        report,corpus=self.fixture();report['cases'][0]['reference']='другой текст'
        with self.assertRaises(ValueError):m.score_report(report,corpus)


if __name__=='__main__':unittest.main()

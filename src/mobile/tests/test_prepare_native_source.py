import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


class NativeSourcePreparationTest(unittest.TestCase):
    def test_fresh_checkout_downloads_then_rejects_untrusted_archive(self):
        path=Path(__file__).resolve().parents[1]/"scripts/prepare-native-evidence.py"
        spec=importlib.util.spec_from_file_location("prepare_native_source",path)
        module=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            def download(url,target):
                Path(target).write_bytes(b"untrusted source archive")
            with patch.object(module,"ROOT",root), patch.object(sys,"argv",[str(path),str(root/"source")]), patch.object(module.urllib.request,"urlretrieve",side_effect=download):
                with self.assertRaisesRegex(ValueError,"Pinned source mismatch"):
                    module.main()
            self.assertFalse((root/"source").exists())


if __name__=="__main__":
    unittest.main()

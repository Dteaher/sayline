"""Exercise the artifact CLI on real temporary ZIPs; Android tools are external doubles."""
import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import runpy
import shutil
import struct
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/verify-artifacts.py'
BASE_MODELS = (
    'gigaam-v3.int8.onnx', 'tokens.txt', 'segmentation.onnx', 'speaker.onnx',
    'gtcrn.onnx', 'speaker-refinement.onnx',
)
ABIS = ('arm64-v8a', 'x86_64')
RUNTIME_LIBRARIES = ('libsherpa-onnx-jni.so', 'libonnxruntime.so')


def aligned_elf_fixture():
    data = bytearray(120)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<Q', data, 32, 64)  # program-header offset
    struct.pack_into('<HH', data, 54, 56, 1)  # entry size and count
    struct.pack_into('<I', data, 64, 1)  # PT_LOAD
    struct.pack_into('<Q', data, 112, 16384)
    return bytes(data)


class ArtifactVerifierTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='neuroscribe-artifact-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'scripts').mkdir()
        (self.root / 'dist').mkdir()
        (self.root / 'tests').mkdir()
        self.models = self.root / 'app/src/main/assets/models'
        self.models.mkdir(parents=True)
        self.script = self.root / 'scripts/verify-artifacts.py'
        shutil.copyfile(SCRIPT, self.script)
        self.model_data = {name: ('fixture-' + name).encode() for name in BASE_MODELS}
        self.manifest = self._manifest_for(self.model_data)
        self.libraries = {f'lib/{abi}/{name}': aligned_elf_fixture()
                          for abi in ABIS for name in RUNTIME_LIBRARIES}

    @staticmethod
    def _manifest_for(files):
        return {name: {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                for name, data in files.items()}

    def _write_package(self, manifest=None, source_manifest=None, libraries=None):
        manifest = self.manifest if manifest is None else manifest
        source_manifest = manifest if source_manifest is None else source_manifest
        for name, data in self.model_data.items():
            (self.models / name).write_bytes(data)
        (self.models / 'manifest.json').write_text(json.dumps(source_manifest), encoding='utf-8')
        with zipfile.ZipFile(self.root / 'dist/Sayline.apk', 'w') as apk:
            apk.writestr('assets/models/manifest.json', json.dumps(manifest))
            for name in manifest:
                apk.writestr('assets/models/' + name, self.model_data[name])
            for name, data in (self.libraries if libraries is None else libraries).items():
                apk.writestr(name, data)
            apk.writestr('assets/licenses/fixture.txt', 'fixture license')

    @staticmethod
    def _android_tool(command, **_kwargs):
        executable = Path(command[0]).name
        if executable == 'aapt.exe' and command[1:3] == ['dump', 'permissions']:
            output = "package: ru.neuroscribe\nuses-permission: name='android.permission.RECORD_AUDIO'\n"
        elif executable == 'apksigner.bat' and command[1:3] == ['verify', '--verbose']:
            output = 'Verifies\n'
        elif executable == 'zipalign.exe' and command[1:5] == ['-c', '-P', '16', '4']:
            output = ''
        else:
            raise AssertionError('Unexpected Android tool invocation: ' + repr(command))
        return subprocess.CompletedProcess(command, 0, stdout=output, stderr='')

    def _run_cli(self):
        with mock.patch.dict(os.environ, {'ANDROID_HOME': str(self.root / 'sdk')}), \
                mock.patch('subprocess.run', side_effect=self._android_tool), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            runpy.run_path(str(self.script), run_name='__main__')
        return json.loads((self.root / 'tests/artifact-results.json').read_text(encoding='utf-8')), output.getvalue()

    def test_complete_package_preserves_json_report(self):
        self._write_package()
        report, output = self._run_cli()
        self.assertEqual(list(ABIS), report['abis'])
        self.assertEqual(set(BASE_MODELS), set(report['model_files']))
        self.assertTrue(report['signature_verified'])
        self.assertTrue(report['zip_aligned_16k'])
        self.assertTrue(report['native_16k_compatible'])
        self.assertEqual(4, len(report['native_load_alignments']))
        self.assertEqual(report['sha256'], json.loads(output)['sha256'])
        self.assertNotIn('model_files', json.loads(output))

    def test_additional_manifest_declared_model_is_verified(self):
        self.model_data['punctuation.onnx'] = b'additional-local-model'
        self.manifest = self._manifest_for(self.model_data)
        self._write_package()
        report, _ = self._run_cli()
        self.assertIn('punctuation.onnx', report['model_files'])

    def test_empty_manifest_cannot_pass_packaging_gate(self):
        self._write_package(manifest={})
        with self.assertRaisesRegex(ValueError, 'Missing mandatory model entries'):
            self._run_cli()

    def test_every_base_model_is_required_even_if_both_manifests_omit_it(self):
        for name in BASE_MODELS:
            with self.subTest(model=name):
                incomplete = {key: value for key, value in self.manifest.items() if key != name}
                self._write_package(manifest=incomplete)
                with self.assertRaisesRegex(ValueError, 'Missing mandatory model entries'):
                    self._run_cli()

    def test_source_and_apk_manifests_must_match(self):
        source_manifest = copy.deepcopy(self.manifest)
        source_manifest['speaker.onnx']['sha256'] = '0' * 64
        self._write_package(source_manifest=source_manifest)
        with self.assertRaisesRegex(ValueError, 'Source/APK model manifests differ'):
            self._run_cli()

    def test_model_declared_only_in_source_cannot_be_omitted_from_apk(self):
        source_manifest = dict(self.manifest, **{'punctuation.onnx': {
            'bytes': 5, 'sha256': hashlib.sha256(b'extra').hexdigest()}})
        self._write_package(source_manifest=source_manifest)
        with self.assertRaisesRegex(ValueError, 'Source/APK model manifests differ'):
            self._run_cli()

    def test_every_abi_requires_both_jni_and_onnx_runtime(self):
        for abi in ABIS:
            for library in RUNTIME_LIBRARIES:
                missing = f'lib/{abi}/{library}'
                with self.subTest(missing=missing):
                    self._write_package(libraries={key: value for key, value in self.libraries.items()
                                                   if key != missing})
                    with self.assertRaisesRegex(ValueError, 'Missing mandatory inference libraries'):
                        self._run_cli()


if __name__ == '__main__':
    unittest.main()

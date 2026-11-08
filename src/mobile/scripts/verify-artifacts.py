"""Inspect the delivered APK, not source assumptions, for offline model packaging."""
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
# These are the base assets consumed by SpeechEngine. Any additional declared
# models (for example punctuation) must also match the source manifest and bytes.
REQUIRED_MODELS = {
    'gigaam-v3.int8.onnx', 'tokens.txt', 'segmentation.onnx', 'speaker.onnx', 'gtcrn.onnx', 'speaker-refinement.onnx',
}
REQUIRED_ABIS = {'arm64-v8a', 'x86_64'}
REQUIRED_LIBRARIES = {'libsherpa-onnx-jni.so', 'libonnxruntime.so'}
apk = ROOT / 'dist/Sayline.apk'
apk_arg = str(apk.relative_to(ROOT))
sdk = Path(os.environ['ANDROID_HOME'])
source_models = ROOT / 'app/src/main/assets/models'
source_manifest = json.loads((source_models / 'manifest.json').read_text(encoding='utf-8'))
with zipfile.ZipFile(apk) as z:
    if z.testzip() is not None:
        raise ValueError('APK contains a corrupt ZIP entry')
    manifest = json.loads(z.read('assets/models/manifest.json'))
    if not isinstance(manifest, dict) or not isinstance(source_manifest, dict):
        raise ValueError('Model manifests must be JSON objects')
    missing_models = REQUIRED_MODELS - manifest.keys()
    if missing_models:
        raise ValueError('Missing mandatory model entries: ' + ', '.join(sorted(missing_models)))
    if manifest != source_manifest:
        raise ValueError('Source/APK model manifests differ')
    verified = {}
    for name, info in manifest.items():
        data = z.read('assets/models/' + name)
        digest = hashlib.sha256(data).hexdigest()
        if len(data) != info['bytes'] or digest != info['sha256']:
            raise ValueError('APK model size/hash mismatch: ' + name)
        if hashlib.sha256((source_models / name).read_bytes()).hexdigest() != digest:
            raise ValueError('Source/APK model mismatch: ' + name)
        verified[name] = info
    libraries = [n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
    abis = sorted({n.split('/')[1] for n in libraries})
    if set(abis) != REQUIRED_ABIS:
        raise ValueError('Unexpected APK ABI set: ' + repr(abis))
    missing_libraries = {f'lib/{abi}/{name}' for abi in REQUIRED_ABIS for name in REQUIRED_LIBRARIES} - set(libraries)
    if missing_libraries:
        raise ValueError('Missing mandatory inference libraries: ' + ', '.join(sorted(missing_libraries)))
    alignments = {}
    for name in libraries:
        data = z.read(name)
        if data[:5] != b'\x7fELF\x02':
            raise ValueError('Native library is not ELF64: ' + name)
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        loads = [struct.unpack_from('<Q', data, offset+i*size+48)[0]
                 for i in range(count) if struct.unpack_from('<I', data, offset+i*size)[0] == 1]
        alignments[name] = min(loads)
    if not any(n.startswith('assets/licenses/') for n in z.namelist()):
        raise ValueError('APK has no bundled license assets')
    assert all(b'visual-test-only' not in z.read(n) for n in z.namelist() if n.endswith('.dex')), 'Visual fixture leaked into app APK'

permissions = subprocess.run([str(sdk/'build-tools/36.0.0/aapt.exe'), 'dump', 'permissions', apk_arg], cwd=ROOT, capture_output=True, text=True, check=True).stdout
if 'android.permission.INTERNET' in permissions:
    raise ValueError('Offline APK must not request INTERNET permission')
if 'android.permission.RECORD_AUDIO' not in permissions:
    raise ValueError('APK is missing microphone permission')
signature = subprocess.run([str(sdk/'build-tools/36.0.0/apksigner.bat'), 'verify', '--verbose', apk_arg], cwd=ROOT, capture_output=True, text=True, check=True)
alignment = subprocess.run([str(sdk/'build-tools/36.0.0/zipalign.exe'), '-c', '-P', '16', '4', apk_arg], cwd=ROOT, capture_output=True, text=True)
report = {'apk': apk.name, 'bytes': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
          'abis': abis, 'model_files': verified, 'permissions': permissions.splitlines(), 'test_fixture_data_absent': True,
          'signature_verified': signature.returncode == 0, 'zip_aligned_16k': alignment.returncode == 0,
          'native_load_alignments': alignments, 'native_16k_compatible': all(a >= 16384 for a in alignments.values())}
(ROOT/'tests/artifact-results.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k != 'model_files'}, indent=2))
if not report['zip_aligned_16k'] or not report['native_16k_compatible']:
    raise ValueError('APK/native libraries are not 16 KB aligned')

"""Fetch official portable build tools and model artifacts; never execute remote scripts."""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.work'
TOOLS = Path(os.environ['LOCALAPPDATA']) / 'NeuroScribe' / 'tools'
WORK.mkdir(exist_ok=True)
TOOLS.mkdir(parents=True, exist_ok=True)
BASE = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/'

def fetch(url, target, checksum=None):
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        print('Downloading', target.name, flush=True)
        req = urllib.request.Request(url, headers={'User-Agent': 'NeuroScribe-build'})
        with urllib.request.urlopen(req, timeout=90) as src, target.with_suffix(target.suffix + '.part').open('wb') as dst:
            shutil.copyfileobj(src, dst)
        target.with_suffix(target.suffix + '.part').replace(target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    if checksum and digest.lower() != checksum.lower():
        raise ValueError(f'Checksum mismatch for {target}')
    print(target.name, target.stat().st_size, digest, flush=True)
    return target

def tools():
    cached = Path.home() / '.cache/tailtown-tools'
    jdks = sorted((cached / 'jdk').glob('*/bin/javac.exe'))
    if jdks and (cached / 'android-sdk/platform-tools/adb.exe').exists():
        sdk = cached / 'android-sdk'
        (ROOT / 'local.properties').write_text('sdk.dir=' + sdk.as_posix().replace(':', '\\:') + '\n', encoding='utf-8')
        print('Reusing official portable JDK', jdks[0].parents[1], 'and SDK', sdk, flush=True)
        return
    req = urllib.request.Request('https://api.github.com/repos/adoptium/temurin17-binaries/releases/latest', headers={'User-Agent': 'NeuroScribe-build'})
    release = json.load(urllib.request.urlopen(req, timeout=30))
    asset = next(a for a in release['assets'] if a['name'].startswith('OpenJDK17U-jdk_x64_windows_hotspot_') and a['name'].endswith('.zip'))
    checksum = asset.get('digest', '').removeprefix('sha256:') or urllib.request.urlopen(asset['browser_download_url'] + '.sha256.txt', timeout=30).read().decode().split()[0]
    jdk = fetch(asset['browser_download_url'], WORK / asset['name'], checksum)
    if not (TOOLS / 'jdk').exists():
        with zipfile.ZipFile(jdk) as z:
            z.extractall(TOOLS / 'jdk-unpack')
        next((TOOLS / 'jdk-unpack').iterdir()).rename(TOOLS / 'jdk')
    cli = fetch('https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip', WORK / 'android-cli.zip', '98b565cb657b012dae6794cefc0f66ae1efb4690c699b78a614b4a6a3505b003')
    sdk = TOOLS / 'android-sdk'
    if not (sdk / 'cmdline-tools/latest/bin/sdkmanager.bat').exists():
        with zipfile.ZipFile(cli) as z:
            z.extractall(WORK / 'sdk-unpack')
        (sdk / 'cmdline-tools').mkdir(parents=True, exist_ok=True)
        shutil.move(str(WORK / 'sdk-unpack/cmdline-tools'), str(sdk / 'cmdline-tools/latest'))
    (ROOT / 'local.properties').write_text('sdk.dir=' + sdk.as_posix().replace(':', '\\:') + '\n', encoding='utf-8')
    print('JAVA_HOME=' + str(TOOLS / 'jdk'), 'ANDROID_HOME=' + str(sdk), flush=True)

def models():
    assets = ROOT / 'app/src/main/assets/models'
    assets.mkdir(parents=True, exist_ok=True)
    jobs = [
        ('asr-models/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16.tar.bz2', WORK / 'gigaam-v3-punct-ctc.tar.bz2', '20e41e160efa6f2460a7ef6554cdbbb9e8fffb2b92e6bc708e9406bd8b9256ea'),
        ('speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2', WORK / 'segmentation.tar.bz2', '24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488'),
        ('speaker-recongition-models/wespeaker_en_voxceleb_resnet34.onnx', assets / 'speaker.onnx', '5ef208a9da1453335308a6b6f4e6dfbd7e183a38b604de0a57664f45d257fe94'),
        ('speech-enhancement-models/gtcrn_simple.onnx', assets / 'gtcrn.onnx', 'e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534'),
        ('speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx', assets / 'speaker-refinement.onnx', 'c59158379255ad66e161679cca6af8d52d51e389e3224ab7d7a7baae295c2db5'),
        ('v1.13.8/sherpa-onnx-1.13.8.aar', ROOT / 'app/libs/sherpa-onnx-1.13.8.aar', '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96')]
    with concurrent.futures.ThreadPoolExecutor(4) as pool:
        list(pool.map(lambda job: fetch(BASE + job[0], job[1], job[2]), jobs))
    for name in ['gigaam-v3-punct-ctc', 'segmentation']:
        with tarfile.open(WORK / (name + '.tar.bz2')) as tar:
            tar.extractall(WORK / name, filter='data')
    asr = next((WORK / 'gigaam-v3-punct-ctc').iterdir())
    shutil.copy2(asr / 'model.int8.onnx', assets / 'gigaam-v3.int8.onnx')
    shutil.copy2(asr / 'tokens.txt', assets / 'tokens.txt')
    licenses = ROOT / 'app/src/main/assets/licenses'
    licenses.mkdir(parents=True, exist_ok=True)
    shutil.copy2(asr / 'LICENSE', licenses / 'GigaAM-MIT.txt')
    # Retired inference assets must not inflate a fresh delivery or replace CTC tokens.
    for name in ['encoder.int8.onnx', 'decoder.onnx', 'joiner.int8.onnx', 'README.md']:
        retired = assets / name
        if retired.exists():
            retired.unlink()
    segmentation = next((WORK / 'segmentation').rglob('model.onnx'))
    shutil.copy2(segmentation, assets / 'segmentation.onnx')
    print('ASR files', [p.name for p in assets.iterdir()], flush=True)
    manifest = {p.name: {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in assets.iterdir() if p.is_file() and p.name != 'manifest.json'}
    (assets / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')

if __name__ == '__main__':
    import sys
    {'tools': tools, 'models': models}[sys.argv[1]]()

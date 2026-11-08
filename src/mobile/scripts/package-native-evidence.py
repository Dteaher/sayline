"""Repackage reviewed native extensions while retaining pinned Kotlin classes/ORT."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser();p.add_argument('--research-x86-only',action='store_true');p.add_argument('--output',type=Path);args=p.parse_args()
    base=Path(os.environ['LOCALAPPDATA'])/'NeuroScribe/native-evidence-build'
    sdk=Path(os.environ['ANDROID_HOME']);strip=sdk/'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'
    original=ROOT/'app/libs/sherpa-onnx-1.13.8.aar';output=args.output or ROOT/'app/libs/sherpa-onnx-neuro-evidence.aar'
    replacements={};hashes={}
    for abi in (['x86_64'] if args.research_x86_only else ['x86_64','arm64-v8a']):
        target=base/abi/'lib/libsherpa-onnx-jni.so';stripped=target.with_name('libsherpa-onnx-jni-stripped.so')
        subprocess.run([str(strip),'--strip-unneeded','-o',str(stripped),str(target)],check=True)
        data=stripped.read_bytes();replacements[f'jni/{abi}/libsherpa-onnx-jni.so']=data
        hashes[abi]=hashlib.sha256(data).hexdigest()
    info=dict(base_version='1.13.8',original_aar_sha256=hashlib.sha256(original.read_bytes()).hexdigest(),
              modified_jni=hashes,research_x86_only=args.research_x86_only,
              extension_source_sha256=hashlib.sha256((ROOT/'native/evidence-jni.cc').read_bytes()).hexdigest())
    with zipfile.ZipFile(original) as old,zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=1) as new:
        for entry in old.infolist():new.writestr(entry,replacements.get(entry.filename,old.read(entry.filename)))
        new.writestr('META-INF/neuroscribe-native-evidence.json',json.dumps(info,indent=2))
    print(json.dumps(dict(bytes=output.stat().st_size,sha256=hashlib.sha256(output.read_bytes()).hexdigest(),**info),indent=2))
if __name__=='__main__':main()

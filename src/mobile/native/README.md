# NeuroScribe native evidence extension

Pinned upstream: sherpa-onnx v1.13.8 (Apache-2.0). `scripts/prepare-native-evidence.py` verifies the source ZIP digest and applies exact source anchors, failing on mismatch. Original Kotlin API/classes and ONNX Runtime bytes remain in the AAR. The two JNI binaries are source-built derivatives, not publisher binaries.

The optional JNI entry point returns schema1: version, sample rate, embedding dimension, reference count, segment count; four floats per segment; then each reference carries window/local/global IDs, clean/activity span counts, embedding and span pairs. Kotlin validates bounds, finite values, dimensions and full consumption. Input is bounded to20minutes/16k mono. Native array access is released with JNI_ABORT by RAII; a thread-local capture flag is restored even on failure. Existing `process` output is unchanged when capture is disabled. Short single-window upstream results legitimately have no embedding references.

The capture runs after the existing inference/clustering and exposes data already computed. Active/clean spans are model-derived labels, not probabilities or ground truth. It does not separate audio sources or export full powerset posteriors. No inference callback crosses JNI.

Rebuild from project root in PowerShell:

```powershell
. ./scripts/env.ps1
./scripts/build-native-evidence.ps1 -Abi x86_64
./scripts/build-native-evidence.ps1 -Abi arm64-v8a
python scripts/package-native-evidence.py
```

Requires installed Android NDK28.2.13676358 and CMake3.22.1. The source/build directories are beneath `%LOCALAPPDATA%/NeuroScribe` to avoid Windows toolchain issues with Cyrillic paths. CMake dependencies use the pinned upstream download definitions/hashes. The first build downloads dependencies; normal APK execution downloads nothing. TTS is disabled because this product does not synthesize speech.

`--research-x86-only` packaging is for local emulator experiments only and MUST NOT be delivered. Final AAR metadata records both modified JNI hashes and original AAR hash. Production uses the fully built `app/libs/sherpa-onnx-neuro-evidence.aar`; the original remains for provenance/rebuilding.

`-PnativeEvidenceResearch=true` adds the explicit Android evidence tests. `-PdiarizationResearch=true` adds the host Kotlin replay tests. Neither flag is needed for the normal APK. See NATIVE_EVIDENCE_RESULTS.md for measured effects and limitations.

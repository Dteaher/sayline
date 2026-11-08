package com.k2fsa.sherpa.onnx

/** Companion entry point in our pinned, source-patched JNI build. Caller owns diarizer lifetime. */
object NeuroEvidence {
    external fun process(diarizer: OfflineSpeakerDiarization, samples: FloatArray): FloatArray
}

package ru.neuroscribe.core

import kotlin.math.abs

/** Bounded gain for very quiet recognized speech windows; never gates or invents speech. */
fun normalizeQuietSpeech(samples: FloatArray) {
    var peak = 0f
    for (sample in samples) peak = maxOf(peak, abs(sample))
    if (peak < .00001f || peak >= .08f) return
    val gain = minOf(10f, .85f / peak)
    for (i in samples.indices) samples[i] *= gain
}

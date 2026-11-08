package ru.neuroscribe.core

fun waveformEnvelope(samples: FloatArray, bars: Int = 64): List<Float> {
    require(bars > 0)
    if (samples.isEmpty()) return emptyList()
    val count = minOf(bars, samples.size)
    val energy = List(count) { bar ->
        val from = bar * samples.size / count
        val to = (bar + 1) * samples.size / count
        var sum = 0.0
        for (i in from until to) {
            val sample = samples[i].takeIf { it.isFinite() } ?: 0f
            sum += sample.toDouble() * sample
        }
        kotlin.math.sqrt(sum / (to - from)).toFloat()
    }
    val peak = energy.maxOrNull() ?: 0f
    return if (peak < .0001f) List(count) { 0f } else energy.map { (it / peak).coerceIn(0f, 1f) }
}

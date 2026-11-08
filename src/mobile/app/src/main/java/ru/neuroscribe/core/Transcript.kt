package ru.neuroscribe.core

import java.util.Locale
import kotlin.math.*

data class Turn(val start: Float, val end: Float, val speaker: Int)
data class Replica(val start: Float, val end: Float, val speaker: Int, val text: String)

/** Short pauses within the same speaker's reply retain linguistic context for ASR. */
fun groupSpeakerTurns(turns: List<Turn>, duration: Float, maxGap: Float = 1.2f): List<Turn> {
    require(maxGap.isFinite() && maxGap in 0f..2f)
    val grouped = mutableListOf<Turn>()
    for (turn in prepareTurns(turns, duration)) {
        val previous = grouped.lastOrNull()
        if (previous != null && previous.speaker == turn.speaker && turn.start - previous.end <= maxGap) {
            grouped[grouped.lastIndex] = previous.copy(end = maxOf(previous.end, turn.end))
        } else grouped += turn
    }
    return grouped
}

fun prepareTurns(turns: List<Turn>, duration: Float): List<Turn> {
    require(duration.isFinite() && duration >= 0)
    return turns.filter { it.start.isFinite() && it.end.isFinite() && it.speaker >= 0 }
        .sortedBy { it.start }.mapNotNull { turn ->
            val start = turn.start.coerceIn(0f, duration)
            val end = turn.end.coerceIn(0f, duration)
            if (end - start > .001f) Turn(start, end, turn.speaker) else null
        }
}

fun downmix(samples: FloatArray, channels: Int): FloatArray {
    require(channels > 0 && samples.size % channels == 0)
    return FloatArray(samples.size / channels) { frame ->
        var sum = 0f
        repeat(channels) { sum += samples[frame * channels + it] }
        sum / channels
    }
}

/** Windowed-sinc low-pass resampling; filtering avoids downsampling aliasing. */
fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
    require(from > 0 && to > 0)
    if (samples.isEmpty() || from == to) return samples.copyOf()
    val radius = 24
    val phases = 512
    val cutoff = min(1.0, to.toDouble() / from) * .94
    val kernels = Array(phases) { phase ->
        DoubleArray(radius * 2) { tap ->
            val x = tap - radius + 1 - phase.toDouble() / phases
            val sinc = if (abs(x) < 1e-9) cutoff else sin(PI * x * cutoff) / (PI * x)
            sinc * (.5 + .5 * cos(PI * x / radius)).coerceAtLeast(0.0)
        }
    }
    return FloatArray((samples.size.toLong() * to / from).toInt()) { i ->
        val position = i.toDouble() * from / to
        val center = position.toInt()
        val kernel = kernels[((position - center) * phases).toInt().coerceIn(0, phases - 1)]
        var value = 0.0
        var weight = 0.0
        kernel.forEachIndexed { tap, w ->
            val source = center + tap - radius + 1
            if (source in samples.indices) { value += samples[source] * w; weight += w }
        }
        if (abs(weight) < 1e-9) 0f else (value / weight).toFloat().coerceIn(-1f, 1f)
    }
}

fun timeLabel(seconds: Float): String {
    val whole = if (seconds.isFinite()) seconds.coerceAtLeast(0f).toLong() else 0L
    return String.format(Locale.ROOT, "%02d:%02d", whole / 60, whole % 60)
}

private fun preciseTime(seconds: Float): String {
    val ms = (seconds.coerceAtLeast(0f) * 1000).roundToLong()
    return String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60000, ms / 1000 % 60, ms % 1000)
}

fun exportText(replicas: List<Replica>, names: Map<Int, String> = emptyMap()): String {
    val speakers = replicas.map { it.speaker }.distinct()
    return replicas.joinToString("\n\n") { r ->
        val label = names[r.speaker]?.takeIf { it.isNotBlank() } ?: "Говорящий ${speakers.indexOf(r.speaker) + 1}"
        "[${preciseTime(r.start)} – ${preciseTime(r.end)}] $label\n${r.text}"
    }
}

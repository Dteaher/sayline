package ru.neuroscribe.audio

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal enum class PcmEncoding(val bytes: Int) { U8(1), S16(2), S24(3), S32(4), F32(4) }

internal data class PcmFormat(val rate: Int, val channels: Int, val encoding: PcmEncoding) {
    init {
        require(rate in 8000..96000 && channels in 1..8) { "Неподдерживаемый формат аудио." }
    }
    val frameBytes: Int get() = channels * encoding.bytes
}

/** Keeps only 64 source frames while accumulating the bounded 16 kHz model input. */
internal class Pcm16kBuffer(val format: PcmFormat, expectedSourceFrames: Int? = null) {
    init {
        require(expectedSourceFrames == null || expectedSourceFrames in 1..format.rate * MAX_SECONDS)
    }
    private val history = FloatArray(64)
    private var sourceCount = 0
    // PCM WAV provides an exact frame count. Allocate once instead of repeatedly
    // copying a growing recording and copying it again to trim at finish().
    private var output = FloatArray(expectedSourceFrames?.let {
        (it.toLong() * TARGET_RATE / format.rate).toInt().coerceAtLeast(1)
    } ?: 65536)
    private var outputCount = 0
    private val kernels by lazy {
        val cutoff = minOf(1.0, TARGET_RATE.toDouble() / format.rate) * .94
        Array(PHASES) { phase ->
            DoubleArray(RADIUS * 2) { tap ->
                val x = tap - RADIUS + 1 - phase.toDouble() / PHASES
                val sinc = if (abs(x) < 1e-9) cutoff else sin(PI * x * cutoff) / (PI * x)
                sinc * (.5 + .5 * cos(PI * x / RADIUS)).coerceAtLeast(0.0)
            }
        }
    }

    fun append(pcm: ByteBuffer) {
        require(pcm.remaining() % format.frameBytes == 0) { "Повреждён неполный кадр PCM." }
        val frames = pcm.remaining() / format.frameBytes
        require(sourceCount.toLong() + frames <= format.rate.toLong() * MAX_SECONDS) { DURATION_ERROR }
        repeat(frames) {
            var sum = 0f
            repeat(format.channels) { sum += readSample(pcm) }
            val mono = sum / format.channels
            if (format.rate == TARGET_RATE) {
                addOutput(mono)
                sourceCount++
            } else {
                history[sourceCount % history.size] = mono
                sourceCount++
                // Delay output until the low-pass filter's future samples have arrived.
                while (outputCount.toLong() * format.rate / TARGET_RATE + RADIUS < sourceCount) emit()
            }
        }
    }

    fun finish(): FloatArray {
        require(sourceCount > 0) { "Аудиодорожка пуста." }
        val finalCount = (sourceCount.toLong() * TARGET_RATE / format.rate).toInt()
        if (format.rate != TARGET_RATE) while (outputCount < finalCount) emit()
        require(outputCount > 0) { "Аудиодорожка слишком короткая." }
        return if (outputCount == output.size) output else output.copyOf(outputCount)
    }

    private fun readSample(buffer: ByteBuffer): Float {
        val sample = when (format.encoding) {
            PcmEncoding.U8 -> ((buffer.get().toInt() and 255) - 128) / 128f
            PcmEncoding.S16 -> buffer.short / 32768f
            PcmEncoding.S24 -> {
                val low = buffer.get().toInt() and 255
                val middle = buffer.get().toInt() and 255
                val high = buffer.get().toInt()
                (low or (middle shl 8) or (high shl 16)) / 8388608f
            }
            PcmEncoding.S32 -> (buffer.int / 2147483648.0).toFloat()
            PcmEncoding.F32 -> buffer.float
        }
        require(sample.isFinite()) { "В аудиодорожке некорректные значения PCM." }
        return sample.coerceIn(-1f, 1f)
    }

    private fun emit() {
        val position = outputCount.toDouble() * format.rate / TARGET_RATE
        val center = position.toInt()
        val kernel = kernels[((position - center) * PHASES).toInt().coerceIn(0, PHASES - 1)]
        var value = 0.0
        var weight = 0.0
        for (tap in kernel.indices) {
            val source = center + tap - RADIUS + 1
            if (source >= 0 && source < sourceCount) {
                val w = kernel[tap]
                value += history[source % history.size] * w
                weight += w
            }
        }
        addOutput(if (abs(weight) < 1e-9) 0f else (value / weight).toFloat().coerceIn(-1f, 1f))
    }

    private fun addOutput(value: Float) {
        if (outputCount == output.size) output = output.copyOf(minOf(output.size * 2, TARGET_RATE * MAX_SECONDS))
        output[outputCount++] = value
    }

    companion object {
        const val TARGET_RATE = 16000
        const val MAX_SECONDS = ru.neuroscribe.core.AudioLimits.MAX_SECONDS
        const val DURATION_ERROR = ru.neuroscribe.core.AudioLimits.DURATION_ERROR
        private const val RADIUS = 24
        private const val PHASES = 512
    }
}

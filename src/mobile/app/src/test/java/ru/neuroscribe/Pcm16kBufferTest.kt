package ru.neuroscribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neuroscribe.audio.Pcm16kBuffer
import ru.neuroscribe.audio.PcmEncoding
import ru.neuroscribe.audio.PcmFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class Pcm16kBufferTest {
    @Test fun knownFrameCountPreservesEveryOutputSampleAcrossRatesAndChunkBoundaries() {
        for (rate in listOf(8000, 16000, 44100, 48000, 96000)) {
            val format = PcmFormat(rate, 2, PcmEncoding.S16)
            val frames = rate + 137
            val growing = Pcm16kBuffer(format)
            val exact = Pcm16kBuffer(format, frames)
            var offset = 0
            while (offset < frames) {
                val count = minOf(113, frames - offset)
                val buffer = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
                repeat(count) { frame ->
                    buffer.putShort(((offset + frame) * 73).toShort())
                    buffer.putShort(((offset + frame) * -31).toShort())
                }
                buffer.flip()
                growing.append(buffer)
                buffer.rewind()
                exact.append(buffer)
                offset += count
            }
            assertTrue("Exact allocation changed PCM at $rate Hz", growing.finish().contentEquals(exact.finish()))
        }
    }

    @Test fun fractionalRateKeepsTheSignalContinuousAcrossSmallPcmBuffers() {
        val converter = Pcm16kBuffer(PcmFormat(44100, 1, PcmEncoding.F32))
        var position = 0
        while (position < 44100) {
            val count = minOf(17, 44100 - position)
            val buffer = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(count) { buffer.putFloat((.5 * sin(2 * PI * 1000 * position++ / 44100)).toFloat()) }
            buffer.flip()
            converter.append(buffer)
        }
        val result = converter.finish()
        assertEquals(16000, result.size)
        var maximumError = 0.0
        for (index in 100 until 15900) {
            maximumError = maxOf(maximumError, abs(result[index] - .5 * sin(2 * PI * 1000 * index / 16000)))
        }
        assertTrue("Buffer boundaries distorted the 1kHz tone: $maximumError", maximumError < .002)
    }

    @Test fun maximumRateDoesNotChangeSilenceOrItsDuration() {
        val converter = Pcm16kBuffer(PcmFormat(96000, 8, PcmEncoding.S16))
        val buffer = ByteBuffer.allocate(960 * 8 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(100) { buffer.clear(); converter.append(buffer) }
        val result = converter.finish()
        assertEquals(16000, result.size)
        assertTrue(result.all { it == 0f })
    }

    @Test fun lowerRateUpsamplingKeepsDurationAndConstantAmplitude() {
        val converter = Pcm16kBuffer(PcmFormat(8000, 1, PcmEncoding.S16))
        val buffer = ByteBuffer.allocate(16000).order(ByteOrder.LITTLE_ENDIAN)
        repeat(8000) { buffer.putShort(8192) }
        buffer.flip()
        converter.append(buffer)
        val result = converter.finish()
        assertEquals(16000, result.size)
        assertTrue(result.all { abs(it - .25f) < .00001f })
    }
}

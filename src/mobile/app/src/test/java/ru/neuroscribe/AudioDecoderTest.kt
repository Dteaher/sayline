package ru.neuroscribe

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.junit.Assert.assertThrows
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neuroscribe.audio.AudioDecoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioDecoderTest {
    @Test fun pcmWaveDecodesWithoutAnInstalledPlatformRawCodec() = runBlocking {
        withWave(16000, 1, 16, byteArrayOf(0, 0, 0, 64, 0, -128, -1, 127)) { file ->
            assertArrayEquals(floatArrayOf(0f, .5f, -1f, 32767f / 32768), AudioDecoder().decode(file), .000001f)
        }
    }

    @Test fun stereoWaveIsDownmixedBeforeResampling() = runBlocking {
        val pcm = ByteBuffer.allocate(48000 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48000) { pcm.putShort(8192); pcm.putShort(16384) }
        withWave(48000, 2, 16, pcm.array()) { file ->
            val result = AudioDecoder().decode(file)
            assertEquals(16000, result.size)
            assertTrue(result.all { kotlin.math.abs(it - .375f) < .00001f })
        }
    }

    @Test fun nonFiniteFloatWaveIsRejectedInsteadOfEnteringInference() = runBlocking {
        val pcm = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array()
        withWave(16000, 1, 32, pcm, formatCode = 3) { file ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { AudioDecoder().decode(file) } }
        }
    }

    @Test fun waveWithAnIncompleteChannelFrameIsRejected() = runBlocking {
        withWave(16000, 2, 16, byteArrayOf(1, 2)) { file ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { AudioDecoder().decode(file) } }
        }
    }

    @Test fun integerWaveEncodingsUseTheCorrectSignedScale() = runBlocking {
        val cases = listOf(
            8 to byteArrayOf(0, -128, -1),
            24 to byteArrayOf(0, 0, -128, 0, 0, 0, -1, -1, 127),
            32 to ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(Int.MIN_VALUE).putInt(0).putInt(Int.MAX_VALUE).array()
        )
        for ((bits, pcm) in cases) withWave(16000, 1, bits, pcm) { file ->
            val result = AudioDecoder().decode(file)
            assertEquals(-1f, result[0], .000001f)
            assertEquals(0f, result[1], .000001f)
            assertTrue(result[2] > .99f && result[2] <= 1f)
        }
    }

    @Test fun floatWaveClipsFiniteOutOfRangeSamples() = runBlocking {
        val pcm = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(-2f).putFloat(.125f).putFloat(2f).array()
        withWave(16000, 1, 32, pcm, formatCode = 3) { file ->
            assertArrayEquals(floatArrayOf(-1f, .125f, 1f), AudioDecoder().decode(file), .000001f)
        }
    }

    @Test fun downsamplingSuppressesAudioAboveTheModelNyquistFrequency() = runBlocking {
        val pcm = ByteBuffer.allocate(48000 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48000) { pcm.putShort((kotlin.math.sin(2 * Math.PI * 12000 * it / 48000) * 16000).toInt().toShort()) }
        withWave(48000, 1, 16, pcm.array()) { file ->
            val result = AudioDecoder().decode(file)
            val rms = kotlin.math.sqrt(result.sliceArray(100 until 15900).map { it * it }.average())
            assertTrue("Aliased 12kHz tone RMS was $rms", rms < .03)
        }
    }

    @Test fun truncatedWavePayloadIsRejected() = runBlocking {
        withWave(16000, 1, 16, byteArrayOf(0, 0, 1, 1)) { file ->
            RandomAccessFile(file, "rw").use { it.setLength(file.length() - 1) }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { AudioDecoder().decode(file) } }
        }
    }

    @Test fun durationLimitDoesNotDependOnMetadata() = runBlocking {
        withWave(16000, 1, 16, ByteArray((ru.neuroscribe.core.AudioLimits.MAX_SAMPLES + 1) * 2)) { file ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { AudioDecoder().decode(file) } }
        }
    }

    @Test fun fiveMinuteHighRateWaveDoesNotNeedAFullSourcePcmArray() = runBlocking {
        withWave(96000, 2, 16, byteArrayOf(0, 0, 0, 0)) { file ->
            val dataBytes = 96000 * 300 * 4
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(44L + dataBytes)
                output.seek(4)
                output.writeInt(Integer.reverseBytes(36 + dataBytes))
                output.seek(40)
                output.writeInt(Integer.reverseBytes(dataBytes))
            }
            val result = AudioDecoder().decode(file)
            assertEquals(4800000, result.size)
            assertTrue(result.all { it == 0f })
        }
    }

    @Test fun cancellationIsObservedBeforeOpeningAnyMediaResources() = runBlocking {
        val file = File.createTempFile("cancelled-audio", ".wav")
        try {
            var cancelled = false
            launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                try { AudioDecoder().decode(file) }
                catch (_: CancellationException) { cancelled = true }
            }.join()
            assertTrue(cancelled)
        } finally { file.delete() }
    }

    @Test fun oversizedInputIsRejectedBeforeOpeningThePlatformDecoder() {
        val file = File.createTempFile("oversized-audio", ".wav")
        try {
            RandomAccessFile(file, "rw").use { it.setLength(ru.neuroscribe.core.AudioLimits.MAX_FILE_BYTES + 1) }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { AudioDecoder().decode(file) } }
        } finally { file.delete() }
    }

    @Test fun emptyInputIsRejectedBeforeOpeningThePlatformDecoder() {
        val file = File.createTempFile("empty-audio", ".wav")
        try {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { AudioDecoder().decode(file) } }
        } finally { file.delete() }
    }

    private suspend fun withWave(rate: Int, channels: Int, bits: Int, pcm: ByteArray,
        formatCode: Int = 1, block: suspend (File) -> Unit) {
        val file = File.createTempFile("decoder-test", ".wav")
        try {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + pcm.size + pcm.size % 2)
            header.put("WAVEfmt ".toByteArray()).putInt(16).putShort(formatCode.toShort())
            header.putShort(channels.toShort()).putInt(rate).putInt(rate * channels * bits / 8)
            header.putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
            header.put("data".toByteArray()).putInt(pcm.size)
            file.outputStream().use { it.write(header.array()); it.write(pcm); if (pcm.size % 2 != 0) it.write(0) }
            block(file)
        } finally { file.delete() }
    }
}

package ru.neuroscribe

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.audio.AudioDecoder
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class AudioDecoderConnectedTest {
    @Test fun localAacM4aDecodesToFinite16kMono() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "decoder-generated-tone.m4a")
        try {
            encodeTone(file)
            val pcm = AudioDecoder().decode(file)
            assertTrue("Unexpected decoded duration: ${pcm.size / 16000f}s", pcm.size in 15200..17600)
            assertTrue(pcm.all { it.isFinite() && it in -1f..1f })
            val rms = sqrt(pcm.sliceArray(1600 until 14400).map { it * it }.average())
            assertTrue("AAC tone amplitude was $rms", rms in .12.. .23)
        } finally { file.delete() }
    }

    /** Creates a one-second 48 kHz stereo sine wave using only local Android APIs. */
    private fun encodeTone(file: File) {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96000)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val info = MediaCodec.BufferInfo()
            var inputFrames = 0
            var inputEnded = false
            var track = -1
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (true) {
                check(SystemClock.elapsedRealtime() < deadline) { "AAC fixture encoding timed out" }
                if (!inputEnded) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = requireNotNull(encoder.getInputBuffer(index)).order(ByteOrder.nativeOrder())
                        input.clear()
                        val frames = minOf(input.remaining() / 4, 48000 - inputFrames)
                        val time = inputFrames * 1_000_000L / 48000
                        repeat(frames) {
                            val value = (8192 * sin(2 * PI * 1000 * inputFrames++ / 48000)).toInt().toShort()
                            input.putShort(value).putShort(value)
                        }
                        val flags = if (frames == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        encoder.queueInputBuffer(index, 0, frames * 4, time, flags)
                        inputEnded = frames == 0
                    }
                }
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted)
                            muxer.writeSampleData(track, requireNotNull(encoder.getOutputBuffer(index)), info)
                        }
                    } finally { encoder.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            try { runCatching { encoder.stop() }; encoder.release() }
            finally {
                try { if (muxerStarted) muxer.stop() }
                finally { muxer.release() }
            }
        }
    }
}

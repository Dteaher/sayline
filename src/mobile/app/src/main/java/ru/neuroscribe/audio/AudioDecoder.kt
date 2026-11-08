package ru.neuroscribe.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteOrder

class AudioDecoder {
    suspend fun decode(file: File): FloatArray {
        val context = currentCoroutineContext()
        context.ensureActive()
        require(file.isFile && file.length() > 0) { "Аудиофайл пуст или недоступен." }
        require(file.length() <= ru.neuroscribe.core.AudioLimits.MAX_FILE_BYTES) { "Размер аудиофайла превышает 512 МБ." }
        WavePcmReader.decodeIfWave(file) { context.ensureActive() }?.let { return it }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("В файле нет поддерживаемой аудиодорожки.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                require(format.getLong(MediaFormat.KEY_DURATION) <= ru.neuroscribe.core.AudioLimits.MAX_SECONDS * 1_000_000L + 500_000L) { Pcm16kBuffer.DURATION_ERROR }
            }
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var sampleFormat = pcmFormat(format)
            var pcm: Pcm16kBuffer? = null
            var inputEnded = false
            var lastOutput = SystemClock.elapsedRealtime()
            val info = MediaCodec.BufferInfo()
            while (true) {
                currentCoroutineContext().ensureActive()
                check(SystemClock.elapsedRealtime() - lastOutput < 20_000) { "Не удалось декодировать запись. Попробуйте WAV, M4A или MP3." }
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = decoder.outputFormat
                    val updated = pcmFormat(output)
                    require(pcm == null || updated == sampleFormat) { "Формат аудио изменился внутри записи." }
                    sampleFormat = updated
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                            require(info.offset >= 0 && info.size <= buffer.capacity() - info.offset) { "Повреждён буфер аудиодекодера." }
                            buffer.limit(info.offset + info.size)
                            buffer.position(info.offset)
                            val converter = pcm ?: Pcm16kBuffer(sampleFormat).also { pcm = it }
                            converter.append(buffer)
                            lastOutput = SystemClock.elapsedRealtime()
                        }
                    } finally { decoder.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            currentCoroutineContext().ensureActive()
            return requireNotNull(pcm) { "Аудиодорожка пуста." }.finish()
        } finally {
            try { codec?.let { runCatching { it.stop() }; it.release() } }
            finally { extractor.release() }
        }
    }

    private fun pcmFormat(format: MediaFormat): PcmFormat {
        val encoding = when (if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT) {
            AudioFormat.ENCODING_PCM_8BIT -> PcmEncoding.U8
            AudioFormat.ENCODING_PCM_16BIT -> PcmEncoding.S16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmEncoding.S24
            AudioFormat.ENCODING_PCM_32BIT -> PcmEncoding.S32
            AudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.F32
            else -> error("Неподдерживаемая разрядность PCM.")
        }
        return PcmFormat(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), encoding)
    }
}

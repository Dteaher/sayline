package ru.neuroscribe.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Direct RIFF PCM decoding avoids depending on a device's raw MediaCodec implementation. */
internal object WavePcmReader {
    fun decodeIfWave(file: File, checkActive: () -> Unit): FloatArray? {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 12) return null
            if (input.readInt() != 0x52494646) return null // RIFF
            val end = input.readUnsignedIntLe() + 8
            if (input.readInt() != 0x57415645) return null // WAVE
            require(end in 12..input.length()) { "Повреждён заголовок WAV." }
            var format: PcmFormat? = null
            var dataStart = -1L
            var dataBytes = 0L
            while (input.filePointer + 8 <= end) {
                checkActive()
                val chunk = input.readInt()
                val size = input.readUnsignedIntLe()
                val start = input.filePointer
                require(size <= end - start) { "Аудиодорожка WAV обрезана." }
                when (chunk) {
                    0x666d7420 -> { // fmt
                        require(format == null) { "Повторный заголовок формата WAV." }
                        format = readFormat(input, size)
                        // Compressed WAV (for example ADPCM) stays with the platform decoder.
                        if (format == null) return null
                    }
                    0x64617461 -> { // data
                        require(dataStart < 0) { "Несколько блоков данных WAV не поддерживаются." }
                        dataStart = start
                        dataBytes = size
                    }
                }
                if (format != null && dataStart >= 0) break
                input.seek(start + size + size % 2)
            }
            val pcmFormat = requireNotNull(format) { "В WAV нет описания формата PCM." }
            require(dataStart >= 0 && dataBytes > 0) { "Аудиодорожка пуста." }
            require(dataBytes % pcmFormat.frameBytes == 0L) { "Повреждён неполный кадр PCM." }
            require(dataBytes / pcmFormat.frameBytes <= pcmFormat.rate.toLong() * Pcm16kBuffer.MAX_SECONDS) {
                Pcm16kBuffer.DURATION_ERROR
            }
            input.seek(dataStart)
            val converter = Pcm16kBuffer(pcmFormat, (dataBytes / pcmFormat.frameBytes).toInt())
            val bytes = ByteArray(pcmFormat.frameBytes * 2048)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var remaining = dataBytes
            while (remaining > 0) {
                checkActive()
                val count = minOf(remaining, bytes.size.toLong()).toInt()
                input.readFully(bytes, 0, count)
                buffer.clear().limit(count)
                converter.append(buffer)
                remaining -= count
            }
            checkActive()
            return converter.finish()
        }
    }

    private fun readFormat(input: RandomAccessFile, size: Long): PcmFormat? {
        require(size >= 16) { "Повреждён формат WAV." }
        var code = input.readUnsignedShortLe()
        val channels = input.readUnsignedShortLe()
        val rate = input.readUnsignedIntLe()
        input.readUnsignedIntLe() // Byte rate; block alignment below defines the actual frame layout.
        val alignment = input.readUnsignedShortLe()
        val bits = input.readUnsignedShortLe()
        if (code == 0xfffe) {
            require(size >= 40 && input.readUnsignedShortLe() >= 22) { "Повреждён расширенный формат WAV." }
            val validBits = input.readUnsignedShortLe()
            require(validBits in 1..bits) { "Некорректная разрядность WAV." }
            input.readUnsignedIntLe() // Channel mask; the model receives an average of all channels.
            val subformat = ByteArray(16).also(input::readFully)
            val standardTail = byteArrayOf(0, 0, 0, 0, 0x10, 0, -128, 0, 0, -86, 0, 56, -101, 113)
            require(subformat.copyOfRange(2, 16).contentEquals(standardTail)) { "Неподдерживаемый подформат WAV." }
            code = (subformat[0].toInt() and 255) or ((subformat[1].toInt() and 255) shl 8)
        }
        val encoding = when (code) {
            1 -> when (bits) {
                8 -> PcmEncoding.U8
                16 -> PcmEncoding.S16
                24 -> PcmEncoding.S24
                32 -> PcmEncoding.S32
                else -> error("Неподдерживаемая разрядность PCM WAV.")
            }
            3 -> { require(bits == 32) { "Поддерживается 32-битный float WAV." }; PcmEncoding.F32 }
            else -> return null
        }
        require(rate in 8000..96000 && alignment == channels * encoding.bytes) { "Неподдерживаемый формат WAV." }
        return PcmFormat(rate.toInt(), channels, encoding)
    }

    private fun RandomAccessFile.readUnsignedShortLe(): Int =
        java.lang.Short.reverseBytes(readShort()).toInt() and 0xffff

    private fun RandomAccessFile.readUnsignedIntLe(): Long =
        java.lang.Integer.reverseBytes(readInt()).toLong() and 0xffffffffL
}

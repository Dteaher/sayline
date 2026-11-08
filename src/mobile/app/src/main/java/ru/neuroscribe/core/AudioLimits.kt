package ru.neuroscribe.core

/** 20 minutes of mono float32 at 16 kHz occupy 73.3 MiB, independent of source format. */
object AudioLimits {
    const val MAX_SECONDS = 20 * 60
    const val MAX_SAMPLES = MAX_SECONDS * 16000
    const val MAX_FILE_BYTES = 512L * 1024 * 1024
    const val DURATION_ERROR = "Поддерживаются записи до 20 минут."
}

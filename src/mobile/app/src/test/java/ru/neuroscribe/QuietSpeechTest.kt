package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.normalizeQuietSpeech

class QuietSpeechTest {
    @Test fun quietSpeechGetsBoundedGainWithoutClipping() {
        val x = floatArrayOf(.001f, -.02f, .01f)
        normalizeQuietSpeech(x)
        assertArrayEquals(floatArrayOf(.01f, -.2f, .1f), x, .00001f)
    }
    @Test fun silenceAndOrdinarySpeechStayUntouched() {
        val silence = FloatArray(100)
        normalizeQuietSpeech(silence)
        assertTrue(silence.all { it == 0f })
        val normal = floatArrayOf(-.5f, .2f)
        normalizeQuietSpeech(normal)
        assertArrayEquals(floatArrayOf(-.5f, .2f), normal, 0f)
    }
    @Test fun almostSilentNoiseIsNotAmplified() {
        val x = floatArrayOf(.000001f)
        normalizeQuietSpeech(x)
        assertEquals(.000001f, x[0], 0f)
    }
}

package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.waveformEnvelope

class WaveformTest {
    @Test fun silenceStaysFlat() {
        assertEquals(listOf(0f, 0f, 0f, 0f), waveformEnvelope(FloatArray(160), 4))
    }
    @Test fun energyRemainsInTheCorrectPartOfTheRecording() {
        val pcm = floatArrayOf(0f, 0f, 1f, -1f, .5f, -.5f, 0f, 0f)
        assertEquals(listOf(0f, 1f, .5f, 0f), waveformEnvelope(pcm, 4))
    }
    @Test fun emptyAudioDoesNotInventAnEnvelope() {
        assertTrue(waveformEnvelope(floatArrayOf(), 48).isEmpty())
    }
}

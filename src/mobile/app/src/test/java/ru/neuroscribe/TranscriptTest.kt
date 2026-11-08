package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.*

class TranscriptTest {
    @Test fun returningSpeakerKeepsItsClusterAndChronology() {
        val parts = prepareTurns(listOf(Turn(12f, 16f, 4), Turn(1f, 3f, 4), Turn(5f, 8f, 9)), 20f)
        assertEquals(listOf(4, 9, 4), parts.map { it.speaker })
        assertEquals(listOf(1f, 5f, 12f), parts.map { it.start })
    }
    @Test fun invalidIntervalsAreRejectedAndModelIntervalsClippedToAudio() {
        val parts = prepareTurns(listOf(Turn(-2f, 60f, 1), Turn(3f, 2f, 0), Turn(Float.NaN, 4f, 0)), 40f)
        assertEquals(listOf(Turn(0f, 40f, 1)), parts)
    }
    @Test fun downmixDoesNotDoubleTheAmplitude() {
        val mono = downmix(floatArrayOf(1f, -1f, .5f, .5f), 2)
        assertArrayEquals(floatArrayOf(0f, .5f), mono, .0001f)
    }
    @Test fun resamplingPreservesDurationAndConstantSignal() {
        val out = resample(FloatArray(48000) { .25f }, 48000, 16000)
        assertEquals(16000, out.size)
        assertTrue(out.all { kotlin.math.abs(it - .25f) < .0001f })
    }
    @Test fun exportUsesRealStartAndSpeakerLabels() {
        val text = exportText(listOf(Replica(1.25f, 3.5f, 2, "Добрый день")), mapOf(2 to "Анна"))
        assertEquals("[00:01.250 – 00:03.500] Анна\nДобрый день", text)
    }
    @Test fun silenceProducesNoManufacturedTurns() {
        assertTrue(prepareTurns(emptyList(), 10f).isEmpty())
    }
    @Test fun overlappingLongTurnDoesNotMoveEarlyReplyToTheEnd() {
        val parts = prepareTurns(listOf(Turn(0f, 60f, 0), Turn(5f, 10f, 1)), 60f)
        assertEquals(listOf(0f, 5f), parts.map { it.start })
    }
    @Test fun downsamplingFiltersOutFrequenciesAboveNyquist() {
        val signal = FloatArray(48000) { kotlin.math.sin(2 * Math.PI * 12000 * it / 48000).toFloat() }
        val output = resample(signal, 48000, 16000)
        val rms = kotlin.math.sqrt(output.sliceArray(100 until 15900).map { it * it }.average())
        assertTrue("Aliased 12kHz tone RMS was $rms", rms < .03)
    }
}

package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.*

class TranscriptEditsTest {
    private val source = listOf(Replica(1f, 2f, 4, "Да."), Replica(3f, 5f, 8, "Продолжим."))

    @Test fun editingKeepsIntervalsAndOtherTurns() {
        val result = editReplica(source, 0, " Нет. ", 8)
        assertEquals(Replica(1f, 2f, 8, "Нет."), result[0])
        assertEquals(source[1], result[1])
        assertEquals("Да.", source[0].text)
    }
    @Test fun unknownSpeakerAndBlankTextAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { editReplica(source, 0, "Нет.", 9) }
        assertThrows(IllegalArgumentException::class.java) { editReplica(source, 0, "  ", 4) }
    }
    @Test fun restoreUsesOriginalAndNeverOtherTurn() {
        val edited = editReplica(source, 0, "Нет.", 8)
        assertEquals(source, restoreReplica(edited, source, 0))
        assertThrows(IllegalArgumentException::class.java) { restoreReplica(edited, source.take(1), 1) }
    }
    @Test fun longInputHasExplicitSampleAndByteBounds() {
        assertEquals(20 * 60 * 16000, AudioLimits.MAX_SAMPLES)
        assertTrue(AudioLimits.MAX_FILE_BYTES >= 20L * 60 * 48000 * 2 * 2)
    }
}

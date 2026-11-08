package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.*

class NativeDiarizationTraceTest {
    private fun valid()=floatArrayOf(1f,16000f,2f,1f,1f, .2f,2f,3f,-2f,
        0f,1f,3f,1f,1f, 1f,0f, .3f,1f, .2f,2f)
    @Test fun decodesActualTimesLabelsVectorsAndUnavailableConfidence() {
        val r=decodeNativeDiarizationTrace(valid(),3f)
        assertEquals(Turn(.2f,2f,3),r.turns.single())
        assertNull(r.confidence.single())
        assertEquals(1,r.references.single().localSpeaker)
        assertEquals(listOf(SpeechSpan(.3f,1f)),r.references.single().clean)
        assertArrayEquals(floatArrayOf(1f,0f),r.references.single().embedding,0f)
    }
    @Test fun emptyShortRecordingTraceDoesNotInventReferences() {
        assertTrue(decodeNativeDiarizationTrace(floatArrayOf(1f,16000f,0f,0f,0f),1f).references.isEmpty())
    }
    @Test fun rejectsMalformedCountsTruncationTrailingDataAndInvalidVectors() {
        for (bad in listOf(valid().dropLast(1).toFloatArray(),valid()+0f,
            valid().also { it[3]=4097f },valid().also { it[14]=Float.NaN },valid().also { it[14]=0f },
            valid().also { it[5]=3.001f;it[6]=3.002f })) {
            assertThrows(IllegalArgumentException::class.java) { decodeNativeDiarizationTrace(bad,3f) }
        }
    }
}

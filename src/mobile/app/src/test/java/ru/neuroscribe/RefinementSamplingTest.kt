package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.*

class RefinementSamplingTest {
    @Test fun longRecordingKeepsLateRareVoiceWithinBudget() {
        val turns = (0 until 700).map { Turn(it * 3f, it * 3f + 2f, if (it == 699) 9 else it % 3) }
        val selected = selectRefinementSamples(turns)
        assertTrue(selected.contains(699))
        assertTrue(selected.size <= 256)
        assertTrue(selected.sumOf { minOf(10f, turns[it].end-turns[it].start).toDouble() } <= 600)
        assertEquals(setOf(0, 1, 2, 9), selected.map { turns[it].speaker }.toSet())
    }

    @Test fun ordinaryInputRetainsExistingEmbeddingWindows() {
        val turns = listOf(Turn(0f, 2f, 0), Turn(3f, 3.2f, 1), Turn(4f, 7f, 0))
        assertEquals(listOf(0, 1, 2), selectRefinementSamples(turns))
    }

    @Test fun overlapIsExcludedWithoutDroppingEveryIntervalOfThatSpeaker() {
        val turns = listOf(Turn(0f, 4f, 0), Turn(1f, 2f, 1), Turn(5f, 7f, 0), Turn(8f, 10f, 1))
        assertArrayEquals(booleanArrayOf(true, true, false, false), mixedReferenceTurns(turns))
        assertEquals(listOf(2, 3), selectRefinementSamples(turns))
    }

    @Test fun finalEvidenceUsesCurrentGroupsAndDoesNotInventConfidence() {
        val turns = listOf(Turn(0f, 2f, 0), Turn(3f, 5f, 1), Turn(6f, 8f, 0), Turn(9f, 9.2f, 2))
        val e = speakerEvidence(turns, listOf(floatArrayOf(1f,0f),floatArrayOf(0f,1f),floatArrayOf(.99f,.01f),null))
        assertTrue(e[0].silhouette!! > .9f)
        assertTrue(e[1].provisional)
        assertNull(e[3].silhouette)
        assertTrue(e[3].provisional)
    }

    @Test fun unusableNeuralEmbeddingFallsBackInsteadOfAbortingTranscript() {
        assertNull(usableSpeakerEmbedding(floatArrayOf(Float.NaN, 1f)))
        assertNull(usableSpeakerEmbedding(floatArrayOf(Float.POSITIVE_INFINITY)))
        assertNull(usableSpeakerEmbedding(floatArrayOf(0f, 0f)))
        assertArrayEquals(floatArrayOf(1f, 0f), usableSpeakerEmbedding(floatArrayOf(1f, 0f)), 0f)
    }
}

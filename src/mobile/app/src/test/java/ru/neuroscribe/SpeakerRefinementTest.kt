package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.*

class SpeakerRefinementTest {
    @Test fun newUtteranceFromSameVoiceReturnsToItsCluster() {
        val turns = listOf(Turn(0f, 2f, 0), Turn(3f, 5f, 0), Turn(6f, 8f, 0))
        val vectors = listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(.99f, .01f))
        val result = refineSpeakers(turns, vectors)
        assertEquals(result[0].speaker, result[2].speaker)
        assertNotEquals(result[0].speaker, result[1].speaker)
        assertEquals(turns.map { it.start to it.end }, result.map { it.start to it.end })
    }
    @Test fun unavailableEmbeddingsPreserveNativeEvidence() {
        val turns = listOf(Turn(0f, .2f, 4), Turn(1f, 3f, 4), Turn(4f, 6f, 8))
        val result = refineSpeakers(turns, listOf(null, floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
        assertEquals(result[0].speaker, result[1].speaker)
        assertNotEquals(result[1].speaker, result[2].speaker)
        assertEquals(turns, refineSpeakers(turns, listOf(null, null, null)))
    }
    @Test fun invalidVectorCannotSilentlyProduceACluster() {
        assertThrows(IllegalArgumentException::class.java) {
            refineSpeakers(listOf(Turn(0f, 1f, 0)), listOf(floatArrayOf(Float.NaN)))
        }
    }

    @Test fun briefNoisyTailCannotSeedAGhostParticipant() {
        val turns = listOf(Turn(0f, 3f, 0), Turn(4f, 7f, 1), Turn(8f, 9.18f, 1))
        val result = refineSpeakers(turns, listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f)))
        assertEquals(2, result.map { it.speaker }.distinct().size)
        assertEquals(result[1].speaker, result[2].speaker)
        assertEquals(turns.map { it.start to it.end }, result.map { it.start to it.end })
    }

    @Test fun missingMixedEmbeddingsCannotCollapseTwoSimultaneousNativeVoices() {
        val turns = listOf(Turn(0f, 3f, 0), Turn(4f, 7f, 1), Turn(8f, 10f, 0), Turn(8.5f, 9.5f, 1), Turn(11f, 13f, 0))
        val result = refineSpeakers(turns, listOf(floatArrayOf(1f,0f), floatArrayOf(.99f,.01f), null, null, floatArrayOf(1f,0f)), preserveOverlap = true)
        assertNotEquals(result[2].speaker, result[3].speaker)
        assertEquals(result[0].speaker, result[2].speaker)
        assertEquals(result[0].speaker, result[4].speaker)
        assertEquals(2, result.map { it.speaker }.distinct().size)
    }

    @Test fun isolatedBriefNativeVoiceSurvivesAsProvisionalParticipant() {
        val turns = listOf(Turn(0f, 3f, 0), Turn(4f, 7f, 1), Turn(8f, 8.25f, 7), Turn(9f, 12f, 0))
        val result = refineSpeakers(turns, listOf(floatArrayOf(1f,0f), floatArrayOf(0f,1f), null, floatArrayOf(.99f,.01f)), preserveOverlap = true)
        assertEquals(3, result.map { it.speaker }.distinct().size)
        assertNotEquals(result[0].speaker, result[2].speaker)
    }

    @Test fun confidentBriefReplyCanCorrectAnIncorrectNativeGroup() {
        val turns = listOf(Turn(0f, 3f, 0), Turn(4f, 7f, 1), Turn(8f, 8.4f, 0))
        val result = refineSpeakers(turns, listOf(floatArrayOf(1f,0f), floatArrayOf(0f,1f), floatArrayOf(.01f,.99f)), globalShortAssignment = true)
        assertEquals(result[1].speaker, result[2].speaker)
        assertNotEquals(result[0].speaker, result[2].speaker)
    }

    @Test fun tinyCleanRemainderOfLongTurnCannotSeedNewAnchor() {
        val turns = listOf(Turn(0f, 5f, 0), Turn(6f, 10f, 1), Turn(11f, 15f, 1))
        val result = refineSpeakers(turns, listOf(floatArrayOf(1f,0f,0f),floatArrayOf(0f,1f,0f),floatArrayOf(0f,0f,1f)),
            referenceDurations = listOf(5f,4f,.25f))
        assertEquals(result[1].speaker,result[2].speaker)
        assertEquals(2,result.map { it.speaker }.distinct().size)
    }
}

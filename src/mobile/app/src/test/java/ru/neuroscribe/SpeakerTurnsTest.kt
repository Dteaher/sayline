package ru.neuroscribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.neuroscribe.core.Turn
import ru.neuroscribe.core.prepareTurns
import ru.neuroscribe.core.groupSpeakerTurns

class SpeakerTurnsTest {
    @Test fun groupingGapCanBeMeasuredWithoutChangingDefault() {
        val turns=listOf(Turn(0f,1f,0),Turn(1.8f,2.5f,0))
        assertEquals(1,groupSpeakerTurns(turns,3f).size)
        assertEquals(2,groupSpeakerTurns(turns,3f,maxGap=.3f).size)
        assertThrows(IllegalArgumentException::class.java) { groupSpeakerTurns(turns,3f,maxGap=Float.NaN) }
    }
    @Test fun longReplicaRetainsItsModelDerivedOuterBoundaries() {
        assertEquals(listOf(Turn(1f, 61f, 4)), prepareTurns(listOf(Turn(1f, 61f, 4)), 70f))
    }

    @Test fun shortSameSpeakerPausesKeepRecognitionContext() {
        val turns = listOf(Turn(.03f, 1.01f, 4), Turn(1.4f, 5f, 4), Turn(5.7f, 8f, 4))
        assertEquals(listOf(Turn(.03f, 8f, 4)), groupSpeakerTurns(turns, 10f))
    }

    @Test fun returningSpeakerDoesNotAbsorbTheOtherPersonsReply() {
        val turns = listOf(Turn(0f, 1f, 4), Turn(1.2f, 2f, 9), Turn(2.2f, 3f, 4))
        assertEquals(turns, groupSpeakerTurns(turns, 5f))
    }

    @Test fun longSilenceStartsAnotherReplicaForTheSameSpeaker() {
        val turns = listOf(Turn(0f, 1f, 4), Turn(3f, 4f, 4))
        assertEquals(turns, groupSpeakerTurns(turns, 5f))
    }

    @Test fun invalidTurnsAreDiscardedAndAudioBoundsAreRespected() {
        assertEquals(listOf(Turn(0f, 2f, 0), Turn(4f, 5f, 1)), groupSpeakerTurns(listOf(
            Turn(-1f, 2f, 0), Turn(Float.NaN, 4f, 0), Turn(2f, 1f, 0), Turn(4f, 8f, 1),
        ), 5f))
    }
}

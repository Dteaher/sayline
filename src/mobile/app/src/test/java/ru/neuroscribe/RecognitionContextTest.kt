package ru.neuroscribe

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.neuroscribe.core.Turn
import ru.neuroscribe.core.recognitionContext

class RecognitionContextTest {
    @Test fun includesNearbyAudioWithoutChangingSpeakerOrModelTurn() {
        val turns = listOf(Turn(1f, 3f, 4))
        assertEquals(Turn(.7f, 3.3f, 4), recognitionContext(turns, 0, 5f))
        assertEquals(Turn(1f, 3f, 4), turns[0])
    }
    @Test fun contextNeverCrossesTheNextOrPreviousSpeakersBoundary() {
        val turns = listOf(Turn(0f, 1f, 0), Turn(1.1f, 2f, 1), Turn(2.1f, 3f, 0))
        assertEquals(Turn(1f, 2.1f, 1), recognitionContext(turns, 1, 3f))
    }
    @Test fun overlapDoesNotExpandIntoAnotherSpeakersInterval() {
        val turns = listOf(Turn(0f, 2f, 0), Turn(1f, 3f, 1))
        assertEquals(Turn(0f, 2f, 0), recognitionContext(turns, 0, 3f))
        assertEquals(Turn(1f, 3f, 1), recognitionContext(turns, 1, 3f))
    }
    @Test fun earlierContainingVoiceBlocksBothSidesDespiteAnInterveningShortTurn() {
        val turns = listOf(Turn(0f, 8f, 0), Turn(1f, 2f, 1), Turn(4f, 5f, 2))
        assertEquals(turns[2], recognitionContext(turns, 2, 10f))
    }
    @Test fun earlierLongVoiceLimitsLeadingContextAfterItEnds() {
        val turns = listOf(Turn(0f, 3.9f, 0), Turn(1f, 2f, 1), Turn(4f, 5f, 2))
        assertEquals(Turn(3.9f, 5.3f, 2), recognitionContext(turns, 2, 10f))
    }
    @Test fun containedInterruptionDoesNotRemoveSafeTrailingSilence() {
        val turns = listOf(Turn(1f, 5f, 0), Turn(2f, 3f, 1), Turn(5.2f, 7f, 2))
        assertEquals(Turn(.7f, 5.2f, 0), recognitionContext(turns, 0, 10f))
    }
    @Test fun boundaryTouchingVoicePreventsAddedContextWithoutTrimmingOriginal() {
        val turns = listOf(Turn(0f, 2f, 0), Turn(2f, 4f, 1), Turn(4f, 6f, 2))
        assertEquals(turns[1], recognitionContext(turns, 1, 6f))
    }
}

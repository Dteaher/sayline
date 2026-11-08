package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.*

class AnchoredRefinementTest {
    @Test fun newCleanEvidenceCannotReclusterExistingReliableVoices() {
        val turns=listOf(Turn(0f,3f,0),Turn(4f,7f,1),Turn(8f,11f,0))
        val original=listOf(floatArrayOf(1f,0f),floatArrayOf(0f,1f),floatArrayOf(.99f,.01f))
        val clean=listOf(floatArrayOf(1f,0f),floatArrayOf(1f,0f),floatArrayOf(1f,0f))
        val result=anchoredRefineSpeakers(turns,original,clean,listOf(3f,3f,3f))
        assertEquals(result[0].speaker,result[2].speaker)
        assertNotEquals(result[0].speaker,result[1].speaker)
    }
    @Test fun clearNewReferenceCanRecoverReturningVoiceFromUnusableMixedTurn() {
        val turns=listOf(Turn(0f,3f,0),Turn(4f,7f,1),Turn(8f,11f,7))
        val baseline=listOf(floatArrayOf(1f,0f),floatArrayOf(0f,1f),null)
        val clean=listOf(floatArrayOf(1f,0f),floatArrayOf(0f,1f),floatArrayOf(.99f,.01f))
        val result=anchoredRefineSpeakers(turns,baseline,clean,listOf(3f,3f,2f))
        assertEquals(result[0].speaker,result[2].speaker)
        assertEquals(2,result.map { it.speaker }.distinct().size)
    }
    @Test fun rareUnmatchedVoiceAndModelDisagreementRemainSeparate() {
        val turns=listOf(Turn(0f,3f,0),Turn(4f,7f,1),Turn(8f,8.3f,7))
        val baseline=listOf(floatArrayOf(1f,0f,0f),floatArrayOf(0f,1f,0f),null)
        val rare=baseline.take(2)+floatArrayOf(0f,0f,1f)
        assertEquals(3,anchoredRefineSpeakers(turns,baseline,rare,listOf(3f,3f,.3f)).map { it.speaker }.distinct().size)
        val clean=baseline.take(2)+floatArrayOf(1f,0f,0f)
        val disagree=listOf(floatArrayOf(1f,0f),floatArrayOf(0f,1f),floatArrayOf(0f,1f))
        assertEquals(3,anchoredRefineSpeakers(turns,baseline,clean,listOf(3f,3f,.3f),disagree).map { it.speaker }.distinct().size)
    }
}

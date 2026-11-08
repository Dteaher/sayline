package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.*

class SpeakerConsensusTest {
    @Test fun eachModelHasEqualInfluenceRegardlessOfScaleAndDimension() {
        val combined=consensusSpeakerEmbedding(floatArrayOf(100f,0f),floatArrayOf(0f,1f,0f))!!
        assertEquals(5,combined.size)
        assertEquals(.5,combined.take(2).sumOf { it.toDouble()*it },1e-6)
        assertEquals(.5,combined.drop(2).sumOf { it.toDouble()*it },1e-6)
    }
    @Test fun invalidOrMissingEvidenceDoesNotPretendToBeConsensus() {
        assertNull(consensusSpeakerEmbedding(null,floatArrayOf(1f)))
        assertNull(consensusSpeakerEmbedding(floatArrayOf(Float.NaN),floatArrayOf(1f)))
        assertNull(consensusSpeakerEmbedding(floatArrayOf(0f),floatArrayOf(1f)))
    }
    @Test fun independentVoiceEvidenceCanPreventErroneousSingleModelMerge() {
        val turns=listOf(Turn(0f,3f,0),Turn(4f,7f,1),Turn(8f,11f,0))
        val vectors=listOf(consensusSpeakerEmbedding(floatArrayOf(1f,0f),floatArrayOf(1f,0f)),
            consensusSpeakerEmbedding(floatArrayOf(1f,0f),floatArrayOf(-1f,0f)),
            consensusSpeakerEmbedding(floatArrayOf(1f,0f),floatArrayOf(.99f,.01f)))
        val result=refineSpeakers(turns,vectors,.6f)
        assertEquals(result[0].speaker,result[2].speaker)
        assertNotEquals(result[0].speaker,result[1].speaker)
    }
}

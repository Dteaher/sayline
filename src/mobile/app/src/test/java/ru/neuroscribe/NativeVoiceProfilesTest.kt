package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.*

class NativeVoiceProfilesTest {
    private val raw=listOf(Turn(0f,3f,0),Turn(4f,7f,0),Turn(8f,11f,1),Turn(12f,15f,1),Turn(20f,21f,7))
    private val a=floatArrayOf(1f,0f,0f);private val b=floatArrayOf(0f,1f,0f)
    private fun reference(window:Int,turn:Turn,v:FloatArray)=NativeVoiceReference(window,0,turn.speaker,v,
        listOf(SpeechSpan(turn.start,turn.end)),listOf(SpeechSpan(turn.start,turn.end)))
    private fun refs(v:FloatArray)=raw.take(4).mapIndexed { i,t->reference(i,t,if(i<2)a else b) }+
        listOf(reference(20,raw.last(),v),reference(21,raw.last(),v))
    @Test fun nativeProfilesRecoverFragmentWithoutAssumingLabelsMatchAfterRefinement() {
        val refined=raw.map { it.copy(speaker=it.speaker+100) }
        val merges=nativeProfileMerges(raw,refined,refs(a))
        assertEquals(107,merges.single().source);assertEquals(100,merges.single().target)
        val result=applyFragmentMerges(refined,merges)
        assertEquals(raw.map { it.start to it.end },result.map { it.start to it.end })
    }
    @Test fun rareAmbiguousAndUnsupportedVoicesRemainSeparate() {
        assertTrue(nativeProfileMerges(raw,raw,refs(floatArrayOf(0f,0f,1f))).isEmpty())
        assertTrue(nativeProfileMerges(raw,raw,refs(floatArrayOf(1f,1f,0f))).isEmpty())
        assertTrue(nativeProfileMerges(raw,raw,refs(a).dropLast(1)).isEmpty())
    }
    @Test fun centeredProfileStillRequiresRawAgreementAndPreservesRareVoices() {
        assertEquals(0,nativeProfileMerges(raw,raw,refs(a),centerProfiles=true).single().target)
        assertTrue(nativeProfileMerges(raw,raw,refs(floatArrayOf(0f,0f,1f)),centerProfiles=true).isEmpty())
        assertTrue(nativeProfileMerges(raw,raw,refs(floatArrayOf(1f,1f,0f)),centerProfiles=true).isEmpty())
    }
    @Test fun contradictoryCleanCoverageCannotAssignAProfileToOneOfTwoGroups() {
        val split=raw.toMutableList().also { it[0]=it[0].copy(end=1.5f);it.add(1,Turn(1.5f,3f,8)) }
        val trace=refs(a)
        val r=nativeProfileMerges(split,split,trace)
        assertTrue(r.none { it.source==8 })
    }
}

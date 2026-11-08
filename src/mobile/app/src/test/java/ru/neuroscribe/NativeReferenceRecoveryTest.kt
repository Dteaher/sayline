package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.*

class NativeReferenceRecoveryTest {
    private fun ref(window:Int,speaker:Int,a:Float,b:Float)=NativeVoiceReference(window,0,speaker,
        floatArrayOf(1f,0f),listOf(SpeechSpan(a,b)),listOf(SpeechSpan(a,b)))
    private val turns=listOf(Turn(0f,4f,0),Turn(0f,4f,1))
    @Test fun twoWindowsRecoverCleanEvidenceWithoutChangingNativeIntervals() {
        val result=nativeReferenceRecovery(turns,listOf(ref(0,0,.5f,3f),ref(1,0,1f,3.5f)))
        assertEquals(Turn(1f,3f,0),result.references[0]);assertNull(result.references[1])
        assertEquals(Turn(0f,4f,0),turns[0])
    }
    @Test fun repeatedSameWindowAndWrongVoiceAreNotIndependentEvidence() {
        assertTrue(nativeReferenceRecovery(turns,listOf(ref(0,0,.5f,3f),ref(0,0,1f,3f))).references.all { it==null })
        assertTrue(nativeReferenceRecovery(turns,listOf(ref(0,0,.5f,3f),ref(1,7,1f,3f))).references.all { it==null })
    }
    @Test fun oldReferencesAndBudgetRemainIntact() {
        val long=(0 until 700).map { Turn(it*3f,it*3f+2f,it%3) }+turns.map { it.copy(start=3000f,end=3004f) }
        val old=anchoredReferencePlan(long)
        val result=nativeReferenceRecovery(long,listOf(ref(0,0,3000f,3004f),ref(1,0,3000f,3004f)))
        assertEquals(old,result)
    }
}

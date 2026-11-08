package ru.neuroscribe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.core.*
import ru.neuroscribe.ml.*

/** Synthetic integration case, not measured speech accuracy: no ERes reference
 * can be selected, but already captured native evidence must remain usable. */
@RunWith(AndroidJUnit4::class)
class NativeProfileFallbackTest {
    @Test fun shortReferencesWithoutTwoAnchorsDoNotRunAnIneffectiveExtractor() = runBlocking {
        val raw=listOf(Turn(0f,.5f,0),Turn(1f,1.5f,1))
        assertEquals(2,anchoredReferencePlan(raw).references.count { it!=null })
        val captured=DiarizationAnalysis(raw,emptyList(),raw,raw,emptyList(),0,0,emptyList())
        val stages=mutableListOf<String>()
        val result=SpeechEngine(InstrumentationRegistry.getInstrumentation().targetContext)
            .refineCapturedNative(FloatArray(2*16000),captured,DiarizationOptions(),{ stages+=it })
        assertEquals(raw,result.refinedTurns)
        assertTrue("Unusable refinement should not run extraction stages",stages.isEmpty())
    }
    @Test fun nativeProfilesAreUsedEvenWhenEveryTurnIsExcludedFromEresExtraction() = runBlocking {
        val raw=listOf(Turn(0f,3f,0),Turn(4f,7f,0),Turn(0f,3f,1),Turn(4f,7f,1),Turn(10f,11f,7),Turn(10f,11f,8))
        assertTrue(anchoredReferencePlan(raw).references.all { it==null })
        val a=floatArrayOf(1f,0f,0f);val b=floatArrayOf(0f,1f,0f)
        fun r(window:Int,turn:Turn,v:FloatArray)=NativeVoiceReference(window,if(turn.speaker==1)1 else 0,
            turn.speaker,v,listOf(SpeechSpan(turn.start,turn.end)),listOf(SpeechSpan(turn.start,turn.end)))
        val refs=listOf(r(0,raw[0],a),r(1,raw[1],a),r(0,raw[2],b),r(1,raw[3],b),r(9,raw[4],a),r(10,raw[4],a))
        val captured=DiarizationAnalysis(raw,emptyList(),raw,raw,emptyList(),0,0,refs)
        val engine=SpeechEngine(InstrumentationRegistry.getInstrumentation().targetContext)
        val result=engine.refineCapturedNative(FloatArray(12*16000),captured,
            DiarizationOptions(useNativeVoiceProfiles=true,centerNativeProfiles=false),{})
        assertEquals(0,result.refinedTurns[4].speaker)
        assertEquals(8,result.refinedTurns[5].speaker)
        assertEquals(raw.map { it.start to it.end },result.refinedTurns.map { it.start to it.end })
    }
}

package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.*

class CleanReferencesTest {
    @Test fun shortInterruptionDoesNotDiscardAllFiveSecondsOfSpeakerEvidence() {
        val turns = listOf(Turn(0f, 5f, 0), Turn(2f, 2.5f, 1))
        val clean = cleanReferenceWindows(turns)
        assertEquals(listOf(Turn(0f, 2f, 0), Turn(2.5f, 5f, 0)), clean[0])
        assertTrue(clean[1].isEmpty())
        assertEquals(turns, listOf(Turn(0f, 5f, 0), Turn(2f, 2.5f, 1)))
    }

    @Test fun overlappingBlockersAreSubtractedAsAUnionAndSameNativeVoiceIsNotABlocker() {
        val turns = listOf(Turn(0f, 10f, 0), Turn(2f, 4f, 1), Turn(3f, 6f, 2), Turn(7f, 8f, 0))
        assertEquals(listOf(Turn(0f, 2f, 0), Turn(6f, 10f, 0)), cleanReferenceWindows(turns)[0])
    }

    @Test fun longCleanSpanUsesSameCenteredTenSecondWindowAsBaseline() {
        assertEquals(listOf(Turn(10f, 20f, 4)), cleanReferenceWindows(listOf(Turn(0f, 30f, 4)))[0])
    }

    @Test fun manyFragmentsKeepLongestCleanContiguousSpanWithoutArtificialSplicing() {
        val turns = listOf(Turn(0f, 20f, 0), Turn(2f, 3f, 1), Turn(5f, 6f, 1), Turn(12f, 13f, 1))
        assertEquals(listOf(Turn(6f, 12f, 0), Turn(13f, 20f, 0)), cleanReferenceWindows(turns)[0])
        assertEquals(Turn(13f, 20f, 0), bestCleanReference(turns, 0))
    }

    @Test fun smallBoundaryOverlapsAreStillExcludedFromVoiceEvidence() {
        val turns = listOf(Turn(0f, 2f, 0), Turn(1.98f, 3f, 1))
        assertEquals(listOf(Turn(0f, 1.98f, 0)), cleanReferenceWindows(turns)[0])
    }

    @Test fun invalidBoundsFailBeforeArraySlicing() {
        assertThrows(IllegalArgumentException::class.java) { cleanReferenceWindows(listOf(Turn(2f, 1f, 0))) }
    }

    @Test fun androidAndHostUseIdenticalIntegerSampleBoundaries() {
        val range=speakerReferenceSamples(Turn(.1234567f,20.987654f,0),400000)
        val start=(.1234567f*16000).toInt();val end=(20.987654f*16000).toInt()
        assertEquals(start+(end-start-160000)/2,range.start)
        assertEquals(range.start+160000,range.end)
        val clipped=speakerReferenceSamples(Turn(0f,3f,0),32000)
        assertEquals(0,clipped.start);assertEquals(32000,clipped.end)
    }

    @Test fun supplementalPlanKeepsOldReferencesAndAddsOnlyRecoveredCleanEvidence() {
        val turns=listOf(Turn(0f,3f,0),Turn(4f,7f,1),Turn(8f,13f,7),Turn(10f,10.5f,1))
        val plan=anchoredReferencePlan(turns)
        assertEquals(setOf(0,1),plan.baselineIndices)
        assertEquals(turns[0],plan.references[0]);assertEquals(turns[1],plan.references[1])
        assertEquals(Turn(10.5f,13f,7),plan.references[2])
        assertNull(plan.references[3])
    }
    @Test fun supplementalPlanSharesOneInferenceBudgetWithBaseline() {
        val turns=(0 until 700).map { Turn(it*3f,it*3f+2f,it%3) }+
            listOf(Turn(3000f,3005f,7),Turn(3002f,3002.5f,1))
        val plan=anchoredReferencePlan(turns)
        assertTrue(plan.references.filterNotNull().size<=256)
        assertTrue(plan.references.filterNotNull().sumOf { minOf(10f,it.end-it.start).toDouble() }<=600)
        assertEquals(selectRefinementSamples(turns).toSet(),plan.baselineIndices)
        assertNull("No supplemental inference outside an exhausted256-sample budget",plan.references[700])
        assertNull(plan.references[701])
    }
}

package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.*

class FragmentEvidenceTest {
    private val base=listOf(Turn(0f,3f,0),Turn(4f,7f,0),Turn(8f,11f,1),Turn(12f,15f,1))
    private val a=floatArrayOf(1f,0f,0f)
    private val b=floatArrayOf(0f,1f,0f)
    private val refs=listOf(a,a,b,b)
    @Test fun clearFragmentMergesWithoutChangingTimes() {
        val turns=base+Turn(20f,20.5f,7)
        val p=fragmentMergeProposals(turns,refs+a,listOf(3f,3f,3f,3f,.5f))
        assertEquals(0,p.single().target)
        val result=applyFragmentMerges(turns,p)
        assertEquals(turns.map { it.start to it.end },result.map { it.start to it.end })
        assertEquals(0,result.last().speaker)
    }
    @Test fun rareAndMissingEvidenceRemainSeparate() {
        val turns=base+Turn(20f,20.5f,7)
        assertTrue(fragmentMergeProposals(turns,refs+floatArrayOf(0f,0f,1f),listOf(3f,3f,3f,3f,.5f)).isEmpty())
        assertTrue(fragmentMergeProposals(turns,refs+listOf(null),listOf(3f,3f,3f,3f,.5f)).isEmpty())
    }
    @Test fun overlapWithTargetVetoesMerge() {
        assertTrue(fragmentMergeProposals(base+Turn(1f,2f,7),refs+a,listOf(3f,3f,3f,3f,1f)).isEmpty())
    }
    @Test fun simultaneousFragmentsCannotBothCollapseIntoSameCore() {
        val turns=base+listOf(Turn(20f,21f,7),Turn(20.3f,21.3f,8))
        assertTrue(fragmentMergeProposals(turns,refs+listOf(a,a),listOf(3f,3f,3f,3f,1f,1f)).isEmpty())
    }
}

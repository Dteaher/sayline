package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.ml.DiarizationOptions

class DiarizationOptionsTest {
    @Test fun productionDefaultsMatchFrozenEvaluatedCandidate() {
        val options = DiarizationOptions()
        assertEquals(.15f, options.minDurationOn, 0f)
        assertEquals(.6f, options.refinementThreshold, 0f)
        assertEquals(.4f, options.minDurationOff, 0f)
        assertEquals(.5f, options.nativeClusteringThreshold, 0f)
        assertEquals(1.2f, options.groupingGap, 0f)
        assertTrue(options.useCleanReferences)
        assertTrue(options.preserveReferenceAnchors)
        assertFalse(options.reconcileFragments)
        assertTrue(options.useNativeVoiceProfiles)
        assertFalse(options.recoverNativeReferences)
        assertFalse(options.collectNativeConfidence)
    }
}

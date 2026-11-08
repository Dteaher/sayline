package ru.neuroscribe.ml

import ru.neuroscribe.core.SpeakerEvidence
import ru.neuroscribe.core.Turn
import ru.neuroscribe.core.NativeVoiceReference

/** Experimental settings are caller-controlled; production keeps measured defaults. */
data class DiarizationOptions(
    val minDurationOn: Float = .15f,
    val minDurationOff: Float = .4f,
    val refinementThreshold: Float = .6f,
    val nativeClusteringThreshold: Float = .5f,
    val useCleanReferences: Boolean = true,
    val preserveReferenceAnchors: Boolean = true,
    val reconcileFragments: Boolean = false,
    val groupingGap: Float = 1.2f,
    val collectNativeConfidence: Boolean = false,
    val collectFinalEvidence: Boolean = false,
    val collectNativeEvidence: Boolean = false,
    val recoverNativeReferences: Boolean = false,
    val useNativeVoiceProfiles: Boolean = true,
    val centerNativeProfiles: Boolean = false,
) {
    init {
        require(minDurationOn.isFinite() && minDurationOn in 0f..1f)
        require(minDurationOff.isFinite() && minDurationOff in 0f..1f)
        require(refinementThreshold.isFinite() && refinementThreshold in 0f..2f)
        require(nativeClusteringThreshold.isFinite() && nativeClusteringThreshold in 0f..1f)
        require(groupingGap.isFinite() && groupingGap in 0f..2f)
    }
}

data class DiarizationAnalysis(
    val rawTurns: List<Turn>,
    val nativeConfidence: List<Float?>,
    val refinedTurns: List<Turn>,
    val turns: List<Turn>,
    val finalEvidence: List<SpeakerEvidence>,
    val nativeMilliseconds: Long,
    val refinementMilliseconds: Long,
    val nativeReferences: List<NativeVoiceReference> = emptyList(),
) // Window embeddings/activity are retained; full powerset posteriors are not exposed.

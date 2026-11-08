package ru.neuroscribe.ml

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.neuroscribe.core.*

class SpeechEngine(private val context: Context, private val requestedThreads: Int = if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") 4 else 2) {
    init { require(requestedThreads in 1..4) { "Inference thread budget must be between 1 and 4" } }
    private fun threadBudget() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1).coerceAtMost(requestedThreads)

    suspend fun transcribe(samples: FloatArray, stage: (String) -> Unit): List<Replica> {
        return transcribe(samples, DiarizationOptions(), stage)
    }

    suspend fun transcribe(samples: FloatArray, options: DiarizationOptions, stage: (String) -> Unit): List<Replica> {
        val turns = diarize(samples, stage, options).turns
        if (turns.isEmpty()) return emptyList()
        return recognizeTurns(samples, turns, stage)
    }

    suspend fun diarize(samples: FloatArray, stage: (String) -> Unit,
                        options: DiarizationOptions = DiarizationOptions()): DiarizationAnalysis {
        require(samples.isNotEmpty() && samples.size <= AudioLimits.MAX_SAMPLES && samples.all { it.isFinite() }) {
            "Некорректное аудио. ${AudioLimits.DURATION_ERROR}"
        }
        currentCoroutineContext().ensureActive()
        val needed = listOf("gigaam-v3.int8.onnx", "tokens.txt", "segmentation.onnx", "speaker.onnx", "speaker-refinement.onnx")
        val available = context.assets.list("models")?.toSet() ?: emptySet()
        check(available.containsAll(needed)) { "В APK отсутствуют модели. Выполните scripts/bootstrap.py models и пересоберите приложение." }
        val threads = threadBudget()
        stage("Определяем говорящих")
        val nativeStarted = SystemClock.elapsedRealtime()
        var confidence = emptyList<Float?>()
        var nativeReferences = emptyList<NativeVoiceReference>()
        val diarizer = OfflineSpeakerDiarization(context.assets, OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig("models/segmentation.onnx"), numThreads = threads),
            embedding = SpeakerEmbeddingExtractorConfig(model = "models/speaker.onnx", numThreads = threads),
            clustering = FastClusteringConfig(numClusters = -1, threshold = options.nativeClusteringThreshold,
                computeConfidence = options.collectNativeConfidence),
            minDurationOn = options.minDurationOn, minDurationOff = options.minDurationOff,
        ))
        val rawTurns = try {
            check(diarizer.sampleRate() == 16000) { "Модель ожидает другую частоту аудио." }
            // Pinned JNI expects a specialized callback signature incompatible with Kotlin indy lambdas.
            // Keep the verified entry point; cancellation is observed immediately after the native call.
            if (options.collectNativeEvidence || options.recoverNativeReferences || options.useNativeVoiceProfiles) {
                val trace = decodeNativeDiarizationTrace(NeuroEvidence.process(diarizer, samples), samples.size / 16000f)
                confidence = trace.confidence
                nativeReferences = trace.references
                trace.turns
            } else {
            val result = diarizer.process(samples)
            currentCoroutineContext().ensureActive()
            val segments = result.mapNotNull { segment ->
                val turn = prepareTurns(listOf(Turn(segment.start, segment.end, segment.speaker)), samples.size / 16000f).singleOrNull()
                turn?.let { it to segment.confidence.takeIf { value -> value.isFinite() && value in -1f..1f } }
            }.sortedBy { it.first.start }
            confidence = segments.map { it.second }
            segments.map { it.first }
            }
        } finally { diarizer.release() }
        val nativeMilliseconds = SystemClock.elapsedRealtime() - nativeStarted
        val refineStarted = SystemClock.elapsedRealtime()
        val (refined, evidence) = refineTurns(samples, rawTurns, threads, stage, options, nativeReferences)
        val turns = groupSpeakerTurns(refined, samples.size / 16000f, options.groupingGap)
        return DiarizationAnalysis(rawTurns, confidence, refined, turns, evidence, nativeMilliseconds,
            SystemClock.elapsedRealtime() - refineStarted, nativeReferences)
    }

    /** Research re-refinement of the same decoded recording, with no repeated segmentation. */
    internal suspend fun refineCapturedNative(samples: FloatArray, captured: DiarizationAnalysis,
        options: DiarizationOptions, stage: (String) -> Unit): DiarizationAnalysis {
        require(samples.isNotEmpty() && samples.size <= AudioLimits.MAX_SAMPLES)
        val started=SystemClock.elapsedRealtime()
        val (refined,evidence)=refineTurns(samples,captured.rawTurns,
            threadBudget(),stage,options,captured.nativeReferences)
        return captured.copy(refinedTurns=refined,turns=groupSpeakerTurns(refined,samples.size/16000f,options.groupingGap),
            finalEvidence=evidence,nativeMilliseconds=0,refinementMilliseconds=SystemClock.elapsedRealtime()-started)
    }

    private suspend fun refineTurns(samples: FloatArray, turns: List<Turn>, threads: Int, stage: (String) -> Unit,
                                    options: DiarizationOptions, nativeReferences: List<NativeVoiceReference>): Pair<List<Turn>, List<SpeakerEvidence>> {
        if (turns.size < 2) return turns to emptyList()
        val anchoredPlan=if(options.recoverNativeReferences)nativeReferenceRecovery(turns,nativeReferences)
            else if(options.useCleanReferences && options.preserveReferenceAnchors)anchoredReferencePlan(turns) else null
        val references = anchoredPlan?.references ?: if (options.useCleanReferences) cleanReferenceWindows(turns)
            .map { windows -> windows.maxByOrNull { it.end-it.start } } else turns.map { it }
        val candidates = references.mapIndexedNotNull { i, reference -> reference?.let { i to it } }
        val selected = if(anchoredPlan!=null)candidates.map { it.first }.toSet()
            else selectRefinementSamples(candidates.map { it.second }).map { candidates[it].first }.toSet()
        // Both refinement algorithms return unchanged labels with fewer than
        // two long anchors. Avoid loading/extracting a model that cannot act.
        val canRefine=selected.count { index -> references[index]!!.let { it.end-it.start>=1.5f } }>=2
        if (selected.isEmpty() || (!canRefine && !options.collectFinalEvidence && !options.reconcileFragments)) {
            val nativeRefined=if(options.useNativeVoiceProfiles)applyFragmentMerges(turns,
                nativeProfileMerges(turns,turns,nativeReferences,options.centerNativeProfiles)) else turns
            return nativeRefined to emptyList()
        }
        val coroutineContext = currentCoroutineContext()
        val extractor = SpeakerEmbeddingExtractor(context.assets,
            SpeakerEmbeddingExtractorConfig(model = "models/speaker-refinement.onnx", numThreads = threads))
        return try {
            var processed = 0
            val vectors = turns.mapIndexed { index, turn ->
                currentCoroutineContext().ensureActive()
                if (index !in selected) null else {
                    stage("Уточняем участников · ${++processed} из ${selected.size}")
                    val stream = extractor.createStream()
                    try {
                        val reference = references[index]!!
                        val range = speakerReferenceSamples(reference,samples.size)
                        stream.acceptWaveform(samples.copyOfRange(range.start,range.end),16000)
                        stream.inputFinished()
                        if (extractor.isReady(stream)) usableSpeakerEmbedding(extractor.compute(stream)) else null
                    } finally { stream.release() }
                }
            }
            currentCoroutineContext().ensureActive()
            val referenceDurations=references.map { it?.let { t -> minOf(10f,t.end-t.start) } ?: 0f }
            val refined = if(anchoredPlan!=null)anchoredRefineSpeakers(turns,
                vectors.mapIndexed { i,v->v.takeIf { i in anchoredPlan.baselineIndices } },vectors,referenceDurations,
                checkpoint={coroutineContext.ensureActive()})
            else refineSpeakers(turns,vectors,options.refinementThreshold,
                referenceDurations=if(options.useCleanReferences)referenceDurations else null,
                checkpoint={coroutineContext.ensureActive()})
            val fragmented = if (options.reconcileFragments) applyFragmentMerges(refined,
                fragmentMergeProposals(refined, vectors, referenceDurations)) else refined
            val finalTurns = if (options.useNativeVoiceProfiles) applyFragmentMerges(fragmented,
                nativeProfileMerges(turns,fragmented,nativeReferences,options.centerNativeProfiles)) else fragmented
            finalTurns to if (options.collectFinalEvidence) speakerEvidence(finalTurns, vectors) else emptyList()
        } finally { extractor.release() }
    }

    /** Re-recognizes only the selected interval without changing the recording's speaker clusters. */
    suspend fun recognizeFragment(samples: FloatArray, replica: Replica, stage: (String) -> Unit, suppressNoise: Boolean = false): String {
        require(replica.start.isFinite() && replica.end.isFinite() && replica.start >= 0f &&
            replica.end > replica.start && replica.end <= samples.size / 16000f + .001f)
        return recognizeTurns(samples, listOf(Turn(replica.start, replica.end, replica.speaker)), stage, useContext = false, suppressNoise = suppressNoise).singleOrNull()?.text.orEmpty()
    }

    internal suspend fun recognizeTurns(samples: FloatArray, turns: List<Turn>, stage: (String) -> Unit, useContext: Boolean = true, suppressNoise: Boolean = false): List<Replica> {
        val threads = threadBudget()
        stage("Загружаем модель русской речи")
        val recognizer = OfflineRecognizer(context.assets, OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 64),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = "models/gigaam-v3.int8.onnx"),
                tokens = "models/tokens.txt", numThreads = threads),
        ))
        var denoiser: OfflineSpeechDenoiser? = null
        return try {
            if (suppressNoise) {
                stage("Подавляем фоновый шум в выбранной реплике")
                denoiser = OfflineSpeechDenoiser(context.assets, OfflineSpeechDenoiserConfig(
                    model = OfflineSpeechDenoiserModelConfig(gtcrn = OfflineSpeechDenoiserGtcrnModelConfig("models/gtcrn.onnx"), numThreads = threads)))
            }
            val result = mutableListOf<Replica>()
            turns.forEachIndexed { index, turn ->
                currentCoroutineContext().ensureActive()
                stage("Распознаём речь · ${index + 1} из ${turns.size}")
                val windows = recognitionWindows(if (useContext) recognitionContext(turns, index, samples.size / 16000f) else turn, samples.size)
                val text = windows.mapIndexed { windowIndex, window ->
                    currentCoroutineContext().ensureActive()
                    if (windows.size > 1) stage("Распознаём речь · ${index + 1} из ${turns.size} · фрагмент ${windowIndex + 1} из ${windows.size}")
                    val stream = recognizer.createStream()
                    try {
                        val input = samples.copyOfRange(window.startSample, window.endSample)
                        val signal = denoiser?.run(input, 16000)?.samples ?: input
                        normalizeQuietSpeech(signal)
                        stream.acceptWaveform(signal, 16000)
                        recognizer.decode(stream)
                        val recognized = recognizer.getResult(stream)
                        currentCoroutineContext().ensureActive()
                        if (windows.size == 1) recognized.text.trim()
                        else recognizedWindowText(recognized.tokens, recognized.timestamps, window)
                    } finally { stream.release() }
                }.filter { it.isNotBlank() }.joinToString(" ")
                if (text.isNotBlank()) result += Replica(turn.start, turn.end, turn.speaker, text)
            }
            currentCoroutineContext().ensureActive()
            result
        } finally { denoiser?.release(); recognizer.release() }
    }
}

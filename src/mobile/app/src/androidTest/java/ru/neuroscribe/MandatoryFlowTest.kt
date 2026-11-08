package ru.neuroscribe

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.audio.AudioDecoder
import ru.neuroscribe.core.exportJson
import ru.neuroscribe.ml.SpeechEngine
import java.io.File

@RunWith(AndroidJUnit4::class)
class MandatoryFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fixture(name: String): File = File(context.cacheDir, name).also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    @Test fun overlapWithOneClusterDoesNotCrashNativeDiarization() = runBlocking {
        val a = fixture("single.wav")
        val ab = fixture("returning.wav")
        try {
            val voiceA = AudioDecoder().decode(a)
            val all = AudioDecoder().decode(ab)
            val voiceB = all.copyOfRange((14.355f * 16000).toInt(), (26.746f * 16000).toInt())
            val samples = FloatArray(maxOf(voiceA.size, voiceB.size) + 32000)
            voiceA.forEachIndexed { i, value -> samples[i] += value * .5f }
            voiceB.forEachIndexed { i, value -> samples[i + 16000] += value * .5f }
            val diarizer = OfflineSpeakerDiarization(context.assets, OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig("models/segmentation.onnx"), numThreads = 2),
                embedding = SpeakerEmbeddingExtractorConfig(model = "models/speaker.onnx", numThreads = 2),
                clustering = FastClusteringConfig(numClusters = 1, threshold = .5f), minDurationOn = .3f, minDurationOff = .4f))
            try {
                val turns = diarizer.process(samples)
                assertTrue(turns.isNotEmpty())
                assertEquals(setOf(0), turns.map { it.speaker }.toSet())
            } finally { diarizer.release() }
        } finally { a.delete(); ab.delete() }
    }

    @Test fun humanSpeechImportProcessingPersistenceAndJsonExport() {
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.INTERNET))
        val audio = fixture("human-returning.wav")
        val output = File(context.cacheDir, "mandatory-flow-export.json").apply { delete() }
        lateinit var vm: NeuroViewModel
        instrumentation.runOnMainSync {
            vm = NeuroViewModel(context.applicationContext as Application)
            vm.import(Uri.fromFile(audio))
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 180_000
            while (SystemClock.elapsedRealtime() < deadline && vm.state.value.stage != null) Thread.sleep(100)
            val state = vm.state.value
            assertNull("Pipeline error: ${state.error}", state.error)
            assertNull("Processing timed out", state.stage)
            val result = requireNotNull(state.selected)
            assertEquals("Готово", result.status)
            assertTrue(result.replicas.isNotEmpty())
            assertTrue(result.replicas.all { it.start >= 0 && it.end > it.start && it.end <= result.duration })
            assertTrue(result.replicas.any { it.text.contains("Японии", ignoreCase = true) })
            assertTrue("Expected genuine model punctuation", result.replicas.any { r -> r.text.any { it in ".,!?" } })
            val windows = listOf(0f to 11.82f, 12.82f to 26.5f, 27.5f to 39.32f)
            val ids = windows.map { (start, end) -> result.replicas.groupBy { it.speaker }
                .mapValues { (_, turns) -> turns.sumOf { maxOf(0f, minOf(it.end, end) - maxOf(it.start, start)).toDouble() } }
                .filterValues { it > 0 }.maxByOrNull { it.value }?.key }
            assertNotNull(ids[0]); assertNotNull(ids[1]); assertEquals(ids[0], ids[2]); assertNotEquals(ids[0], ids[1])
            val saved = vm.store.list().first { it.id == result.id }
            assertEquals(result, saved)
            val exported = exportJson(result.replicas)
            instrumentation.runOnMainSync { vm.export(Uri.fromFile(output), exported) }
            val exportDeadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < exportDeadline && output.length() == 0L) Thread.sleep(50)
            assertEquals(exported, output.readText())
            val json = JSONObject(output.readText())
            assertEquals(setOf("transcript"), json.keys().asSequence().toSet())
            val transcript = json.getJSONArray("transcript")
            assertEquals(result.replicas.size, transcript.length())
            assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), transcript.getJSONObject(0).keys().asSequence().toSet())
            val evidence = File(context.filesDir, "qa-evidence").apply { mkdirs() }
            File(evidence, "human-returning.json").writeText(exported)
            File(evidence, "human-runtime.json").writeText(JSONObject()
                .put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("abi", android.os.Build.SUPPORTED_ABIS.first()).put("audio_seconds", result.duration.toDouble())
                .put("processing_ms", result.processingMs).put("rtf", result.processingMs / 1000.0 / result.duration)
                .put("dominant_speakers", ids.joinToString(",")).put("fixture", "FLEURS concatenated read speech, CC-BY-4.0; not a natural conversation")
                .toString(2))
        } finally { audio.delete(); output.delete() }
    }

    @Test fun corruptImportShowsErrorAndDoesNotCreateTranscript() {
        val file = File(context.cacheDir, "corrupt-audio.bin").apply { writeText("not audio") }
        lateinit var vm: NeuroViewModel
        instrumentation.runOnMainSync {
            vm = NeuroViewModel(context.applicationContext as Application)
            vm.import(Uri.fromFile(file))
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline && vm.state.value.stage != null) Thread.sleep(50)
            assertNull(vm.state.value.stage)
            assertNotNull(vm.state.value.error)
            assertTrue(vm.state.value.selected?.replicas.orEmpty().isEmpty())
        } finally { file.delete() }
    }

    @Test fun longReplyKeepsRepeatedWordsAcrossRecognitionWindows() = runBlocking {
        val file = fixture("single.wav")
        try {
            val voice = AudioDecoder().decode(file)
            val samples = FloatArray(voice.size * 2)
            voice.copyInto(samples)
            voice.copyInto(samples, voice.size)
            val replicas = SpeechEngine(context).transcribe(samples) {}
            val text = replicas.joinToString(" ") { it.text }.lowercase()
            assertTrue("Both occurrences of проект should survive: $text", Regex("проект").findAll(text).count() >= 2)
            assertTrue("Expected a model reply over20s, got ${replicas.map { it.start to it.end }}", replicas.any { it.end - it.start > 20 })
        } finally { file.delete() }
    }
}

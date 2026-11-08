package ru.neuroscribe

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.audio.AudioDecoder
import ru.neuroscribe.core.Replica
import ru.neuroscribe.data.RecordingStore
import ru.neuroscribe.ml.SpeechEngine
import org.json.JSONObject
import java.io.File

/** Connected tests: they are NOT claimed to have run without a device. */
@RunWith(AndroidJUnit4::class)
class OfflinePipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun applicationHasNoInternetPermissionAndLaunches() {
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.INTERNET))
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }

    @Test fun transcriptPersistsAcrossStoreInstances() {
        val store = RecordingStore(context)
        val record = store.create("Тест сохранения").copy(duration = 3f,
            replicas = listOf(Replica(1f, 2f, 7, "Проверка")), names = mapOf(7 to "Анна"), status = "Готово")
        store.save(record)
        try { assertEquals(record, RecordingStore(context).list().first { it.id == record.id }) }
        finally { File(context.filesDir, "recordings/${record.id}.json").delete() }
    }

    @Test fun silenceDoesNotCreateInventedSpeech() = runBlocking {
        assertTrue(SpeechEngine(context).transcribe(FloatArray(80000)) {}.isEmpty())
    }

    @Test fun interruptedAtomicWriteRecoversPreviousTranscript() {
        val store = RecordingStore(context)
        val record = store.create("Восстановление").copy(status = "Готово")
        store.save(record)
        val base = File(context.filesDir, "recordings/${record.id}.json")
        val backup = File(base.path + ".bak")
        try {
            base.copyTo(backup, overwrite = true)
            base.writeText("interrupted write")
            assertEquals(record, RecordingStore(context).list().first { it.id == record.id })
        } finally { base.delete(); backup.delete() }
    }

    @Test fun syntheticRussianSpeechIsDecodedAndRecognizedLocally() = runBlocking {
        val file = File(context.cacheDir, "single-test.wav")
        try {
            instrumentation.context.assets.open("single.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
            val pcm = AudioDecoder().decode(file)
            assertTrue(pcm.size > 16000)
            val replicas = SpeechEngine(context).transcribe(pcm) {}
            assertTrue(replicas.isNotEmpty())
            assertTrue(replicas.any { it.text.contains("проект", ignoreCase = true) })
            assertTrue(replicas.all { it.start >= 0 && it.end > it.start && it.end <= pcm.size / 16000f })
        } finally { file.delete() }
    }

    @Test fun twoSyntheticVoicesKeepIdentityWhenFirstVoiceReturns() = runBlocking {
        val file = File(context.cacheDir, "returning-test.wav")
        try {
            instrumentation.context.assets.open("returning.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
            val pcm = AudioDecoder().decode(file)
            val replicas = SpeechEngine(context).transcribe(pcm) {}
            val windows = listOf(0f to 13.355f, 14.355f to 26.746f, 27.746f to 39.486f)
            val ids = windows.map { (start, end) ->
                replicas.groupBy { it.speaker }.mapValues { (_, turns) ->
                    turns.sumOf { maxOf(0f, minOf(it.end, end) - maxOf(it.start, start)).toDouble() }
                }.filterValues { it > 0 }.maxByOrNull { it.value }?.key
            }
            assertNotNull(ids[0]); assertNotNull(ids[1]); assertNotNull(ids[2])
            assertEquals(ids[0], ids[2])
            assertNotEquals(ids[0], ids[1])
            assertEquals("Refinement must not create ghost participants", 2, replicas.map { it.speaker }.distinct().size)
        } finally { file.delete() }
    }

    @Test fun selectedFragmentCanUseBundledDenoiserWithoutChangingItsBoundaries() = runBlocking {
        val file = File(context.cacheDir, "denoiser-fragment.wav")
        try {
            instrumentation.context.assets.open("single.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
            val pcm = AudioDecoder().decode(file)
            val random = java.util.Random(20261107)
            val noisy = FloatArray(pcm.size) { i -> (pcm[i] + random.nextGaussian().toFloat() * .012f).coerceIn(-1f, 1f) }
            val replica = Replica(0f, pcm.size / 16000f, 7, "Исходный результат")
            val text = SpeechEngine(context).recognizeFragment(noisy, replica, {}, suppressNoise = true)
            assertTrue(text, text.contains("проект", true))
            assertEquals(7, replica.speaker)
            assertEquals("Исходный результат", replica.text)
        } finally { file.delete() }
    }

    @Test fun recordedFemaleVoicesRemainDistinctWithDifferentReturningUtterances() = actorVoices(false)
    @Test fun recordedFemaleVoicesRemainDistinctWithAddedNoise() = actorVoices(true)

    private fun actorVoices(noise: Boolean) = runBlocking {
        val file = File(context.cacheDir, "actors-female-test.wav")
        try {
            instrumentation.context.assets.open("actors-female.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
            val pcm = AudioDecoder().decode(file)
            if (noise) {
                val rms = kotlin.math.sqrt(pcm.sumOf { (it * it).toDouble() } / pcm.size)
                val random = java.util.Random(20261107)
                for (i in pcm.indices) pcm[i] = (pcm[i] + random.nextGaussian() * rms * .316228).toFloat().coerceIn(-1f, 1f)
            }
            val replicas = SpeechEngine(context).transcribe(pcm) {}
            val windows = listOf(0f to 4.7945f, 5.2945f to 8.9314375f, 9.4314375f to 21.171625f,
                21.671625f to 28.0693125f, 28.5693125f to 41.5365f)
            val ids = windows.map { (start, end) -> replicas.groupBy { it.speaker }.mapValues { (_, turns) ->
                turns.sumOf { maxOf(0f, minOf(it.end, end) - maxOf(it.start, start)).toDouble() }
            }.filterValues { it > 0 }.maxByOrNull { it.value }?.key }
            assertTrue(ids.toString(), ids.all { it != null })
            assertEquals(ids.toString(), ids[0], ids[2]); assertEquals(ids[0], ids[4])
            assertEquals(ids[1], ids[3]); assertNotEquals(ids[0], ids[1])
            assertEquals(2, replicas.map { it.speaker }.distinct().size)
            val report = JSONObject().put("kind", "Recorded actors, composed exchange, not a spontaneous meeting")
                .put("noise", noise).put("dominant_ids", org.json.JSONArray(ids)).put("turns", replicas.size)
                .put("transcript", org.json.JSONArray(replicas.map { it.text }))
            File(context.filesDir, "quality-actors-${if (noise) "noise" else "clean"}.json").writeText(report.toString(2))
        } finally { file.delete() }
    }
}

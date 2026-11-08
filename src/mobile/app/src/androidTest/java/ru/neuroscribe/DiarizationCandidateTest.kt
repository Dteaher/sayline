package ru.neuroscribe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.audio.AudioDecoder
import ru.neuroscribe.ml.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiarizationCandidateTest {
    @Test fun selectedSettingsPreserveRussianWordsAndVoiceCountOnFixedRegressionFixtures() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val evidence = JSONArray()
        fun words(text: String) = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase().replace('ё','е')).map { it.value }.toList()
        for (asset in listOf("single.wav", "returning.wav", "human-returning.wav", "actors-female.wav")) {
            val file = File(context.cacheDir, "candidate-regression.wav")
            val pcm = try {
                instrumentation.context.assets.open(asset).use { src -> file.outputStream().use { src.copyTo(it) } }
                AudioDecoder().decode(file)
            } finally { file.delete() }
            val engine = SpeechEngine(context)
            val baseline = engine.transcribe(pcm, DiarizationOptions(minDurationOn=.3f, refinementThreshold=.5f,
                useCleanReferences=false, preserveReferenceAnchors=false,useNativeVoiceProfiles=false)) {}
            val candidate = engine.transcribe(pcm, DiarizationOptions(minDurationOn=.15f, refinementThreshold=.6f)) {}
            assertEquals(asset, words(baseline.joinToString(" ") { it.text }), words(candidate.joinToString(" ") { it.text }))
            assertEquals(asset, baseline.map { it.speaker }.distinct().size, candidate.map { it.speaker }.distinct().size)
            evidence.put(JSONObject().put("asset",asset).put("baseline",JSONArray(baseline.map { it.text }))
                .put("candidate",JSONArray(candidate.map { it.text })).put("speakers",candidate.map { it.speaker }.distinct().size))
        }
        File(context.filesDir,"diarization-russian-regression.json").writeText(JSONObject()
            .put("scope","Four fixed Russian fixtures, not unseen natural conversations")
            .put("baseline_on",.3).put("candidate_on",.15).put("candidate_threshold",.6).put("cases",evidence).toString(2))
    }
}

package ru.neuroscribe

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.audio.AudioDecoder
import java.io.File

/** Explicit opt-in evaluation; candidate weights live outside the production APK. */
@RunWith(AndroidJUnit4::class)
class StackComparisonTest {
    @Test fun compareLocalAsrCandidate() = runBlocking {
        val candidate = InstrumentationRegistry.getArguments().getString("asr_candidate")
        org.junit.Assume.assumeTrue("Supply asr_candidate=ctc/rnnt to run the model evaluation", candidate != null)
        val model = requireNotNull(candidate)
        require(model == "ctc" || model == "rnnt")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "stack-human.wav")
        instrumentation.context.assets.open("human-returning.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        val pcm = try { AudioDecoder().decode(file) } finally { file.delete() }
        val baselinePss = Debug.getPss()
        val start = SystemClock.elapsedRealtime()
        val folder = File(context.filesDir, "stack-eval")
        val config = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16000, featureDim = 64),
            modelConfig = if (model == "ctc") OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig("models/gigaam-v3.int8.onnx"), tokens = "models/tokens.txt", numThreads = 2)
            else OfflineModelConfig(transducer = OfflineTransducerModelConfig(
                encoder = File(folder, "encoder.int8.onnx").path, decoder = File(folder, "decoder.onnx").path,
                joiner = File(folder, "joiner.onnx").path), tokens = File(folder, "tokens.txt").path,
                modelType = "nemo_transducer", numThreads = 2))
        val rec = OfflineRecognizer(if (model == "ctc") context.assets else null, config)
        val report = JSONObject().put("model", model).put("load_ms", SystemClock.elapsedRealtime()-start)
            .put("baseline_pss_kib", baselinePss).put("loaded_pss_kib", Debug.getPss())
            .put("environment", "Android15 x86_64 emulator, CPU2threads; not ARM")
        val cases = JSONArray()
        try {
            for ((name, range) in listOf("human_male" to (0 until 189120), "human_female" to (205120 until 424000))) {
                val audio = pcm.copyOfRange(range.first, range.last+1)
                val times = mutableListOf<Long>()
                var text = ""
                repeat(3) {
                    val stream = rec.createStream()
                    try {
                        val began = SystemClock.elapsedRealtime()
                        stream.acceptWaveform(audio,16000);rec.decode(stream)
                        text = rec.getResult(stream).text
                        times += SystemClock.elapsedRealtime()-began
                    } finally { stream.release() }
                }
                assertTrue(text.isNotBlank())
                cases.put(JSONObject().put("id",name).put("text",text).put("audio_seconds",audio.size/16000.0)
                    .put("median_ms",times.sorted()[1]).put("pss_after_kib",Debug.getPss()))
            }
        } finally { rec.release() }
        report.put("cases",cases)
        val evidence=File(context.filesDir,"qa-evidence").apply { mkdirs() }
        File(evidence,"asr-$model.json").writeText(report.toString(2))
    }
}

package ru.neuroscribe

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.audio.AudioDecoder
import ru.neuroscribe.core.resample
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.*

/** Build with -PseparationResearch=true. Model is private QA data, never production assets. */
@RunWith(AndroidJUnit4::class)
class SeparationCandidateTest {
    @Test fun twoOverlappingVoicesAreSeparatedByRealOnnxInference() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("separation_candidate") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = File(context.filesDir, "qa-separation.onnx")
        assertTrue(model.isFile)
        val digest = model.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        assertEquals("e7c682ac986efe7136407b8f8bbd093b514f95aca1d2735a3945b40b3470bfb6", digest)
        val files = listOf("single.wav", "returning.wav").map { name -> File(context.cacheDir, "sep-$name").also { f ->
            instrumentation.context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        } }
        try {
            val a = resample(AudioDecoder().decode(files[0]), 16000, 8000).copyOfRange(2400, 18400)
            val allB = AudioDecoder().decode(files[1])
            val b = resample(allB.copyOfRange((14.355f * 16000).toInt(), (26.746f * 16000).toInt()), 16000, 8000).copyOfRange(2400, 18400)
            val gain = sqrt(a.sumOf { (it * it).toDouble() } / b.sumOf { (it * it).toDouble() }).toFloat()
            for (i in b.indices) b[i] *= gain
            val mix = FloatArray(16008) { i -> if (i < a.size) (a[i] + b[i]) * .5f else 0f }
            val environment = OrtEnvironment.getEnvironment()
            val started = SystemClock.elapsedRealtime()
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                environment.createSession(model.absolutePath, options).use { session ->
                    val loaded = SystemClock.elapsedRealtime()
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(mix), longArrayOf(1, mix.size.toLong())).use { tensor ->
                        session.run(mapOf("mix_sig" to tensor)).use { output ->
                            @Suppress("UNCHECKED_CAST")
                            val streams = (output[0].value as Array<Array<FloatArray>>)[0]
                            assertEquals(2, streams.size)
                            val one = streams[0].copyOf(a.size); val two = streams[1].copyOf(a.size)
                            val direct = siSdr(one, a) + siSdr(two, b)
                            val reverse = siSdr(two, a) + siSdr(one, b)
                            val best = if (direct >= reverse) listOf(siSdr(one, a), siSdr(two, b)) else listOf(siSdr(two, a), siSdr(one, b))
                            val baseline = listOf(siSdr(mix.copyOf(a.size), a), siSdr(mix.copyOf(a.size), b))
                            val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                            val report = JSONObject().put("kind", "Android x86_64, two-second balanced TTS mixture; research only")
                                .put("load_ms", loaded - started).put("inference_ms", SystemClock.elapsedRealtime() - loaded)
                                .put("output_sisdr", JSONArray(best)).put("input_sisdr", JSONArray(baseline))
                                .put("post_inference_pss_kib", memory.totalPss)
                            File(context.filesDir, "quality-separation-result.json").writeText(report.toString(2))
                            assertTrue(report.toString(), best.indices.all { best[it] > baseline[it] })
                        }
                    }
                }
            }
        } finally { files.forEach(File::delete) }
    }

    private fun siSdr(x: FloatArray, y: FloatArray): Double {
        val mx = x.average(); val my = y.average()
        var xy = 0.0; var yy = 0.0
        for (i in x.indices) { xy += (x[i] - mx) * (y[i] - my); yy += (y[i] - my).pow(2) }
        val scale = xy / (yy + 1e-9)
        var signal = 0.0; var error = 0.0
        for (i in x.indices) { val target = scale * (y[i] - my); signal += target * target; error += (x[i] - mx - target).pow(2) }
        return 10 * log10((signal + 1e-9) / (error + 1e-9))
    }
}

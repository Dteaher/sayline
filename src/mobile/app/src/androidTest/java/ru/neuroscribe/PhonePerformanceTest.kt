package ru.neuroscribe

import android.os.SystemClock
import android.os.Build
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
import ru.neuroscribe.core.exportJson
import ru.neuroscribe.ml.SpeechEngine
import java.io.File

/** Explicit device benchmark; skipped in routine suites. Uses actual bundled models. */
@RunWith(AndroidJUnit4::class)
class PhonePerformanceTest {
    @Test fun compareThreadBudgetsWithoutChangingWordsOrSpeakers() = runBlocking {
        val i=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("phone_benchmark")=="true")
        val context=i.targetContext
        val asset=InstrumentationRegistry.getArguments().getString("benchmark_asset")?:"human-returning.wav"
        val file=File(context.cacheDir,"phone-performance.wav")
        val report=JSONArray()
        try {
            i.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
            val pcm=AudioDecoder().decode(file)
            var expected:String?=null
            for(threads in listOf(2,4,4,2)) {
                val phases=JSONArray()
                val began=SystemClock.elapsedRealtime()
                val replicas=SpeechEngine(context,threads).transcribe(pcm) { stage ->
                    phases.put(JSONObject().put("stage",stage).put("elapsed_ms",SystemClock.elapsedRealtime()-began))
                }
                val elapsed=SystemClock.elapsedRealtime()-began
                val output=exportJson(replicas)
                val row=JSONObject().put("threads",threads).put("elapsed_ms",elapsed)
                    .put("audio_seconds",pcm.size/16000.0).put("stages",phases).put("output",JSONObject(output))
                report.put(row)
                File(context.filesDir,"phone-performance.json").writeText(JSONObject()
                    .put("asset",asset).put("device",Build.MODEL).put("abi",Build.SUPPORTED_ABIS.first())
                    .put("scope","Fixed concatenated Russian read speech; not natural conversation accuracy")
                    .put("runs",report).toString(2))
                assertTrue(replicas.isNotEmpty())
                assertEquals(2,replicas.map { it.speaker }.distinct().size)
                if(expected==null)expected=output else assertEquals("Thread budget must preserve exported transcript",expected,output)
            }
        } finally { file.delete() }
    }
}

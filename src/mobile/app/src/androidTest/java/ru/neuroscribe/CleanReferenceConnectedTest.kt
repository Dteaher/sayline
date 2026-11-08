package ru.neuroscribe

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.audio.AudioDecoder
import ru.neuroscribe.core.exportJson
import ru.neuroscribe.ml.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class CleanReferenceConnectedTest {
    @Test fun cleanSamplesDoNotLoseRussianWordsOrReturningSpeakers() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val report=JSONArray()
        val nativeThreshold=InstrumentationRegistry.getArguments().getString("native_threshold")?.toFloat()?:.5f
        val gap=InstrumentationRegistry.getArguments().getString("grouping_gap")?.toFloat()?:1.2f
        val anchored=InstrumentationRegistry.getArguments().getString("anchored")?.toBoolean()?:DiarizationOptions().preserveReferenceAnchors
        val nativeProfiles=InstrumentationRegistry.getArguments().getString("native_profiles")?.toBoolean()?:DiarizationOptions().useNativeVoiceProfiles
        val centered=InstrumentationRegistry.getArguments().getString("center_profiles")?.toBoolean()?:DiarizationOptions().centerNativeProfiles
        fun words(text:String)=Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase().replace('ё','е')).map { it.value }.toList()
        for(asset in listOf("single.wav","returning.wav","human-returning.wav","actors-female.wav")) {
            val file=File(context.cacheDir,"clean-reference-regression.wav")
            val pcm=try {
                instrumentation.context.assets.open(asset).use { src -> file.outputStream().use { src.copyTo(it) } }
                AudioDecoder().decode(file)
            } finally { file.delete() }
            val engine=SpeechEngine(context)
            val baselineOptions=DiarizationOptions(useCleanReferences=false,nativeClusteringThreshold=.5f,groupingGap=1.2f,useNativeVoiceProfiles=false)
            val candidateOptions=DiarizationOptions(useCleanReferences=true,nativeClusteringThreshold=nativeThreshold,groupingGap=gap,preserveReferenceAnchors=anchored,useNativeVoiceProfiles=nativeProfiles,centerNativeProfiles=centered)
            val began=SystemClock.elapsedRealtime()
            val baseline=engine.transcribe(pcm,baselineOptions){}
            val baselineMs=SystemClock.elapsedRealtime()-began
            val start=SystemClock.elapsedRealtime()
            val candidate=engine.transcribe(pcm,candidateOptions){}
            val candidateMs=SystemClock.elapsedRealtime()-start
            val baselineWords=words(baseline.joinToString(" "){it.text})
            val candidateWords=words(candidate.joinToString(" "){it.text})
            val row=JSONObject().put("asset",asset).put("audio_seconds",pcm.size/16000.0)
                .put("baseline_ms",baselineMs).put("candidate_ms",candidateMs)
                .put("baseline_speakers",baseline.map { it.speaker }.distinct().size)
                .put("candidate_speakers",candidate.map { it.speaker }.distinct().size)
                .put("baseline",JSONObject(exportJson(baseline))).put("candidate",JSONObject(exportJson(candidate)))
                .put("post_inference_pss_kib",Debug.getPss()).put("words_equal",baselineWords==candidateWords)
            report.put(row)
            File(context.filesDir,"cleanrefs-russian-regression.json").writeText(JSONObject()
                .put("scope","Four fixed Russian fixtures; real Android inference, PSS snapshot not peak")
                .put("native_threshold",nativeThreshold).put("grouping_gap",gap).put("anchored",anchored).put("native_profiles",nativeProfiles).put("center_profiles",centered).put("cases",report).toString(2))
            assertEquals(asset,baselineWords,candidateWords)
            assertEquals(asset,baseline.map { it.speaker }.distinct().size,candidate.map { it.speaker }.distinct().size)
        }
    }
}

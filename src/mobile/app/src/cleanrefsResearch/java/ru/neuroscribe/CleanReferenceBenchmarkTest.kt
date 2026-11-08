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
import ru.neuroscribe.core.*
import ru.neuroscribe.ml.*
import java.io.File

/** Explicit research source set, private QA files only; no benchmark audio in APK. */
@RunWith(AndroidJUnit4::class)
class CleanReferenceBenchmarkTest {
    @Test fun evaluatePrivateLicensedCorpusWithActualAndroidPipeline() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val directory=File(context.filesDir,"qa-cleanrefs")
        val manifest=JSONObject(File(directory,"manifest.json").readText())
        val cases=manifest.getJSONArray("cases")
        val result=JSONArray()
        val options=DiarizationOptions(useCleanReferences=true,useNativeVoiceProfiles=false,
            preserveReferenceAnchors=manifest.optBoolean("anchored",false),
            nativeClusteringThreshold=manifest.getDouble("native_threshold").toFloat(),
            groupingGap=manifest.getDouble("grouping_gap").toFloat())
        for(i in 0 until cases.length()) {
            val row=cases.getJSONObject(i)
            val name=row.getString("file")
            require(name==File(name).name && name.endsWith(".wav"))
            val pcm=AudioDecoder().decode(File(directory,name))
            val entry=JSONObject().put("id",row.getString("id")).put("audio_seconds",pcm.size/16000.0)
            if(row.has("reference"))entry.put("reference",row.getString("reference"))
            for((variant,settings) in listOf("baseline" to DiarizationOptions(useCleanReferences=false,nativeClusteringThreshold=.5f,groupingGap=1.2f,useNativeVoiceProfiles=false),"candidate" to options)) {
                val start=SystemClock.elapsedRealtime()
                if(manifest.optString("mode")=="diarization") {
                    val analysis=SpeechEngine(context).diarize(pcm,{},settings)
                    entry.put(variant,JSONArray(analysis.turns.map { t -> JSONArray(listOf(t.start,t.end,t.speaker)) }))
                    entry.put("${variant}_native_ms",analysis.nativeMilliseconds)
                    entry.put("${variant}_refinement_ms",analysis.refinementMilliseconds)
                } else {
                    val transcript=SpeechEngine(context).transcribe(pcm,settings){}
                    entry.put(variant,JSONObject(exportJson(transcript)))
                    assertTrue(transcript.all { it.start>=0 && it.end>it.start && it.end<=pcm.size/16000f+.001f })
                }
                entry.put("${variant}_elapsed_ms",SystemClock.elapsedRealtime()-start)
                entry.put("${variant}_post_pss_kib",Debug.getPss())
            }
            result.put(entry)
            File(context.filesDir,"cleanrefs-corpus-benchmark.json").writeText(JSONObject()
                .put("device",android.os.Build.MODEL).put("abis",JSONArray(android.os.Build.SUPPORTED_ABIS.toList()))
                .put("mode",manifest.optString("mode")).put("native_threshold",options.nativeClusteringThreshold)
                .put("grouping_gap",options.groupingGap).put("memory_scope","Post-inference PSS snapshots, not peak")
                .put("anchored",options.preserveReferenceAnchors)
                .put("cases",result).toString(2))
        }
        assertEquals(cases.length(),result.length())
    }
}

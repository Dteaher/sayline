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
import ru.neuroscribe.core.*
import ru.neuroscribe.ml.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeEvidenceConnectedTest {
    @Test fun recognizeCapturedNaturalCalls() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("natural_asr")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val qa=File(c.filesDir,"qa-native-evidence")
        val manifest=JSONObject(File(qa,"manifest.json").readText()).getJSONArray("cases")
        val report=JSONArray()
        for(n in 0 until manifest.length()) {
            val row=manifest.getJSONObject(n);val id=row.getString("id")
            val samples=AudioDecoder().decode(File(qa,row.getString("file")))
            val captured=JSONObject(File(c.filesDir,"native-profiles-$id.json").readText())
            val intervals=captured.getJSONArray("turns")
            val turns=(0 until intervals.length()).map { k ->
                val t=intervals.getJSONArray(k);Turn(t.getDouble(0).toFloat(),t.getDouble(1).toFloat(),t.getInt(2))
            }
            val start=android.os.SystemClock.elapsedRealtime()
            val replicas=SpeechEngine(c).recognizeTurns(samples,turns,{})
            report.put(JSONObject().put("id",id).put("audio_seconds",samples.size/16000.0)
                .put("asr_ms",android.os.SystemClock.elapsedRealtime()-start)
                .put("diarization_groups",turns.map { it.speaker }.distinct().size)
                .put("recognized_groups",replicas.map { it.speaker }.distinct().size)
                .put("transcript",JSONObject(exportJson(replicas))))
            File(c.filesDir,"natural-russian-asr.json").writeText(JSONObject()
                .put("scope","Actual recorded natural Russian calls, no human text/time reference; no WER/DER claim")
                .put("cases",report).toString(2))
        }
    }
    @Test fun collectingFeaturesDoesNotChangeNativeOrRefinedTurns() = runBlocking {
        val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
        for(asset in listOf("single.wav","actors-female.wav")) {
            val file=File(c.cacheDir,"trace-parity.wav")
            val pcm=try { i.context.assets.open(asset).use { src -> file.outputStream().use { src.copyTo(it) } };AudioDecoder().decode(file) }
                finally { file.delete() }
            val engine=SpeechEngine(c)
            val a=engine.diarize(pcm,{},DiarizationOptions(useNativeVoiceProfiles=false))
            val b=engine.diarize(pcm,{},DiarizationOptions(collectNativeEvidence=true,useNativeVoiceProfiles=false))
            assertEquals(asset,a.rawTurns,b.rawTurns);assertEquals(asset,a.refinedTurns,b.refinedTurns)
            assertEquals(asset,a.turns,b.turns)
            assertTrue(asset,b.nativeReferences.isNotEmpty())
            assertTrue(b.nativeReferences.all { it.clean.isNotEmpty() && it.embedding.all(Float::isFinite) })
            writeTrace(File(c.filesDir,"trace-parity-$asset.json"),asset,pcm.size/16000f,b)
        }
        val empty=SpeechEngine(c).diarize(FloatArray(16000),{},DiarizationOptions(collectNativeEvidence=true))
        assertTrue(empty.turns.isEmpty());assertTrue(empty.nativeReferences.isEmpty())
    }

    @Test fun collectFixedLicensedMeetings() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("trace_cases")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val qa=File(c.filesDir,"qa-native-evidence")
        val manifest=JSONObject(File(qa,"manifest.json").readText()).getJSONArray("cases")
        val threshold=InstrumentationRegistry.getArguments().getString("native_threshold")?.toFloat()?:.5f
        for(n in 0 until manifest.length()) {
            val row=manifest.getJSONObject(n);val name=row.getString("id")
            val samples=AudioDecoder().decode(File(qa,row.getString("file")))
            val result=SpeechEngine(c).diarize(samples,{},DiarizationOptions(collectNativeEvidence=true,useNativeVoiceProfiles=false,nativeClusteringThreshold=threshold))
            assertTrue(result.nativeReferences.isNotEmpty())
            writeTrace(File(c.filesDir,"native-trace-$name.json"),name,samples.size/16000f,result)
            val proposals=nativeProfileMerges(result.rawTurns,result.refinedTurns,result.nativeReferences)
            val profileResult=result.copy(turns=groupSpeakerTurns(applyFragmentMerges(result.refinedTurns,proposals),samples.size/16000f))
            writeTrace(File(c.filesDir,"native-profiles-$name.json"),name,samples.size/16000f,profileResult)
            val centered=nativeProfileMerges(result.rawTurns,result.refinedTurns,result.nativeReferences,centerProfiles=true)
            writeTrace(File(c.filesDir,"native-centered-$name.json"),name,samples.size/16000f,
                result.copy(turns=groupSpeakerTurns(applyFragmentMerges(result.refinedTurns,centered),samples.size/16000f)))
            if (InstrumentationRegistry.getArguments().getString("recover")=="true") {
                val recovered=SpeechEngine(c).refineCapturedNative(samples,result,
                    DiarizationOptions(recoverNativeReferences=true,useNativeVoiceProfiles=false),{})
                writeTrace(File(c.filesDir,"native-recovery-$name.json"),name,samples.size/16000f,recovered)
            }
        }
    }

    private fun writeTrace(file:File,id:String,duration:Float,result:DiarizationAnalysis) {
        fun spans(values:List<SpeechSpan>)=JSONArray(values.map { JSONArray(listOf(it.start,it.end)) })
        fun turns(values:List<Turn>)=JSONArray(values.map { JSONArray(listOf(it.start,it.end,it.speaker)) })
        val refs=JSONArray(result.nativeReferences.map { r -> JSONObject().put("window",r.window)
            .put("local",r.localSpeaker).put("speaker",r.nativeSpeaker).put("embedding",JSONArray(r.embedding.toList()))
            .put("clean",spans(r.clean)).put("active",spans(r.active)) })
        file.writeText(JSONObject().put("id",id).put("duration",duration).put("native_ms",result.nativeMilliseconds)
            .put("native_threshold",InstrumentationRegistry.getArguments().getString("native_threshold")?:"0.5")
            .put("native_linkage",InstrumentationRegistry.getArguments().getString("native_linkage")?:"complete")
            .put("refinement_ms",result.refinementMilliseconds).put("raw",turns(result.rawTurns))
            .put("refined",turns(result.refinedTurns)).put("turns",turns(result.turns)).put("references",refs).toString())
    }
}

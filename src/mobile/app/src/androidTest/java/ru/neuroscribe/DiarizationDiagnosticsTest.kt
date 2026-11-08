package ru.neuroscribe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.audio.AudioDecoder
import ru.neuroscribe.ml.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiarizationDiagnosticsTest {
    @Test fun realJniConfidenceAndFinalStagesAreInspectableWithoutChangingTimes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "diagnostic-actors.wav")
        try {
            instrumentation.context.assets.open("actors-female.wav").use { src -> file.outputStream().use { src.copyTo(it) } }
            val samples = AudioDecoder().decode(file)
            val result = SpeechEngine(context).diarize(samples, {}, DiarizationOptions(collectNativeConfidence = true))
            assertTrue(result.rawTurns.isNotEmpty())
            assertEquals(result.rawTurns.map { it.start to it.end }, result.refinedTurns.map { it.start to it.end })
            assertEquals(result.rawTurns.size, result.nativeConfidence.size)
            assertTrue(result.nativeConfidence.filterNotNull().all { it.isFinite() && it in -1f..1f })
            assertTrue(result.nativeConfidence.any { it != null })
            assertTrue(result.nativeMilliseconds > 0)
            assertTrue(result.refinementMilliseconds > 0)
            assertEquals(2, result.turns.map { it.speaker }.distinct().size)
        } finally { file.delete() }
    }

    @Test fun silenceDiagnosticsDoNotInventParticipantsOrConfidence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = SpeechEngine(context).diarize(FloatArray(80000), {}, DiarizationOptions(collectNativeConfidence = true))
        assertTrue(result.turns.isEmpty()); assertTrue(result.nativeConfidence.isEmpty())
        assertTrue(result.finalEvidence.isEmpty())
    }
}

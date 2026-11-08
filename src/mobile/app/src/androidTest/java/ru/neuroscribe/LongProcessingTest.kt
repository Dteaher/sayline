package ru.neuroscribe

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.audio.AudioDecoder
import ru.neuroscribe.data.RecordingStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicitly opt-in: -e long_audio true. Repeated TTS is a load test, not accuracy evidence. */
@RunWith(AndroidJUnit4::class)
class LongProcessingTest {
    @Test fun twentyMinutesFinishInBackgroundWithScreenOff() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("long_audio") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = RecordingStore(context)
        val record = store.create("20 минут · нагрузочная проверка")
        val source = File(context.cacheDir, "long-source.wav")
        instrumentation.context.assets.open("single.wav").use { input -> source.outputStream().use { input.copyTo(it) } }
        val pcm = AudioDecoder().decode(source)
        val total = 1200 * 16000
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + total * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        header.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(total * 2)
        store.audio(record.id).outputStream().use { output ->
            output.write(header.array())
            val buffer = ByteBuffer.allocate(32000).order(ByteOrder.LITTLE_ENDIAN)
            var written = 0
            while (written < total) {
                buffer.clear()
                repeat(minOf(16000, total - written)) { buffer.putShort((pcm[(written++) % pcm.size] * 32767).toInt().toShort()) }
                output.write(buffer.array(), 0, buffer.position())
            }
        }
        source.delete()
        store.save(record)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val started = SystemClock.elapsedRealtime()
        try {
            lateinit var vm: NeuroViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[NeuroViewModel::class.java]; vm.select(record); vm.retry() }
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
            val deadline = started + 1200000
            while (vm.state.value.stage != null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(500)
            val result = store.list().first { it.id == record.id }
            val report = JSONObject().put("audio_seconds", 1200).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                .put("status", result.status).put("replicas", result.replicas.size).put("last_end", result.replicas.lastOrNull()?.end)
                .put("error", vm.state.value.error).put("kind", "Android x86_64, repeated TTS load test, background + screen off")
            File(context.filesDir, "quality-long-result.json").writeText(report.toString(2))
            assertNull("Timed out: $report", vm.state.value.stage)
            assertEquals(report.toString(), "Готово", result.status)
            assertEquals(1200f, result.duration, .01f)
            assertTrue(report.toString(), result.replicas.last().end > 1190f)
        } finally {
            instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
            scenario.close()
            File(context.filesDir, "recordings/${record.id}.json").delete()
            store.audio(record.id).delete()
        }
    }
}

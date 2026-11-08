package ru.neuroscribe

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.data.RecordingStore
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackgroundProcessingTest {
    @Test fun inferenceFinishesWhileActivityIsBackgroundedAndOriginalIsRestorable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = RecordingStore(context)
        val recording = store.create("Фоновая проверка")
        instrumentation.context.assets.open("single.wav").use { input -> store.audio(recording.id).outputStream().use { input.copyTo(it) } }
        store.save(recording)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var vm: NeuroViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[NeuroViewModel::class.java]; vm.select(recording); vm.retry() }
            val deadline = SystemClock.elapsedRealtime() + 120000
            while (vm.state.value.stage != null && vm.state.value.stage == "Подготавливаем аудио" && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            while (vm.state.value.stage != null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertNull("Background inference timed out: ${vm.state.value.error}", vm.state.value.stage)
            val result = store.list().first { it.id == recording.id }
            assertEquals("Готово", result.status)
            assertTrue(result.replicas.any { it.text.contains("проект", true) })
            val replica = result.replicas.first()
            scenario.onActivity { vm.editTurn(0, replica, "Ручная правка.", replica.speaker) }
            val editDeadline = SystemClock.elapsedRealtime() + 10000
            while (store.list().first { it.id == recording.id }.originalReplicas == null && SystemClock.elapsedRealtime() < editDeadline) Thread.sleep(50)
            val edited = store.list().first { it.id == recording.id }
            assertEquals(result.replicas, edited.originalReplicas)
            assertEquals("Ручная правка.", edited.replicas.first().text)
            scenario.onActivity { vm.recognizeTurn(0, edited.replicas.first()) }
            val retryDeadline = SystemClock.elapsedRealtime() + 120000
            while (vm.state.value.stage != null && SystemClock.elapsedRealtime() < retryDeadline) Thread.sleep(100)
            val recognized = store.list().first { it.id == recording.id }
            assertNull(vm.state.value.error)
            assertEquals(result.replicas, recognized.originalReplicas)
            assertNotEquals("Ручная правка.", recognized.replicas.first().text)
            assertEquals(edited.replicas.first().start, recognized.replicas.first().start, 0f)
            assertEquals(edited.replicas.first().end, recognized.replicas.first().end, 0f)
            assertEquals(edited.replicas.first().speaker, recognized.replicas.first().speaker)
        } finally {
            scenario.close()
            File(context.filesDir, "recordings/${recording.id}.json").delete()
            store.audio(recording.id).delete()
        }
    }
}

package ru.neuroscribe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import ru.neuroscribe.data.RecordingStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecordingStoreDesignTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun waveformBackfillAndRenamingPreserveBothChanges() {
        val store = RecordingStore(context)
        val record = store.create("Тест обновления метаданных")
        store.save(record)
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val rename = executor.submit { start.await(); RecordingStore(context).update(record.id) { it.copy(title = "Новое название", names = mapOf(1 to "Анна")) } }
            val waveform = executor.submit { start.await(); RecordingStore(context).update(record.id) { it.copy(duration = 12f, waveform = listOf(.1f, 1f, .3f)) } }
            start.countDown(); rename.get(10, TimeUnit.SECONDS); waveform.get(10, TimeUnit.SECONDS)
            val updated = store.list().first { it.id == record.id }
            assertEquals("Новое название", updated.title)
            assertEquals("Анна", updated.names[1])
            assertEquals(listOf(.1f, 1f, .3f), updated.waveform)
            assertEquals(12f, updated.duration)
        } finally { executor.shutdownNow(); File(context.filesDir, "recordings/${record.id}.json").delete() }
    }

    @Test fun previousAppVersionRecordLoadsWithoutWaveform() {
        val store = RecordingStore(context)
        val record = store.create("Тест совместимости")
        store.save(record)
        val file = File(context.filesDir, "recordings/${record.id}.json")
        try {
            val old = JSONObject(file.readText()).apply { remove("waveform") }
            file.writeText(old.toString())
            assertEquals(record, store.list().first { it.id == record.id })
        } finally { file.delete() }
    }
}

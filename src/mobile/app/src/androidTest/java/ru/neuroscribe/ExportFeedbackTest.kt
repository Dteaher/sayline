package ru.neuroscribe

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportFeedbackTest {
    @Test fun successfulFileWriteHasAcknowledgementAndCanBeDismissed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(context.cacheDir, "export-feedback.json").apply { delete() }
        lateinit var vm: NeuroViewModel
        instrumentation.runOnMainSync {
            vm = NeuroViewModel(context.applicationContext as Application)
            vm.export(Uri.fromFile(output), "{\"transcript\":[]}")
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (vm.state.value.notice == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            assertEquals("{\"transcript\":[]}", output.readText())
            assertEquals("Файл JSON сохранён", vm.state.value.notice)
            vm.clearNotice()
            assertNull(vm.state.value.notice)
            assertNull(vm.state.value.error)
        } finally { output.delete() }
    }
}

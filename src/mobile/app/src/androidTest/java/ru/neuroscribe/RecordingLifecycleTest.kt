package ru.neuroscribe

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingLifecycleTest {
    @Test fun closingRecorderOwnerDoesNotLeaveProcessRecordingStateStuck() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        instrumentation.uiAutomation.executeShellCommand("pm grant ru.neuroscribe android.permission.RECORD_AUDIO").use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val owner = ViewModelStore()
        lateinit var vm: NeuroViewModel
        try {
            scenario.onActivity {
                vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[NeuroViewModel::class.java]
                vm.startRecording()
            }
            assumeTrue("Emulator recorder unavailable: ${vm.state.value.error}", vm.state.value.recording)
            Thread.sleep(1200)
            scenario.onActivity { owner.clear() }
            assertFalse(vm.state.value.recording)
            assertFalse(vm.state.value.recordingPaused)
            assertTrue(vm.state.value.recordingLevels.isEmpty())
        } finally { scenario.onActivity { owner.clear() }; scenario.close() }
    }
}

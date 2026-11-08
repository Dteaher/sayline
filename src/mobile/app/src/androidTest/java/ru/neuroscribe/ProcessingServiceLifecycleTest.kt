package ru.neuroscribe

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcessingServiceLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Suppress("DEPRECATION")
    private fun foreground() = app.getSystemService(ActivityManager::class.java).getRunningServices(100)
        .any { it.service.className == ProcessingService::class.java.name && it.foreground }

    @Test fun notificationCancelKeepsForegroundProtectionUntilUninterruptibleWorkReturns() = runBlocking {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var job: Job
        instrumentation.runOnMainSync {
            job = ProcessingService.execute(app) { await ->
                await()
                // A controlled non-cancellable region models an in-flight native call, without real model output.
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
            }
        }
        try {
            withTimeout(15000) { entered.await() }
            assertTrue(foreground())
            instrumentation.runOnMainSync { app.startService(Intent(app, ProcessingService::class.java).setAction("ru.neuroscribe.CANCEL_PROCESSING")) }
            withTimeout(10000) { while (!job.isCancelled) delay(20) }
            assertFalse(job.isCompleted)
            assertTrue("Cancellation removed protection while native work was still running", foreground())
            instrumentation.runOnMainSync {
                assertThrows(IllegalStateException::class.java) { ProcessingService.execute(app) { await -> await() } }
            }
        } finally { release.complete(Unit); withTimeout(15000) { job.join() }; scenario.close() }
    }

    @Test fun consecutiveOperationsDoNotCancelTheNewServiceJob() = runBlocking {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            repeat(5) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                lateinit var job: Job
                instrumentation.runOnMainSync {
                    job = ProcessingService.execute(app) { await -> await(); entered.complete(Unit); release.await() }
                }
                try {
                    withTimeout(15000) { entered.await() }
                    delay(100)
                    assertTrue("Previous service teardown cancelled a new operation", job.isActive)
                } finally { release.complete(Unit); withTimeout(15000) { job.join() } }
            }
        } finally { scenario.close() }
    }
}

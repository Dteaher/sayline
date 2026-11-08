package ru.neuroscribe

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import kotlinx.coroutines.*

/** User-started local media processing. No microphone or network is used by this service. */
class ProcessingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var ownedJob: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            active?.cancel()
            // Cancellation waits for JNI to return. Keep foreground protection until cleanup.
            if (active == null || active?.isCompleted == true) stopSelf()
            return START_NOT_STICKY
        }
        if (active?.isActive != true) { stopSelf(); return START_NOT_STICKY }
        ownedJob = active
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Обработка аудио", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val cancel = PendingIntent.getService(this, 1, Intent(this, ProcessingService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Sayline обрабатывает аудио")
                .setContentText("Можно свернуть приложение. Все вычисления на устройстве.")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, "Остановить", cancel).build()).build()
            if (Build.VERSION.SDK_INT >= 35) startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            else if (Build.VERSION.SDK_INT >= 29) startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(41, notification)
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeuroScribe:processing").apply {
                    setReferenceCounted(false)
                    acquire(60 * 60 * 1000L)
                }
            }
            ready?.complete(Unit)
        } catch (e: Exception) {
            ready?.completeExceptionally(e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ownedJob?.cancel(CancellationException("Истекло системное время фоновой обработки"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        ownedJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "local_processing"
        private const val CANCEL = "ru.neuroscribe.CANCEL_PROCESSING"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var ready: CompletableDeferred<Unit>? = null
        @Volatile private var active: Job? = null

        /** The caller's cleanup also runs when service startup fails or cancellation precedes startup. */
        fun execute(app: Application, block: suspend (suspend () -> Unit) -> Unit): Job {
            check(active?.isCompleted != false)
            val gate = CompletableDeferred<Unit>()
            ready = gate
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val owned = currentCoroutineContext().job
                try { block { withTimeout(10000) { gate.await() } } }
                finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (active === owned) {
                            active = null
                            ready = null
                            app.stopService(Intent(app, ProcessingService::class.java))
                        }
                    }
                }
            }
            active = job
            job.start()
            try { app.startForegroundService(Intent(app, ProcessingService::class.java)) }
            catch (e: Exception) { gate.completeExceptionally(e) }
            return job
        }
    }
}

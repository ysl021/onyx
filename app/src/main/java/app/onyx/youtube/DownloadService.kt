package app.onyx.youtube

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.onyx.ui.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the chapter queue alive while the app is in the background. The service
 * itself only mirrors [YouTubeDownloads.state] into a progress notification —
 * all real work happens in the queue object, so a service restart cannot lose
 * finished files.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var workerStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foreground = runCatching {
            startForegroundCompat(buildNotification(0f, tr("Подготовка…", "Preparing…")))
        }.isSuccess
        if (!foreground) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            scope.launch {
                YouTubeDownloads.state.collect { state ->
                    val manager = getSystemService(NotificationManager::class.java) ?: return@collect
                    manager.notify(
                        NOTIFICATION_ID,
                        buildNotification(state.overallProgress, describe(state)),
                    )
                }
            }
        }
        // A second start() must not spawn a second worker: stopping the service
        // from that duplicate would cancel the batch already in flight.
        if (workerStarted) return START_NOT_STICKY
        workerStarted = true
        scope.launch(Dispatchers.IO) {
            // Returns false when another worker (the in-app watchdog) owns the queue;
            // either way this service is done and can hand the notification back.
            YouTubeDownloads.processQueue(applicationContext)
            withContext(Dispatchers.Main.immediate) {
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun describe(state: YouTubeState): String {
        val total = state.jobs.size
        val done = state.finished
        val failed = state.failed
        val running = state.jobs.firstOrNull {
            it.status != JobStatus.Done && it.status != JobStatus.Error
        }
        return when {
            running != null -> tr(
                "${running.index + 1}/$total · ${running.sectionTitle}",
                "${running.index + 1}/$total · ${running.sectionTitle}",
            )
            failed > 0 -> tr("Готово: $done, ошибок: $failed", "Done: $done, failed: $failed")
            else -> tr("Готово: $done", "Done: $done")
        }
    }

    private fun buildNotification(progress: Float, text: String): Notification {
        val intent = Intent(this, app.onyx.MainActivity::class.java)
        val pending = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tr("Загрузка с YouTube", "Downloading from YouTube"))
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (progress * 100).toInt().coerceIn(0, 100), progress <= 0f)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            tr("Загрузки YouTube", "YouTube downloads"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = tr("Скачивание и нарезка треков", "Downloading and splitting tracks")
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "onyx_youtube_downloads"
        private const val NOTIFICATION_ID = 4310

        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            true
        }.getOrDefault(false)
    }
}

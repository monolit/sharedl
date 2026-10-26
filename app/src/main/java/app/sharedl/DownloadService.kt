package app.sharedl

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger(0)
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun progressNotif(text: String, pct: Int = -1): Notification {
        val b = NotificationCompat.Builder(this, "dl")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ShareDL")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (pct >= 0) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        nm.createNotificationChannel(NotificationChannel("dl", "Загрузки", NotificationManager.IMPORTANCE_LOW))
        val n = progressNotif("Загрузка…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)

        val text = intent?.getStringExtra("url").orEmpty()
        active.incrementAndGet()
        scope.launch {
            var msg: String
            try {
                val saved = Downloader.run(this@DownloadService, text, DownloadState::add) { p ->
                    nm.notify(1, progressNotif("Загрузка… ${p.toInt()}%", p.toInt().coerceIn(0, 100)))
                }
                msg = if (saved.isEmpty()) "Не удалось скачать" else "Готово: " + saved.joinToString(", ")
            } catch (e: Exception) {
                msg = "Ошибка: ${e.message?.take(120)}"
                DownloadState.add(msg)
            }
            nm.notify(
                System.currentTimeMillis().toInt(),
                NotificationCompat.Builder(this@DownloadService, "dl")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("ShareDL")
                    .setContentText(msg)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                    .setAutoCancel(true)
                    .build()
            )
            if (active.decrementAndGet() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

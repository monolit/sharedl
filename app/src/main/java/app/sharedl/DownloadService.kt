package app.sharedl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Очередь загрузок: до двух одновременно, у каждой своё уведомление с кнопками
 * «Пауза / Продолжить / Отмена». Успех — только всплывающее сообщение (если не тихий режим),
 * уведомление остаётся лишь при ошибке.
 */
class DownloadService : Service() {
    companion object {
        const val ACTION_ADD = "app.sharedl.ADD"
        const val ACTION_PAUSE = "app.sharedl.PAUSE"
        const val ACTION_RESUME = "app.sharedl.RESUME"
        const val ACTION_CANCEL = "app.sharedl.CANCEL"

        private const val CH_PROGRESS = "dl_progress"
        private const val CH_ERRORS = "dl_errors"
        private const val GROUP = "app.sharedl.GROUP"
        private const val FGS_ID = 1
        private const val JOB_BASE = 1000
        private const val ERR_BASE = 5000

        /** [text] может содержать несколько ссылок (через перевод строки или пробел). */
        fun add(ctx: Context, text: String) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, DownloadService::class.java).setAction(ACTION_ADD).putExtra("text", text)
        )

        fun control(ctx: Context, action: String, id: Int) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, DownloadService::class.java).setAction(action).putExtra("id", id)
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sem = Semaphore(2)
    private val tasks = ConcurrentHashMap<Int, kotlinx.coroutines.Job>()
    private val ui = Handler(Looper.getMainLooper())
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun s(@StringRes id: Int, vararg a: Any) = Loc.ctx(this).getString(id, *a)

    private fun toast(msg: String) = ui.post { Toast.makeText(Loc.ctx(this), msg, Toast.LENGTH_LONG).show() }

    private fun activeCount() = Jobs.list.value.count { it.active }

    // ---------- уведомления ----------

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun actionIntent(action: String, id: Int) = PendingIntent.getService(
        this, id, Intent(this, DownloadService::class.java).setAction(action).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun act(icon: Int, @StringRes title: Int, action: String, id: Int) =
        NotificationCompat.Action(icon, s(title), actionIntent(action, id))

    private fun summary(): Notification = NotificationCompat.Builder(this, CH_PROGRESS)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("ShareDL")
        .setContentText(s(R.string.notif_active, activeCount()))
        .setOngoing(true).setOnlyAlertOnce(true)
        .setGroup(GROUP).setGroupSummary(true)
        .setContentIntent(openApp())
        .build()

    private fun shortName(j: DlJob) = j.title.ifBlank { j.url.removePrefix("https://").removePrefix("http://").take(60) }

    private fun notifyJob(j: DlJob) {
        val b = NotificationCompat.Builder(this, CH_PROGRESS)
            .setContentTitle(shortName(j))
            .setGroup(GROUP).setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        val p = j.progress.coerceIn(0, 100)
        when (j.state) {
            DlState.QUEUED -> b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentText(s(R.string.notif_waiting)).setProgress(0, 0, true).setOngoing(true)
                .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, R.string.btn_cancel, ACTION_CANCEL, j.id))
            DlState.RUNNING -> b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentText(if (j.progress >= 0) s(R.string.state_running_pct, p) else s(R.string.state_running))
                .setProgress(100, p, j.progress < 0).setOngoing(true)
                .addAction(act(android.R.drawable.ic_media_pause, R.string.btn_pause, ACTION_PAUSE, j.id))
                .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, R.string.btn_cancel, ACTION_CANCEL, j.id))
            DlState.PAUSED -> b.setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentText(s(R.string.notif_paused)).setProgress(100, p, false).setOngoing(false)
                .addAction(act(android.R.drawable.ic_media_play, R.string.btn_resume, ACTION_RESUME, j.id))
                .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, R.string.btn_cancel, ACTION_CANCEL, j.id))
            else -> return
        }
        nm.notify(JOB_BASE + j.id, b.build())
    }

    private fun notifyError(j: DlJob, msg: String) {
        nm.notify(
            ERR_BASE + j.id,
            NotificationCompat.Builder(this, CH_ERRORS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(s(R.string.notif_failed))
                .setContentText("${shortName(j)}: $msg")
                .setStyle(NotificationCompat.BigTextStyle().bigText("${shortName(j)}\n$msg"))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .addAction(act(android.R.drawable.ic_popup_sync, R.string.btn_retry, ACTION_RESUME, j.id))
                .build()
        )
    }

    // ---------- жизненный цикл ----------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, s(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_ERRORS, s(R.string.notif_channel_errors), NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 29) startForeground(FGS_ID, summary(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(FGS_ID, summary())

        val id = intent?.getIntExtra("id", -1) ?: -1
        when (intent?.action) {
            ACTION_ADD -> add(intent?.getStringExtra("text").orEmpty())
            ACTION_PAUSE -> pause(id)
            ACTION_RESUME -> resume(id)
            ACTION_CANCEL -> cancel(id)
        }
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        if (activeCount() == 0 && tasks.values.none { it.isActive }) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else nm.notify(FGS_ID, summary())
    }

    // ---------- управление ----------

    private fun add(text: String) {
        val urls = Urls.extract(text)
        if (urls.isEmpty()) { toast(s(R.string.toast_no_links)); return }
        var added = 0
        for (u in urls) {
            if (Jobs.list.value.any { it.url == u && it.open }) continue  // уже качается / на паузе
            val job = Jobs.create(u)
            launchJob(job.id)
            added++
        }
        if (added > 0 && !Prefs.quiet(this)) toast(s(R.string.toast_added, added))
    }

    private fun pause(id: Int) {
        val j = Jobs.get(id) ?: return
        if (!j.active) return
        Jobs.update(id) { it.copy(state = DlState.PAUSED) }
        Downloader.requestStop(id)
        if (j.state == DlState.QUEUED) Jobs.get(id)?.let { notifyJob(it) }  // у RUNNING уведомление обновит исполнитель
    }

    private fun resume(id: Int) {
        val j = Jobs.get(id) ?: return
        if (j.state != DlState.PAUSED && j.state != DlState.FAILED) return
        Downloader.clearStop(id)
        nm.cancel(ERR_BASE + id)
        Jobs.update(id) { it.copy(state = DlState.QUEUED, message = "") }
        launchJob(id)
    }

    private fun cancel(id: Int) {
        val j = Jobs.get(id) ?: return
        if (j.state == DlState.DONE || j.state == DlState.CANCELED) return
        Jobs.update(id) { it.copy(state = DlState.CANCELED) }
        Downloader.requestStop(id)
        nm.cancel(JOB_BASE + id)
        nm.cancel(ERR_BASE + id)
        if (j.state != DlState.RUNNING) cleanup(j)  // у RUNNING почистит исполнитель после остановки
    }

    private fun cleanup(j: DlJob) {
        Downloader.tmpDir(this, j.url).deleteRecursively()
    }

    // ---------- исполнение ----------

    private fun launchJob(id: Int) {
        val prev = tasks[id]
        tasks[id] = scope.launch {
            prev?.join()  // если предыдущая попытка ещё останавливается
            sem.withPermit {
                if (Jobs.get(id)?.state != DlState.QUEUED) return@withPermit
                Jobs.update(id) { it.copy(state = DlState.RUNNING) }
                execute(id)
            }
        }.also { job -> job.invokeOnCompletion { ui.post { refresh() } } }
    }

    private suspend fun execute(id: Int) {
        val job = Jobs.get(id) ?: return
        notifyJob(job)
        var last = -2
        try {
            val res = Downloader.run(
                this, job,
                log = { DownloadState.add(it) },
                progress = { p ->
                    if (p != last) {
                        last = p
                        Jobs.update(id) { it.copy(progress = p) }
                        Jobs.get(id)?.let { notifyJob(it) }
                    }
                },
                title = { t ->
                    Jobs.update(id) { it.copy(title = t) }
                }
            )
            finishOk(id, res)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (Jobs.get(id)?.state) {
                DlState.PAUSED -> Jobs.get(id)?.let { notifyJob(it) }
                DlState.CANCELED -> cleanup(job)
                else -> fail(id, e)
            }
        }
    }

    private fun finishOk(id: Int, res: Downloader.Result) {
        val j = Jobs.get(id) ?: return
        cleanup(j)
        Jobs.update(id) { it.copy(state = DlState.DONE, progress = 100, message = "") }
        nm.cancel(JOB_BASE + id)
        if (Prefs.quiet(this)) return
        when {
            res.saved.isNotEmpty() -> toast(s(R.string.toast_saved, res.saved.joinToString(", ")))
            res.skipped.isNotEmpty() -> toast(s(R.string.toast_exists, res.skipped.joinToString(", ")))
        }
    }

    private fun fail(id: Int, e: Exception) {
        val j = Jobs.get(id) ?: return
        val msg = (e.message ?: e.javaClass.simpleName).lineSequence().lastOrNull { it.isNotBlank() }?.take(200) ?: "error"
        DownloadState.add("✖ $msg")
        cleanup(j)
        Jobs.update(id) { it.copy(state = DlState.FAILED, message = msg) }
        nm.cancel(JOB_BASE + id)
        notifyError(j, msg)
    }
}

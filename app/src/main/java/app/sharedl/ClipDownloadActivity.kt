package app.sharedl

import android.app.Activity
import android.content.ClipboardManager
import android.widget.Toast

/**
 * Прозрачная activity для плитки и ярлыка: читает буфер обмена и запускает загрузку.
 * Android 10+ отдаёт буфер только приложению в фокусе, поэтому читаем в onWindowFocusChanged.
 */
class ClipDownloadActivity : Activity() {
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = if (clip == null) "" else
            (0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(this).toString() }
        if (Urls.extract(text).isEmpty()) {
            Toast.makeText(Loc.ctx(this), R.string.toast_no_links, Toast.LENGTH_SHORT).show()
        } else {
            DownloadService.add(this, text)
        }
        finish()
    }
}

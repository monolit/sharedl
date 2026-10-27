package app.sharedl

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Приёмник Share: прозрачная activity, передаёт ссылки в сервис и сразу закрывается. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (text.isNotBlank()) {
            DownloadService.add(this, text)
            if (Prefs.showUi(this)) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        finish()
    }
}

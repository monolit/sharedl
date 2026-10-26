package app.sharedl

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (text.isBlank()) { finish(); return }

        ContextCompat.startForegroundService(
            this, Intent(this, DownloadService::class.java).putExtra("url", text)
        )
        if (Prefs.showUi(this)) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Toast.makeText(this, "Скачивание начато", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}

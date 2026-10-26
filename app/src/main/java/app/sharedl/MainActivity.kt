package app.sharedl

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var cookiesInfo: TextView

    private val pickCookies = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val n = Cookies.import(this, uris)
            Toast.makeText(this, "Добавлено cookies: $n", Toast.LENGTH_SHORT).show()
            refreshCookies()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(text: String, onClick: () -> Unit) =
        Button(this).apply { this.text = text; setOnClickListener { onClick() } }

    private fun refreshCookies() {
        val d = Cookies.domains(this)
        cookiesInfo.text = if (d.isEmpty()) "Cookies: нет" else "Cookies:\n" + d.entries.joinToString("\n") { "• ${it.key} (${it.value})" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        // --- ручная вставка ссылки
        val urlInput = EditText(this).apply { hint = "Вставьте ссылку"; setSingleLine() }
        root.addView(urlInput)
        root.addView(button("Скачать") {
            val t = urlInput.text.toString()
            if (t.isNotBlank()) {
                ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java).putExtra("url", t))
                urlInput.text.clear()
            }
        })

        // --- настройки
        root.addView(SwitchCompat(this).apply {
            text = "Открывать окно при Share"
            isChecked = Prefs.showUi(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setShowUi(this@MainActivity, v) }
        })

        root.addView(TextView(this).apply { text = "\nФормат аудио" })
        val fmts = arrayOf("m4a", "mp3", "opus")
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, fmts)
            setSelection(fmts.indexOf(Prefs.audioFmt(this@MainActivity)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                    Prefs.setAudioFmt(this@MainActivity, fmts[pos])
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })

        root.addView(TextView(this).apply { text = "\nМаксимальная высота видео" })
        root.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.maxHeight(this@MainActivity).toString())
            doAfterTextChanged { t -> t.toString().toIntOrNull()?.let { Prefs.setMaxHeight(this@MainActivity, it) } }
        })

        root.addView(TextView(this).apply {
            text = "\nДоп. музыкальные домены (через запятую; music.youtube.com, soundcloud.com и др. уже встроены)"
        })
        root.addView(EditText(this).apply {
            setText(Prefs.musicHosts(this@MainActivity))
            doAfterTextChanged { Prefs.setMusicHosts(this@MainActivity, it.toString()) }
        })

        root.addView(TextView(this).apply { text = "\nПрокси (http://… или socks5://…, пусто = без прокси)" })
        root.addView(EditText(this).apply {
            setSingleLine()
            setText(Prefs.proxy(this@MainActivity))
            doAfterTextChanged { Prefs.setProxy(this@MainActivity, it.toString()) }
        })

        root.addView(TextView(this).apply { text = "\nДоп. аргументы yt-dlp (для опытных)" })
        root.addView(EditText(this).apply {
            setText(Prefs.extra(this@MainActivity))
            doAfterTextChanged { Prefs.setExtra(this@MainActivity, it.toString()) }
        })

        // --- cookies
        cookiesInfo = TextView(this).apply { setPadding(0, dp(16), 0, dp(8)) }
        root.addView(cookiesInfo)
        root.addView(button("Импорт cookies.txt (можно несколько)") { pickCookies.launch(arrayOf("*/*")) })
        root.addView(button("Удалить cookies домена") {
            val d = Cookies.domains(this).keys.toTypedArray()
            if (d.isNotEmpty()) AlertDialog.Builder(this).setItems(d) { _, i ->
                Cookies.remove(this, d[i]); refreshCookies()
            }.show()
        })
        root.addView(button("Очистить все cookies") { Cookies.clear(this); refreshCookies() })
        refreshCookies()

        // --- обновление yt-dlp
        root.addView(button("Обновить yt-dlp и gallery-dl") {
            lifecycleScope.launch {
                DownloadState.add("Обновление…")
                val r = withContext(Dispatchers.IO) { try { Downloader.update(this@MainActivity) } catch (e: Exception) { "ошибка: ${e.message}" } }
                DownloadState.add(r)
            }
        })

        // --- лог
        val logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true)
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(logView)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadState.log.collect { logView.text = it }
            }
        }
    }
}

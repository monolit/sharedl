package app.sharedl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var chips: ChipGroup
    private lateinit var cookiesEmpty: TextView

    private val pickCookies = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val n = Cookies.import(this, uris)
            Toast.makeText(this, "Добавлено cookies: $n", Toast.LENGTH_SHORT).show()
            refreshCookies()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, style: Int) = TextView(this).apply { text = s; setTextAppearance(style) }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    // ---- Material 3 «кирпичики» ----

    private fun section(title: String, vararg views: View): MaterialCardView {
        val card = MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewFilledStyle)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(label(title, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium))
        views.forEach { box.addView(it, lp(12)) }
        card.addView(box)
        card.layoutParams = lp(12)
        return card
    }

    private fun field(
        hint: String, value: String, inputType: Int = InputType.TYPE_CLASS_TEXT,
        onChange: (String) -> Unit
    ): TextInputLayout {
        val til = TextInputLayout(this).apply { this.hint = hint }
        til.addView(TextInputEditText(til.context).apply {
            this.inputType = inputType
            setText(value)
            doAfterTextChanged { onChange(it.toString()) }
        })
        return til
    }

    private fun filled(t: String, onClick: () -> Unit) =
        MaterialButton(this).apply { text = t; setOnClickListener { onClick() } }

    private fun outlined(t: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = t; setOnClickListener { onClick() }
        }

    private fun tonal(t: String, onClick: () -> Unit) = filled(t, onClick).also { b ->
        b.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(b, com.google.android.material.R.attr.colorSecondaryContainer))
        b.setTextColor(MaterialColors.getColor(b, com.google.android.material.R.attr.colorOnSecondaryContainer))
    }

    private fun refreshCookies() {
        chips.removeAllViews()
        val d = Cookies.domains(this)
        cookiesEmpty.visibility = if (d.isEmpty()) View.VISIBLE else View.GONE
        d.forEach { (domain, n) ->
            chips.addView(Chip(this).apply {
                text = "$domain · $n"
                isCloseIconVisible = true
                setOnCloseIconClickListener { Cookies.remove(this@MainActivity, domain); refreshCookies() }
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(label("ShareDL", com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium))
        root.addView(
            label("Поделитесь ссылкой — загрузка начнётся сразу",
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium), lp(4)
        )

        // --- ручная вставка ссылки
        var pasted = ""
        val urlField = field("Ссылка", "", InputType.TYPE_TEXT_VARIATION_URI) { pasted = it }
        root.addView(section("Скачать", urlField, filled("Скачать") {
            if (pasted.isNotBlank()) {
                ContextCompat.startForegroundService(
                    this, Intent(this, DownloadService::class.java).putExtra("url", pasted))
                urlField.editText?.text?.clear()
            }
        }))

        // --- поведение
        root.addView(section("Поведение",
            MaterialSwitch(this).apply {
                text = "Открывать окно при Share"
                isChecked = Prefs.showUi(this@MainActivity)
                setOnCheckedChangeListener { _, v -> Prefs.setShowUi(this@MainActivity, v) }
            }))

        // --- аудио
        val fmts = listOf("m4a", "mp3", "opus")
        val group = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        fmts.forEach { f ->
            group.addView(
                MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId(); text = f; tag = f
                })
        }
        group.check(group.getChildAt(fmts.indexOf(Prefs.audioFmt(this)).coerceAtLeast(0)).id)
        group.addOnButtonCheckedListener { g, id, checked ->
            if (checked) Prefs.setAudioFmt(this, g.findViewById<View>(id).tag as String)
        }
        root.addView(section("Аудио (музыкальные сайты)",
            label("Формат", com.google.android.material.R.style.TextAppearance_Material3_BodySmall),
            group,
            field("Доп. музыкальные домены через запятую", Prefs.musicHosts(this)) { Prefs.setMusicHosts(this, it) },
            label("music.youtube.com, soundcloud, bandcamp и др. уже встроены",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall)))

        // --- видео
        root.addView(section("Видео",
            field("Максимальная высота, px", Prefs.maxHeight(this).toString(), InputType.TYPE_CLASS_NUMBER) {
                it.toIntOrNull()?.let { h -> Prefs.setMaxHeight(this, h) }
            }))

        // --- сеть
        root.addView(section("Сеть",
            field("Прокси (http:// или socks5://)", Prefs.proxy(this), InputType.TYPE_TEXT_VARIATION_URI) {
                Prefs.setProxy(this, it)
            },
            field("Доп. аргументы yt-dlp", Prefs.extra(this)) { Prefs.setExtra(this, it) }))

        // --- cookies
        chips = ChipGroup(this)
        cookiesEmpty = label("Cookies не загружены",
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        root.addView(section("Cookies",
            cookiesEmpty, chips,
            outlined("Импорт cookies.txt (можно несколько)") { pickCookies.launch(arrayOf("*/*")) },
            outlined("Очистить все") { Cookies.clear(this); refreshCookies() }))
        refreshCookies()

        // --- обслуживание
        root.addView(section("Обслуживание", tonal("Обновить yt-dlp и gallery-dl") {
            lifecycleScope.launch {
                DownloadState.add("Обновление…")
                val r = withContext(Dispatchers.IO) {
                    try { Downloader.update(this@MainActivity) } catch (e: Exception) { "ошибка: ${e.message}" }
                }
                DownloadState.add(r)
            }
        }))

        // --- журнал
        val logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true)
        }
        root.addView(section("Журнал", logView))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadState.log.collect { logView.text = it }
            }
        }
    }
}

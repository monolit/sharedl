package app.sharedl

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private companion object {
        const val ID_MAIN = 1
        const val ID_LOG = 2
        const val ID_SET = 3
        val LANG_TAGS = listOf("", "en", "ru", "uz", "kk", "tg", "be")
    }

    private val pages = HashMap<Int, View>()
    private var current = ID_MAIN
    private lateinit var urlField: TextInputLayout
    private lateinit var chips: ChipGroup
    private lateinit var cookiesEmpty: TextView
    private lateinit var jobsBox: LinearLayout
    private lateinit var jobsEmpty: TextView
    private val cards = HashMap<Int, JobCard>()

    private val pickCookies = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val n = Cookies.import(this, uris)
            Toast.makeText(this, getString(R.string.toast_cookies_added, n), Toast.LENGTH_SHORT).show()
            refreshCookies()
        }
    }

    // ---------- «кирпичики» Material 3 ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, style: Int) = TextView(this).apply { text = s; setTextAppearance(style) }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

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

    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        MaterialSwitch(this).apply {
            this.text = text
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    private fun page(vararg views: View): ScrollView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        views.forEach { box.addView(it) }
        return ScrollView(this).apply { addView(box) }
    }

    // ---------- карточка загрузки ----------

    private inner class JobCard(val id: Int) {
        val card = MaterialCardView(this@MainActivity, null, com.google.android.material.R.attr.materialCardViewFilledStyle)
        private val title = TextView(this@MainActivity).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            maxLines = 2
        }
        private val status = TextView(this@MainActivity).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        private val bar = LinearProgressIndicator(this@MainActivity).apply { max = 100 }
        private val btnA = outlined("") {}
        private val btnB = outlined("") {}

        init {
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(btnA, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            row.addView(btnB, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            box.addView(title)
            box.addView(status, lp(2))
            box.addView(bar, lp(8))
            box.addView(row, lp(8))
            card.addView(box)
            card.layoutParams = lp(8)
        }

        private fun ctl(button: MaterialButton, text: Int, action: String) {
            button.visibility = View.VISIBLE
            button.setText(text)
            button.setOnClickListener { DownloadService.control(this@MainActivity, action, id) }
        }

        fun bind(j: DlJob) {
            title.text = j.title.ifBlank { j.url }
            val p = j.progress.coerceAtLeast(0)
            status.text = when (j.state) {
                DlState.QUEUED -> getString(R.string.state_queued)
                DlState.RUNNING -> if (j.progress >= 0) getString(R.string.state_running_pct, j.progress) else getString(R.string.state_running)
                DlState.PAUSED -> getString(R.string.state_paused) + " · " + p + "%"
                DlState.DONE -> getString(R.string.state_done)
                DlState.FAILED -> getString(R.string.state_failed) + ": " + j.message
                DlState.CANCELED -> getString(R.string.state_canceled)
            }
            bar.setProgressCompat(if (j.state == DlState.DONE) 100 else p, false)
            btnA.visibility = View.GONE
            btnB.visibility = View.GONE
            when (j.state) {
                DlState.QUEUED, DlState.RUNNING -> {
                    ctl(btnA, R.string.btn_pause, DownloadService.ACTION_PAUSE)
                    ctl(btnB, R.string.btn_cancel, DownloadService.ACTION_CANCEL)
                }
                DlState.PAUSED -> {
                    ctl(btnA, R.string.btn_resume, DownloadService.ACTION_RESUME)
                    ctl(btnB, R.string.btn_cancel, DownloadService.ACTION_CANCEL)
                }
                DlState.FAILED -> ctl(btnA, R.string.btn_retry, DownloadService.ACTION_RESUME)
                else -> {}
            }
        }
    }

    private fun renderJobs(list: List<DlJob>) {
        val ids = list.map { it.id }.toSet()
        cards.keys.filter { it !in ids }.forEach { jobsBox.removeView(cards.remove(it)!!.card) }
        for (j in list) {
            val c = cards.getOrPut(j.id) { JobCard(j.id).also { jobsBox.addView(it.card, 0) } }  // новые сверху
            c.bind(j)
        }
        jobsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------- вставка ссылок ----------

    /** Весь текст текущего буфера обмена: у клипа может быть несколько элементов, а в тексте — несколько строк. */
    private fun clipboardText(): String {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return ""
        return (0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(this).toString() }
    }

    private fun startDownload() {
        val typed = urlField.editText?.text?.toString().orEmpty()
        val text = typed.ifBlank { clipboardText() }
        if (Urls.extract(text).isEmpty()) {
            Toast.makeText(this, R.string.toast_no_links, Toast.LENGTH_SHORT).show()
            return
        }
        DownloadService.add(this, text)
        urlField.editText?.text?.clear()
    }

    // ---------- cookies / язык ----------

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

    private fun langName(i: Int) = when (i) {
        1 -> "English"; 2 -> "Русский"; 3 -> "Oʻzbekcha"; 4 -> "Қазақша"; 5 -> "Тоҷикӣ"; 6 -> "Беларуская"
        else -> getString(R.string.lang_system)
    }

    private fun currentLang(): Int {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (tags.isEmpty()) return 0
        val i = LANG_TAGS.indexOfFirst { it.isNotEmpty() && tags.startsWith(it) }
        return if (i < 0) 0 else i
    }

    private fun pickLanguage() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.card_language)
            .setItems(Array(LANG_TAGS.size) { langName(it) }) { _, i ->
                AppCompatDelegate.setApplicationLocales(
                    if (i == 0) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(LANG_TAGS[i]))
            }
            .show()
    }

    private fun show(id: Int) {
        current = id
        pages.forEach { (k, v) -> v.visibility = if (k == id) View.VISIBLE else View.GONE }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", current)
    }

    override fun onResume() {
        super.onResume()
        if (::chips.isInitialized) refreshCookies()  // после входа через WebView
    }

    // ---------- экран ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        // --- Главная: ввод ссылок + список загрузок
        urlField = field(getString(R.string.hint_link), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {}
        urlField.helperText = getString(R.string.hint_clipboard)
        jobsEmpty = label(getString(R.string.jobs_empty), com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        jobsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pages[ID_MAIN] = page(
            label("ShareDL", com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium),
            label(getString(R.string.app_tagline), com.google.android.material.R.style.TextAppearance_Material3_BodyMedium),
            section(getString(R.string.card_download), urlField, filled(getString(R.string.btn_download)) { startDownload() }),
            section(getString(R.string.card_jobs), jobsEmpty, jobsBox,
                outlined(getString(R.string.btn_clear_done)) { Jobs.clearFinished() })
        )

        // --- Журнал
        val logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true)
        }
        pages[ID_LOG] = page(
            section(getString(R.string.card_log), logView,
                outlined(getString(R.string.btn_clear_log)) { DownloadState.log.value = "" })
        )

        // --- Настройки
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
        chips = ChipGroup(this)
        cookiesEmpty = label(getString(R.string.cookies_none), com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)

        val quick = ArrayList<View>()
        quick.add(label(getString(R.string.note_quick), com.google.android.material.R.style.TextAppearance_Material3_BodySmall))
        if (Build.VERSION.SDK_INT >= 33) {
            quick.add(tonal(getString(R.string.btn_add_tile)) {
                getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(this, DownloadTile::class.java),
                    getString(R.string.tile_label),
                    Icon.createWithResource(this, R.drawable.ic_tile),
                    mainExecutor,
                    java.util.function.Consumer<Int> { }
                )
            })
        }

        pages[ID_SET] = page(
            section(getString(R.string.card_behavior),
                switchRow(getString(R.string.sw_show_window), Prefs.showUi(this)) { Prefs.setShowUi(this, it) },
                switchRow(getString(R.string.sw_quiet), Prefs.quiet(this)) { Prefs.setQuiet(this, it) }),
            section(getString(R.string.card_audio),
                label(getString(R.string.label_format), com.google.android.material.R.style.TextAppearance_Material3_BodySmall),
                group,
                switchRow(getString(R.string.sw_sponsor), Prefs.sponsor(this)) { Prefs.setSponsor(this, it) },
                field(getString(R.string.hint_music_domains), Prefs.musicHosts(this)) { Prefs.setMusicHosts(this, it) },
                label(getString(R.string.note_music_builtin), com.google.android.material.R.style.TextAppearance_Material3_BodySmall)),
            section(getString(R.string.card_video),
                field(getString(R.string.hint_max_height), Prefs.maxHeight(this).toString(), InputType.TYPE_CLASS_NUMBER) {
                    it.toIntOrNull()?.let { h -> Prefs.setMaxHeight(this, h) }
                }),
            section(getString(R.string.card_folders),
                label(getString(R.string.note_folders), com.google.android.material.R.style.TextAppearance_Material3_BodySmall),
                field(getString(R.string.folder_video), Prefs.dir(this, "video")) { Prefs.setDir(this, "video", it) },
                field(getString(R.string.folder_audio), Prefs.dir(this, "audio")) { Prefs.setDir(this, "audio", it) },
                field(getString(R.string.folder_image), Prefs.dir(this, "image")) { Prefs.setDir(this, "image", it) },
                field(getString(R.string.folder_file), Prefs.dir(this, "file")) { Prefs.setDir(this, "file", it) }),
            section(getString(R.string.card_network),
                field(getString(R.string.hint_proxy), Prefs.proxy(this), InputType.TYPE_TEXT_VARIATION_URI) { Prefs.setProxy(this, it) },
                field(getString(R.string.hint_extra), Prefs.extra(this)) { Prefs.setExtra(this, it) },
                switchRow(getString(R.string.sw_libdl), Prefs.libdl(this)) { Prefs.setLibdl(this, it) }),
            section(getString(R.string.card_cookies), cookiesEmpty, chips,
                tonal(getString(R.string.btn_web_login)) { startActivity(Intent(this, WebLoginActivity::class.java)) },
                outlined(getString(R.string.btn_import_cookies)) { pickCookies.launch(arrayOf("*/*")) },
                outlined(getString(R.string.btn_clear_cookies)) { Cookies.clear(this); refreshCookies() }),
            section(getString(R.string.card_quick), *quick.toTypedArray()),
            section(getString(R.string.card_language),
                outlined(getString(R.string.btn_language, langName(currentLang()))) { pickLanguage() }),
            section(getString(R.string.card_maintenance), tonal(getString(R.string.btn_update)) {
                lifecycleScope.launch {
                    DownloadState.add("Updating…")
                    val r = withContext(Dispatchers.IO) {
                        try { Downloader.update(this@MainActivity) } catch (e: Exception) { "error: ${e.message}" }
                    }
                    DownloadState.add(r)
                }
            })
        )
        refreshCookies()
        Shortcuts.publish(this)

        // --- каркас: страницы + нижняя навигация (Material 3, «пилюля»)
        val frame = FrameLayout(this)
        pages.values.forEach { frame.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)); it.visibility = View.GONE }
        val nav = BottomNavigationView(this)
        nav.menu.add(Menu.NONE, ID_MAIN, Menu.NONE, R.string.nav_main).setIcon(R.drawable.ic_nav_main)
        nav.menu.add(Menu.NONE, ID_LOG, Menu.NONE, R.string.nav_log).setIcon(R.drawable.ic_nav_log)
        nav.menu.add(Menu.NONE, ID_SET, Menu.NONE, R.string.nav_settings).setIcon(R.drawable.ic_nav_settings)
        nav.setOnItemSelectedListener { show(it.itemId); true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)

        val start = savedInstanceState?.getInt("page", ID_MAIN) ?: ID_MAIN
        nav.selectedItemId = start
        show(start)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Jobs.list.collect { renderJobs(it) } }
                launch { DownloadState.log.collect { logView.text = it } }
            }
        }
    }
}

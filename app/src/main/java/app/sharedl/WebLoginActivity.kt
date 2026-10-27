package app.sharedl

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Вход на сайт во встроенном браузере и сохранение его cookies для yt-dlp / gallery-dl.
 * У Google вход может блокироваться как «небезопасный браузер» — тогда используйте импорт cookies.txt.
 */
class WebLoginActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var address: TextInputEditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun go(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        web.loadUrl(if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.web_title)

        val quick = listOf(
            "Instagram" to "https://www.instagram.com/accounts/login/",
            "X" to "https://x.com/i/flow/login",
            "YouTube" to "https://www.youtube.com/",
            "TikTok" to "https://www.tiktok.com/login",
            "VK" to "https://vk.com/",
            "Pinterest" to "https://www.pinterest.com/login/"
        )
        val chipGroup = ChipGroup(this).apply { isSingleLine = true }
        quick.forEach { (name, url) ->
            chipGroup.addView(Chip(this).apply { text = name; setOnClickListener { go(url) } })
        }
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipGroup)
        }

        val til = TextInputLayout(this).apply { hint = getString(R.string.web_hint_url) }
        address = TextInputEditText(til.context).apply { setSingleLine() }
        til.addView(address)
        val goBtn = MaterialButton(this).apply { text = getString(R.string.btn_go); setOnClickListener { go(address.text.toString()) } }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(til, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(goBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // без «; wv» часть сайтов (в т.ч. Google) охотнее пускает на вход
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && !address.hasFocus()) address.setText(url)
            }
        }

        val save = MaterialButton(this).apply {
            text = getString(R.string.btn_save_cookies)
            setOnClickListener { saveCookies() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(chipScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(save, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        setContentView(root)

        go(quick.first().second)
    }

    private fun saveCookies() {
        val cur = web.url ?: return
        val host = Uri.parse(cur).host ?: return
        val base = Cookies.baseDomain(host)
        val cm = CookieManager.getInstance()
        cm.flush()
        val pairs = LinkedHashSet<String>()
        listOf(cur, "https://$host", "https://$base", "https://www.$base").forEach { u ->
            cm.getCookie(u)?.split(";")?.forEach { pairs.add(it.trim()) }
        }
        if (pairs.isEmpty()) {
            Toast.makeText(this, R.string.toast_web_none, Toast.LENGTH_LONG).show()
            return
        }
        val n = Cookies.addFromHeader(this, base, pairs.joinToString("; "))
        Toast.makeText(this, getString(R.string.toast_web_saved, base, n), Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}

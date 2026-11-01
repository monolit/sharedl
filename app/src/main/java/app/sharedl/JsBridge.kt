package app.sharedl

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JS-движок для yt-dlp: решение YouTube-челленджей (signature / n) в скрытом WebView.
 * Python (dl.py) вызывает [eval] с готовым скриптом и получает то, что скрипт вывел через console.log.
 * Если скрипт упал — в конце ответа добавляется «\n#ERR#<текст ошибки>».
 */
object JsBridge {
    @Volatile
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())

    private const val PAGE = "<html><head><script>window.__out=[];window.__err='';" +
        "console.log=function(s){window.__out.push(String(s))};" +
        "window.addEventListener('error',function(e){window.__err+=String((e.error&&e.error.stack)||e.message)+'\\n'});" +
        "</script><script src=\"https://sharedl.invalid/s.js\"></script></head><body></body></html>"

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun eval(script: String): String {
        val ctx = appCtx ?: throw IllegalStateException("JsBridge is not initialised")
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<String>(1)
        main.post {
            try {
                val wv = WebView(ctx)
                wv.settings.javaScriptEnabled = true
                // чтобы рендерер не «засыпал», пока мы в фоне
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                wv.webViewClient = object : WebViewClient() {
                    // большой скрипт отдаём как «файл» из памяти, а не через evaluateJavascript
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (request.url.path == "/s.js")
                            WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(script.toByteArray(Charsets.UTF_8)))
                        else null

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(
                            "window.__out.join('') + (window.__err ? '\\n#ERR#' + window.__err : '')"
                        ) { r ->
                            result[0] = r
                            view.destroy()
                            latch.countDown()
                        }
                    }
                }
                wv.loadDataWithBaseURL("https://sharedl.invalid/", PAGE, "text/html", "utf-8", null)
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        if (!latch.await(180, TimeUnit.SECONDS)) throw IllegalStateException("JS timeout")
        val raw = result[0] ?: throw IllegalStateException("WebView failed")
        return JSONArray("[$raw]").optString(0)  // evaluateJavascript возвращает значение в JSON-кодировке
    }
}

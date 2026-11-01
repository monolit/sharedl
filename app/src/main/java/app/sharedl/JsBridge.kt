package app.sharedl

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JS-движок для yt-dlp: решение YouTube-челленджей (signature / n) в скрытом WebView.
 * Python (dl.py) вызывает [eval] с готовым скриптом и получает то, что скрипт вывел через console.log.
 * Если скрипт упал — в конце ответа добавляется «\n#ERR#<текст ошибки>».
 *
 * Результат страница передаёт сама: после основного скрипта выполняется Bridge.done(...).
 * Так не нужно гадать, для какой страницы пришёл onPageFinished, и нет лимитов evaluateJavascript.
 */
object JsBridge {
    @Volatile
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())

    private const val PAGE = "<html><head>" +
        "<script>window.__out=[];window.__err='';" +
        "console.log=function(s){window.__out.push(String(s))};" +
        "window.addEventListener('error',function(e){window.__err+=String((e.error&&e.error.stack)||e.message)+'\\n'});" +
        "</script>" +
        "<script src=\"https://sharedl.invalid/s.js\"></script>" +
        "<script>Bridge.done(window.__out.join(''),window.__err);</script>" +
        "</head><body></body></html>"

    private class Sink(val latch: CountDownLatch, val result: Array<String?>) {
        @JavascriptInterface
        fun done(out: String?, err: String?) {
            result[0] = (out ?: "") + (if (err.isNullOrBlank()) "" else "\n#ERR#$err")
            latch.countDown()
        }
    }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun eval(script: String): String {
        val ctx = appCtx ?: throw IllegalStateException("JsBridge is not initialised")
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<String>(1)
        var wvRef: WebView? = null
        main.post {
            try {
                val wv = WebView(ctx)
                wvRef = wv
                wv.settings.javaScriptEnabled = true
                // чтобы рендерер не «засыпал», пока мы в фоне
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                wv.addJavascriptInterface(Sink(latch, result), "Bridge")
                wv.webViewClient = object : WebViewClient() {
                    // большой скрипт отдаём как «файл» из памяти
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (request.url.path == "/s.js")
                            WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(script.toByteArray(Charsets.UTF_8)))
                        else null
                }
                wv.loadDataWithBaseURL("https://sharedl.invalid/", PAGE, "text/html", "utf-8", null)
            } catch (e: Exception) {
                result[0] = "\n#ERR#WebView: ${e.message}"
                latch.countDown()
            }
        }
        val ok = latch.await(180, TimeUnit.SECONDS)
        main.post { try { wvRef?.destroy() } catch (_: Exception) {} }
        if (!ok) throw IllegalStateException("JS timeout")
        return result[0] ?: ""
    }
}

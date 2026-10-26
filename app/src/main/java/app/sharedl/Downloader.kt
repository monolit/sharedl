package app.sharedl

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.chaquo.python.Python
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

object Downloader {
    private val URL_RE = Regex("""https?://[^\s<>"]+""")

    /** Сайты, которые считаются музыкальными → качаем только аудио. */
    private val MUSIC = listOf(
        "music.youtube.com", "soundcloud.com", "bandcamp.com", "music.yandex.ru",
        "music.yandex.com", "mixcloud.com", "audiomack.com", "deezer.com",
        "audius.co", "hearthis.at", "jamendo.com", "last.fm"
    )
    private val EXTRA_MIME = mapOf(
        "opus" to "audio/ogg", "m4a" to "audio/mp4", "mkv" to "video/x-matroska",
        "webm" to "video/webm", "mp3" to "audio/mpeg"
    )

    /** Колбэк, который вызывает Python (dl.py). */
    class PyCb(private val logFn: (String) -> Unit, private val progFn: (Double) -> Unit) {
        fun onLog(s: String) = logFn(s)
        fun onProgress(p: Double) = progFn(p)
    }

    private fun dl() = Python.getInstance().getModule("dl")

    private var ffReady = false

    /** @return (путь к libffmpeg.so, каталог его библиотек) */
    @Synchronized
    private fun ffmpeg(ctx: Context, log: (String) -> Unit): Pair<String, String> {
        if (!ffReady) {
            try { FFmpeg.getInstance().init(ctx); ffReady = true }
            catch (t: Throwable) { log("ffmpeg: ${t.message}") }
        }
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val libs = File(ctx.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        if (!bin.exists()) log("⚠ ffmpeg не найден: склейка видео и mp3 работать не будут")
        return (if (bin.exists()) bin.absolutePath else "") to libs.absolutePath
    }

    /** Обновляет yt-dlp и gallery-dl из PyPI (без пересборки APK). */
    fun update(ctx: Context): String {
        val r = dl().callAttr("update_all", Prefs.proxy(ctx)).toString()
        Prefs.setLastUpdate(ctx, System.currentTimeMillis())
        return r
    }

    fun isMusic(ctx: Context, host: String): Boolean {
        val extra = Prefs.musicHosts(ctx).split(',', ' ', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return (MUSIC + extra).any { host == it || host.endsWith(".$it") }
    }

    /** @return список имён сохранённых файлов */
    suspend fun run(
        ctx: Context,
        raw: String,
        log: (String) -> Unit,
        progress: (Float) -> Unit
    ): List<String> = withContext(Dispatchers.IO) {
        val url = URL_RE.find(raw)?.value?.trimEnd(')', '.', ',')
        if (url == null) { log("Ссылка не найдена: $raw"); return@withContext emptyList() }
        log("▶ $url")

        if (System.currentTimeMillis() - Prefs.lastUpdate(ctx) > 24 * 3600_000L) {
            try { log("Обновление: " + update(ctx)) } catch (e: Exception) { log("Обновление не удалось: ${e.message}") }
        }

        val host = try { URI(url).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
        val music = isMusic(ctx, host)
        log(if (music) "Музыкальный сайт ($host) → аудио" else "Медиа ($host)")

        val (ffBin, ffLibs) = ffmpeg(ctx, log)
        val cookies = Cookies.file(ctx)
        val opts = JSONObject()
            .put("music", music)
            .put("audio_fmt", Prefs.audioFmt(ctx))
            .put("max_h", Prefs.maxHeight(ctx))
            .put("cookies", if (cookies.exists()) cookies.absolutePath else "")
            .put("ffmpeg", ffBin)
            .put("ffmpeg_libs", ffLibs)
            .put("tmp", ctx.cacheDir.absolutePath)
            .put("proxy", Prefs.proxy(ctx).trim())
            .put("extra", Prefs.extra(ctx).trim())

        val outDir = File(ctx.cacheDir, "dl/${System.currentTimeMillis()}").apply { mkdirs() }
        val saved = mutableListOf<String>()
        try {
            val cb = PyCb(log) { progress(it.toFloat()) }
            val res = dl().callAttr("download", url, outDir.absolutePath, opts.toString(), cb).toString()
            val arr = JSONArray(res)
            for (i in 0 until arr.length()) saved += save(ctx, File(arr.getString(i)), log)
        } finally {
            outDir.deleteRecursively()
        }
        saved
    }

    /** Копирует файл в общую медиатеку: Music / Movies / Pictures / Download → папка ShareDL. */
    private fun save(ctx: Context, f: File, log: (String) -> Unit): String {
        val ext = f.extension.lowercase()
        val mime = EXTRA_MIME[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val (coll, dir) = when {
            mime.startsWith("audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
            mime.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            mime.startsWith("image") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/ShareDL")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(coll, values) ?: throw IllegalStateException("MediaStore insert failed")
        r.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        r.update(uri, values, null, null)
        log("✔ $dir/ShareDL/${f.name}")
        return f.name
    }
}

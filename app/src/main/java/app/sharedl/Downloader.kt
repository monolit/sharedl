package app.sharedl

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
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
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

object Downloader {
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

    class Result(val saved: List<String>, val skipped: List<String>)

    /** Колбэк, который вызывает Python (dl.py). */
    class PyCb(
        private val logFn: (String) -> Unit,
        private val progFn: (Double) -> Unit,
        private val titleFn: (String) -> Unit
    ) {
        fun onLog(s: String) = logFn(s)
        fun onProgress(p: Double) = progFn(p)
        fun onTitle(s: String) = titleFn(s)
    }

    private fun dl() = Python.getInstance().getModule("dl")

    /** Временная папка задачи: зависит только от ссылки, поэтому после паузы докачка продолжается. */
    fun tmpDir(ctx: Context, url: String) = File(ctx.cacheDir, "dl/" + Integer.toHexString(url.hashCode()))

    fun requestStop(id: Int) { try { dl().callAttr("request_stop", id) } catch (_: Exception) {} }
    fun clearStop(id: Int) { try { dl().callAttr("clear_stop", id) } catch (_: Exception) {} }

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
        if (!bin.exists()) log("⚠ ffmpeg not found: merging video and mp3 will not work")
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

    private val updating = AtomicBoolean(false)

    suspend fun run(
        ctx: Context,
        job: DlJob,
        log: (String) -> Unit,
        progress: (Int) -> Unit,
        title: (String) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val url = job.url
        log("▶ $url")

        if (System.currentTimeMillis() - Prefs.lastUpdate(ctx) > 24 * 3600_000L && updating.compareAndSet(false, true)) {
            try { log("Update: " + update(ctx)) } catch (e: Exception) { log("Update failed: ${e.message}") }
            finally { updating.set(false) }
        }

        val host = try { URI(url).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
        val music = isMusic(ctx, host)
        log(if (music) "Music site ($host) → audio" else "Media ($host)")

        val (ffBin, ffLibs) = ffmpeg(ctx, log)
        val cookies = Cookies.file(ctx)
        val opts = JSONObject()
            .put("music", music)
            .put("audio_fmt", Prefs.audioFmt(ctx))
            .put("max_h", Prefs.maxHeight(ctx))
            .put("sponsor", Prefs.sponsor(ctx))
            .put("libdl", Prefs.libdl(ctx))
            .put("cookies", if (cookies.exists()) cookies.absolutePath else "")
            .put("ffmpeg", ffBin)
            .put("ffmpeg_libs", ffLibs)
            .put("tmp", ctx.cacheDir.absolutePath)
            .put("proxy", Prefs.proxy(ctx).trim())
            .put("extra", Prefs.extra(ctx).trim())

        val outDir = tmpDir(ctx, url).apply { mkdirs() }
        val cb = PyCb(log, { progress(it.toInt()) }, title)
        val res = dl().callAttr("download", job.id, url, outDir.absolutePath, opts.toString(), cb).toString()

        val arr = JSONArray(res)
        val saved = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val f = File(arr.getString(i))
            if (save(ctx, f, log)) saved += f.name else skipped += f.name
        }
        Result(saved, skipped)
    }

    // ---------- сохранение в общую медиатеку ----------

    private class Target(val coll: Uri, val rel: String, val mime: String)

    private fun mimeOf(f: File): String {
        val ext = f.extension.lowercase()
        return EXTRA_MIME[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun category(mime: String) = when {
        mime.startsWith("audio") -> "audio"
        mime.startsWith("video") -> "video"
        mime.startsWith("image") -> "image"
        else -> "file"
    }

    private fun target(ctx: Context, f: File, forceDefault: Boolean = false): Target {
        val mime = mimeOf(f)
        val cat = category(mime)
        var rel = (if (forceDefault) "" else Prefs.dir(ctx, cat)).trim().trim('/')
        if (rel.isEmpty()) rel = Prefs.defaultDir(cat)
        val top = rel.substringBefore('/')
        // Download/Documents принимают файлы любого типа; остальное — по коллекции типа
        val coll = if (top == "Download" || top == "Documents") MediaStore.Downloads.EXTERNAL_CONTENT_URI
        else when (cat) {
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        return Target(coll, "$rel/", mime)
    }

    private fun fill(s: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = s.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun sameBytes(r: ContentResolver, uri: Uri, f: File): Boolean {
        return try {
            val a = r.openInputStream(uri) ?: return false
            val result: Boolean? = a.use { ia ->
                f.inputStream().use { ib ->
                    val ba = ByteArray(65536)
                    val bb = ByteArray(65536)
                    var res: Boolean? = null
                    while (res == null) {
                        val na = fill(ia, ba)
                        val nb = fill(ib, bb)
                        when {
                            na != nb -> res = false
                            na == 0 -> res = true
                            else -> {
                                var i = 0
                                while (i < na && ba[i] == bb[i]) i++
                                if (i < na) res = false
                            }
                        }
                    }
                    res
                }
            }
            result == true
        } catch (_: Exception) {
            false
        }
    }

    /** Есть ли в целевой папке файл с тем же размером и теми же байтами (имя не важно). */
    private fun isDuplicate(ctx: Context, t: Target, f: File): Boolean {
        val r = ctx.contentResolver
        try {
            r.query(
                t.coll, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.SIZE}=?",
                arrayOf(t.rel, f.length().toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (sameBytes(r, ContentUris.withAppendedId(t.coll, c.getLong(0)), f)) return true
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun insert(r: ContentResolver, t: Target, f: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, t.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, t.rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return r.insert(t.coll, values)
    }

    /** @return true — сохранён, false — пропущен как дубликат */
    private fun save(ctx: Context, f: File, log: (String) -> Unit): Boolean {
        var t = target(ctx, f)
        if (isDuplicate(ctx, t, f)) {
            log("= already exists, skipped: ${f.name}")
            return false
        }
        val r = ctx.contentResolver
        val uri = try {
            insert(r, t, f)
        } catch (e: IllegalArgumentException) {
            // папка не подходит для этого типа файла (например, видео в Pictures) — берём папку по умолчанию
            log("⚠ folder ${t.rel} is not allowed for this file type, using default")
            t = target(ctx, f, forceDefault = true)
            insert(r, t, f)
        } ?: throw IllegalStateException("MediaStore insert failed")
        try {
            r.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            r.update(uri, done, null, null)
        } catch (e: Exception) {
            r.delete(uri, null, null)
            throw e
        }
        log("✔ ${t.rel}${f.name}")
        return true
    }
}

package app.sharedl

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun showUi(c: Context) = sp(c).getBoolean("show_ui", false)
    fun setShowUi(c: Context, v: Boolean) = sp(c).edit().putBoolean("show_ui", v).apply()

    /** Тихий режим: без всплывающих сообщений об успехе, только уведомления об ошибках. */
    fun quiet(c: Context) = sp(c).getBoolean("quiet", false)
    fun setQuiet(c: Context, v: Boolean) = sp(c).edit().putBoolean("quiet", v).apply()

    fun sponsor(c: Context) = sp(c).getBoolean("sponsor", true)
    fun setSponsor(c: Context, v: Boolean) = sp(c).edit().putBoolean("sponsor", v).apply()

    /** Экспериментально: прямые ссылки на файлы через libdl. */
    fun libdl(c: Context) = sp(c).getBoolean("libdl", false)
    fun setLibdl(c: Context, v: Boolean) = sp(c).edit().putBoolean("libdl", v).apply()

    fun audioFmt(c: Context) = sp(c).getString("audio_fmt", "m4a")!!
    fun setAudioFmt(c: Context, v: String) = sp(c).edit().putString("audio_fmt", v).apply()

    fun maxHeight(c: Context) = sp(c).getInt("max_h", 1080)
    fun setMaxHeight(c: Context, v: Int) = sp(c).edit().putInt("max_h", v).apply()

    // дополнительные «музыкальные» домены через запятую
    fun musicHosts(c: Context) = sp(c).getString("music_hosts", "")!!
    fun setMusicHosts(c: Context, v: String) = sp(c).edit().putString("music_hosts", v).apply()

    // прокси для yt-dlp / gallery-dl / прямых ссылок, напр. socks5://127.0.0.1:1080
    fun proxy(c: Context) = sp(c).getString("proxy", "")!!
    fun setProxy(c: Context, v: String) = sp(c).edit().putString("proxy", v).apply()

    // произвольные аргументы yt-dlp
    fun extra(c: Context) = sp(c).getString("extra", "")!!
    fun setExtra(c: Context, v: String) = sp(c).edit().putString("extra", v).apply()

    /** Папка для категории: video / audio / image / file (путь относительно внутренней памяти). */
    fun defaultDir(cat: String) = when (cat) {
        "video" -> "Movies/ShareDL"
        "audio" -> "Music/ShareDL"
        "image" -> "Pictures/ShareDL"
        else -> "Download/ShareDL"
    }
    fun dir(c: Context, cat: String) = sp(c).getString("dir_$cat", null) ?: defaultDir(cat)
    fun setDir(c: Context, cat: String, v: String) = sp(c).edit().putString("dir_$cat", v).apply()

    fun lastUpdate(c: Context) = sp(c).getLong("last_update", 0L)
    fun setLastUpdate(c: Context, v: Long) = sp(c).edit().putLong("last_update", v).apply()
}

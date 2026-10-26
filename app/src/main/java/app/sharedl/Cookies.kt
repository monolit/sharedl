package app.sharedl

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Хранит один объединённый cookies.txt (Netscape). Можно импортировать
 * несколько файлов для разных сайтов — yt-dlp сам берёт нужные по домену.
 */
object Cookies {
    private const val HEADER = "# Netscape HTTP Cookie File"

    fun file(c: Context) = File(c.filesDir, "cookies.txt")

    private fun key(parts: List<String>) = parts[0] + "|" + parts[2] + "|" + parts[5]

    private fun parse(text: String, into: LinkedHashMap<String, String>) {
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            if (line.startsWith("#") && !line.startsWith("#HttpOnly_")) continue
            val p = line.split("\t")
            if (p.size < 7) continue
            into[key(p)] = line
        }
    }

    private fun load(c: Context): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        val f = file(c)
        if (f.exists()) parse(f.readText(), m)
        return m
    }

    private fun save(c: Context, m: Map<String, String>) {
        if (m.isEmpty()) { file(c).delete(); return }
        file(c).writeText(HEADER + "\n" + m.values.joinToString("\n") + "\n")
    }

    fun import(c: Context, uris: List<Uri>): Int {
        val m = load(c)
        val before = m.size
        for (u in uris) {
            val text = c.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: continue
            parse(text, m)
        }
        save(c, m)
        return m.size - before
    }

    /** домен -> число cookies */
    fun domains(c: Context): Map<String, Int> =
        load(c).values.groupingBy {
            it.split("\t")[0].removePrefix("#HttpOnly_").trimStart('.')
        }.eachCount().toSortedMap()

    fun remove(c: Context, domain: String) {
        val m = load(c)
        m.entries.removeAll {
            it.value.split("\t")[0].removePrefix("#HttpOnly_").trimStart('.') == domain
        }
        save(c, m)
    }

    fun clear(c: Context) { file(c).delete() }
}

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

    /** example.co.uk / www.example.com / music.youtube.com → базовый домен (упрощённо, без Public Suffix List). */
    fun baseDomain(host: String): String {
        val h = host.lowercase().removePrefix("www.")
        val p = h.split('.')
        if (p.size <= 2) return h
        val sld = setOf("co", "com", "org", "net", "gov", "edu", "ac")
        val n = if (p.last().length == 2 && p[p.size - 2] in sld) 3 else 2
        return p.takeLast(n).joinToString(".")
    }

    /**
     * Cookies из WebView: CookieManager отдаёт только «name=value; …» без домена и срока,
     * поэтому записываем их как cookies базового домена со сроком в год.
     * @return сколько cookies разобрано
     */
    fun addFromHeader(c: Context, base: String, header: String): Int {
        val m = load(c)
        val exp = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        var n = 0
        for (pair in header.split(";")) {
            val t = pair.trim()
            if (!t.contains('=')) continue
            val parts = listOf(".$base", "TRUE", "/", "TRUE", exp.toString(), t.substringBefore('='), t.substringAfter('='))
            m[key(parts)] = parts.joinToString("\t")
            n++
        }
        save(c, m)
        return n
    }
}

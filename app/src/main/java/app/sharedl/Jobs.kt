package app.sharedl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

enum class DlState { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELED }

data class DlJob(
    val id: Int,
    val url: String,
    val state: DlState = DlState.QUEUED,
    val progress: Int = -1,
    val title: String = "",
    val message: String = ""
) {
    val active get() = state == DlState.QUEUED || state == DlState.RUNNING
    val open get() = active || state == DlState.PAUSED
}

/** Реестр загрузок в памяти процесса; UI подписывается на [list]. */
object Jobs {
    private val counter = AtomicInteger(0)
    val list = MutableStateFlow<List<DlJob>>(emptyList())

    fun create(url: String): DlJob {
        val j = DlJob(counter.incrementAndGet(), url)
        list.update { it + j }
        return j
    }

    fun get(id: Int) = list.value.firstOrNull { it.id == id }

    fun update(id: Int, f: (DlJob) -> DlJob) = list.update { l -> l.map { if (it.id == id) f(it) else it } }

    fun clearFinished() = list.update { l -> l.filter { it.open } }
}

object Urls {
    private val RE = Regex("""https?://[^\s<>"]+""")

    /** Все ссылки из текста (несколько через перевод строки/пробел), без повторов. */
    fun extract(text: String): List<String> =
        RE.findAll(text).map { it.value.trimEnd(')', '.', ',', ';') }.distinct().toList()
}

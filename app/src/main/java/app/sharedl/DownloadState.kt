package app.sharedl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object DownloadState {
    val log = MutableStateFlow("")
    fun add(s: String) = log.update { (it + s + "\n").takeLast(8000) }
}

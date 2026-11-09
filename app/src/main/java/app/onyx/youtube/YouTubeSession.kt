package app.onyx.youtube

import androidx.compose.runtime.mutableStateOf

/**
 * YouTube tab state that must survive tab switches (and accidental back
 * presses). Compose disposes the tab content when another tab is shown, so a
 * plain `remember` would drop the resolved video and the chosen timestamps.
 */
object YouTubeSession {

    const val MODE_SEARCH = "search"
    const val MODE_LINK = "link"

    val mode = mutableStateOf(MODE_SEARCH)
    val query = mutableStateOf("")
    val link = mutableStateOf("")

    val preview = mutableStateOf<YtVideo?>(null)
    val resolving = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val sections = mutableStateOf<List<YtSection>>(emptyList())
    val selected = mutableStateOf<Set<Int>>(emptySet())
    val manualMode = mutableStateOf(false)
    val manualText = mutableStateOf("")

    fun closePreview() {
        preview.value = null
        sections.value = emptyList()
        selected.value = emptySet()
        error.value = null
        manualMode.value = false
    }
}

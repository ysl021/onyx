package app.onyx.ui

/**
 * The pages that can carry a cover backdrop or a custom photo.
 *
 * Keys are stored in preferences (and survive the 0.9 tabs→sections rework), so
 * they must stay stable: "music" is the Library, "youtube" is the Search tab.
 * Keeping the list in one place stops the two settings cards from drifting
 * apart again — before this, they still offered Queue, Playlists and Visual,
 * which no longer exist as pages.
 */
data class OnyxPage(val key: String, val ru: String, val en: String) {
    val label: String get() = tr(ru, en)
}

val ONYX_PAGES: List<OnyxPage> = listOf(
    OnyxPage("player", "Плеер", "Player"),
    OnyxPage("lyrics", "Текст", "Lyrics"),
    OnyxPage("music", "Библиотека", "Library"),
    OnyxPage("youtube", "Поиск", "Search"),
    OnyxPage("settings", "Настройки", "Settings"),
)

/** Keys that still draw a page backdrop; everything else is legacy. */
val ONYX_PAGE_KEYS: Set<String> = ONYX_PAGES.map { it.key }.toSet()

/**
 * Old builds had separate Playlists and Visual pages. Their saved backdrops
 * still exist in preferences; map them onto the page that absorbed them so a
 * custom photo is not silently lost.
 */
fun normalizePageKey(key: String): String = when (key) {
    "playlists" -> "music"
    "visualizer", "queue" -> "player"
    else -> key
}

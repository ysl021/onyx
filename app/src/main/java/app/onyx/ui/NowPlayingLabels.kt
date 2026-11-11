package app.onyx.ui

/**
 * Players often report the source app ("Telegram", "exteragram", "Spotify") as
 * the artist when a file has no tags. The phone player used to print the artist
 * and then the album, so those files showed "tg" twice or the artist twice.
 */
private val SOURCE_LABELS = setOf(
    "telegram", "tg", "телеграм", "exteragram", "telegramx",
    "spotify", "youtube", "youtubemusic", "soundcloud",
    "vk", "vkontakte", "вконтакте",
    "music", "audio", "files", "downloads",
    "музыка", "аудио", "загрузки", "локальныефайлы",
)

private fun labelKey(value: String): String =
    value.lowercase().replace(Regex("[\\p{Punct}\\s]+"), "")

private fun isSourceLabel(value: String): Boolean =
    value.isBlank() || labelKey(value) in SOURCE_LABELS

/** Artist line: real artist, otherwise a meaningful album, otherwise empty. */
fun nowPlayingArtist(artist: String, album: String): String {
    val a = artist.trim()
    val b = album.trim()
    return when {
        !isSourceLabel(a) -> a
        !isSourceLabel(b) -> b
        else -> ""
    }
}

/** Album line, hidden when it repeats the artist or is just an app name. */
fun nowPlayingAlbum(artist: String, album: String): String {
    val b = album.trim()
    if (isSourceLabel(b)) return ""
    val shownArtist = nowPlayingArtist(artist, album)
    if (shownArtist.isNotBlank() && labelKey(b) == labelKey(shownArtist)) return ""
    return b
}

/** Removes an "Artist - " prefix that would duplicate the artist line. */
fun nowPlayingTitle(title: String, artist: String, album: String): String {
    val text = title.trim()
    val shownArtist = nowPlayingArtist(artist, album)
    if (shownArtist.isBlank()) return text
    if (text.startsWith(shownArtist, ignoreCase = true)) {
        val rest = text.substring(shownArtist.length)
        for (separator in listOf(" - ", " – ", " — ", " | ", ": ")) {
            if (rest.startsWith(separator)) {
                val trimmed = rest.removePrefix(separator).trim()
                if (trimmed.isNotBlank()) return trimmed
            }
        }
    }
    return text
}

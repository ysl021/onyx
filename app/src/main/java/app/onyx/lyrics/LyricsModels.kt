package app.onyx.lyrics

import kotlinx.serialization.Serializable

@Serializable
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

@Serializable
data class LyricLine(
    val text: String,
    val startMs: Long,
    val endMs: Long = startMs + 4000,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
)

@Serializable
data class LyricsDoc(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val karaoke: Boolean,
    val provider: String,
    val title: String = "",
    val artist: String = "",
)

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Ready(val doc: LyricsDoc) : LyricsState
    data class Error(val message: String) : LyricsState
}

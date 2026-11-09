package app.onyx.media

import android.graphics.Bitmap

data class NowPlaying(
    val packageName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artUri: String? = null,
    val art: Bitmap? = null,
    val mediaId: String = "",
    val durationMs: Long = 0L,
    /** Playback position, used by the Discord bridge for the progress bar. */
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val canSeek: Boolean = false,
    val canSkip: Boolean = false,
) {
    val hasTrack: Boolean get() = title.isNotBlank()
    val spotifyTrackId: String?
        get() {
            if (packageName != "com.spotify.music") return null
            if (mediaId.startsWith("spotify:track:")) return mediaId.removePrefix("spotify:track:")
            return mediaId.takeIf { it.length == 22 && it.none { c -> !c.isLetterOrDigit() } }
        }
}

data class MediaState(
    val now: NowPlaying = NowPlaying(),
    val positionMs: Long = 0L,
    val hasAccess: Boolean = false,
)

package app.onyx.spotify

import android.os.SystemClock
import app.onyx.core.Http
import app.onyx.core.Prefs
import app.onyx.core.PrefsData
import app.onyx.media.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.math.abs

/**
 * Sends the current track to the ONYX desktop companion over LAN.
 * The companion turns it into Discord Rich Presence with the official IPC API.
 *
 * Position is sent along with the track so the companion can hand Discord both
 * timestamps (`start` and `end`), which is what draws the progress bar.
 */
class DiscordBridge(
    private val prefs: Prefs,
    nowPlaying: StateFlow<NowPlaying>,
    position: StateFlow<Long>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastSignature = ""
    private var lastAttemptAt = 0L
    private var lastPosition = 0L
    private var lastPlaying = false
    private var lastOk = false
    private var hadTrack = false
    private var enabled = false

    init {
        scope.launch {
            combine(nowPlaying, position) { now, pos -> now to pos }.collect { (now, rawPos) ->
                val p = prefs.snapshot()
                if (!p.discordBridgeEnabled || p.discordBridgeHost.isBlank()) {
                    // Turning the bridge off must remove the card immediately.
                    // Without this the PC keeps the last presence for up to
                    // IDLE_CLEAR_SECONDS after the phone stops sending updates.
                    if (enabled) {
                        enabled = false
                        hadTrack = false
                        lastSignature = ""
                        lastOk = false
                        if (p.discordBridgeHost.isNotBlank()) {
                            post(p, JSONObject().put("clear", true))
                        }
                    }
                    return@collect
                }
                enabled = true

                if (now.title.isBlank()) {
                    if (hadTrack) {
                        hadTrack = false
                        lastSignature = ""
                        post(p, JSONObject().put("clear", true))
                    }
                    return@collect
                }
                hadTrack = true

                val duration = now.durationMs.coerceAtLeast(0L)
                val signature = listOf(
                    now.title,
                    now.artist,
                    now.album,
                    now.isPlaying,
                    duration,
                    now.packageName,
                    now.spotifyTrackId ?: "",
                ).joinToString("\u0000")

                val nowMs = SystemClock.elapsedRealtime()
                val trackChanged = signature != lastSignature
                // On a track change the position flow still holds the previous
                // track's value for a moment; starting at 0 beats showing the
                // bar at the end of the new track.
                val raw = if (trackChanged) 0L else rawPos
                val pos = if (duration > 0) raw.coerceIn(0L, duration) else raw.coerceAtLeast(0L)
                val expected = lastPosition +
                    if (lastPlaying && lastAttemptAt > 0) nowMs - lastAttemptAt else 0L
                val seeked = abs(pos - expected) > SEEK_THRESHOLD_MS
                val due = trackChanged || seeked ||
                    nowMs - lastAttemptAt >= if (lastOk) HEARTBEAT_MS else RETRY_MS
                if (!due) return@collect

                lastSignature = signature
                lastAttemptAt = nowMs
                lastPosition = pos
                lastPlaying = now.isPlaying

                val payload = JSONObject().apply {
                    val cleanArtist = cleanSource(now.artist)
                    val cleanAlbum = cleanSource(now.album)
                    put("title", now.title)
                    // The PC puts this on the second line of the Discord card
                    // and uses the album only as the cover hover text.
                    put("artist", cleanArtist)
                    put("album", cleanAlbum)
                    put("playing", now.isPlaying)
                    put("durationMs", duration)
                    put("positionMs", pos)
                    put("source", now.packageName)
                    put(
                        "url",
                        now.spotifyTrackId?.let { "https://open.spotify.com/track/$it" }
                            ?: app.onyx.core.DiscordConfig.GITHUB_URL,
                    )
                }
                lastOk = post(p, payload)
            }
        }
    }

    /**
     * Some players report the source app as the artist for files without tags.
     * Send an empty value instead so the PC card never shows "tg" or "Telegram".
     */
    private fun cleanSource(value: String): String {
        val key = value.lowercase().replace(Regex("[\\p{Punct}\\s]+"), "")
        return if (key in SOURCE_LABELS) "" else value.trim()
    }

    private fun post(p: PrefsData, payload: JSONObject): Boolean {
        val url = "http://${p.discordBridgeHost.trim()}:" +
            "${p.discordBridgePort.coerceIn(1, 65535)}/presence"
        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (p.discordBridgeToken.isNotBlank()) {
            builder.header("Authorization", "Bearer ${p.discordBridgeToken.trim()}")
        }
        return runCatching {
            Http.client.newCall(builder.build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun close() {
        scope.cancel()
    }

    private companion object {
        /** Discord accepts ~5 presence updates per 20 s; 5 s keeps the bar smooth. */
        const val HEARTBEAT_MS = 5_000L

        /** A failed POST is retried quickly in case the PC bridge was just started. */
        const val RETRY_MS = 5_000L

        /** Anything bigger than this is treated as a seek and pushed immediately. */
        const val SEEK_THRESHOLD_MS = 4_000L

        val SOURCE_LABELS = setOf(
            "telegram", "tg", "телеграм", "exteragram", "telegramx",
            "spotify", "youtube", "youtubemusic", "soundcloud",
            "vk", "vkontakte", "вконтакте",
            "music", "audio", "files", "downloads",
            "музыка", "аудио", "загрузки", "локальныефайлы",
        )
    }
}

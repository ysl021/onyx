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

    init {
        scope.launch {
            combine(nowPlaying, position) { now, pos -> now to pos }.collect { (now, rawPos) ->
                val p = prefs.snapshot()
                if (!p.discordBridgeEnabled || p.discordBridgeHost.isBlank()) return@collect

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
                val pos = if (duration > 0) rawPos.coerceIn(0L, duration) else rawPos.coerceAtLeast(0L)
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
                val expected = lastPosition +
                    if (lastPlaying && lastAttemptAt > 0) nowMs - lastAttemptAt else 0L
                val seeked = abs(pos - expected) > SEEK_THRESHOLD_MS
                val trackChanged = signature != lastSignature
                val due = trackChanged || seeked ||
                    nowMs - lastAttemptAt >= if (lastOk) HEARTBEAT_MS else RETRY_MS
                if (!due) return@collect

                lastSignature = signature
                lastAttemptAt = nowMs
                lastPosition = pos
                lastPlaying = now.isPlaying

                val payload = JSONObject().apply {
                    put("title", now.title)
                    put("artist", now.artist)
                    put("album", now.album)
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

    private fun post(p: PrefsData, payload: JSONObject): Boolean {
        val url = "http://${p.discordBridgeHost.trim()}:" +
            "${p.discordBridgePort.coerceIn(1, 65535)}/presence"
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            Http.client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun close() {
        scope.cancel()
    }

    private companion object {
        /** Discord accepts ~5 presence updates per 20 s; this stays well under it. */
        const val HEARTBEAT_MS = 15_000L

        /** A failed POST is retried quickly in case the PC bridge was just started. */
        const val RETRY_MS = 5_000L

        /** Anything bigger than this is treated as a seek and pushed immediately. */
        const val SEEK_THRESHOLD_MS = 4_000L
    }
}

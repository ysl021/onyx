package app.onyx.spotify

import app.onyx.core.Http
import app.onyx.core.Prefs
import app.onyx.media.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Sends the current track to the ONYX desktop companion over LAN.
 * The companion turns it into Discord Rich Presence with the official IPC API.
 */
class DiscordBridge(
    private val prefs: Prefs,
    nowPlaying: StateFlow<NowPlaying>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastPayload = ""

    init {
        scope.launch {
            nowPlaying.collect { now ->
                if (!now.title.isBlank()) push(now)
            }
        }
    }

    private suspend fun push(now: NowPlaying) {
        val p = prefs.snapshot()
        if (!p.discordBridgeEnabled || p.discordBridgeHost.isBlank()) return
        val payload = JSONObject().apply {
            put("title", now.title)
            put("artist", now.artist)
            put("album", now.album)
            put("playing", now.isPlaying)
            put("durationMs", now.durationMs)
            put("source", now.packageName)
            put(
                "url",
                now.spotifyTrackId?.let { "https://open.spotify.com/track/$it" }
                    ?: app.onyx.core.DiscordConfig.GITHUB_URL,
            )
        }.toString()
        if (payload == lastPayload) return
        val url = "http://${p.discordBridgeHost.trim()}:${p.discordBridgePort.coerceIn(1, 65535)}/presence"
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) lastPayload = payload
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}

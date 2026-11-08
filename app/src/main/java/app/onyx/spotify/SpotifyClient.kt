package app.onyx.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import app.onyx.core.Http
import app.onyx.core.Prefs
import app.onyx.ui.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class SpotifyImage(val url: String = "")

@Serializable
data class SpotifyArtist(val name: String = "")

@Serializable
data class SpotifyAlbum(
    val name: String = "",
    val images: List<SpotifyImage> = emptyList(),
)

@Serializable
data class SpotifyTrack(
    val id: String = "",
    val name: String = "",
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbum = SpotifyAlbum(),
    @SerialName("duration_ms") val durationMs: Long = 0L,
    val uri: String = "",
)

@Serializable
data class SpotifyDevice(
    val name: String = "",
    val active: Boolean = false,
)

@Serializable
data class SpotifyPlayer(
    @SerialName("repeat_state") val repeatState: String = "off",
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("progress_ms") val progressMs: Long = 0L,
    val item: SpotifyTrack? = null,
    val device: SpotifyDevice? = null,
)

@Serializable
data class SpotifyQueue(
    @SerialName("currently_playing") val currentlyPlaying: SpotifyTrack? = null,
    val queue: List<SpotifyTrack> = emptyList(),
)

@Serializable
data class SpotifyPlaylist(
    val id: String = "",
    val name: String = "",
    val images: List<SpotifyImage> = emptyList(),
    val tracks: SpotifyPlaylistTracks = SpotifyPlaylistTracks(),
    val uri: String = "",
)

@Serializable
data class SpotifyPlaylistTracks(
    val total: Int = 0,
)

@Serializable
private data class SpotifyPlaylistsResponse(
    val items: List<SpotifyPlaylist> = emptyList(),
)

@Serializable
private data class SpotifyPlaylistItem(
    val track: SpotifyTrack? = null,
)

@Serializable
private data class SpotifyPlaylistTracksResponse(
    val items: List<SpotifyPlaylistItem> = emptyList(),
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("token_type") val tokenType: String = "",
)

@Serializable
private data class Profile(val display_name: String = "", val id: String = "")

data class TrackUi(
    val title: String,
    val artist: String,
    val album: String,
    val art: String?,
    val durationMs: Long,
    val uri: String,
)

data class QueueData(
    val current: TrackUi?,
    val next: List<TrackUi>,
    val repeatState: String,
    val deviceName: String,
)

sealed interface SpotifyLoginState {
    data object Idle : SpotifyLoginState
    data object Waiting : SpotifyLoginState
    data class Connected(val name: String) : SpotifyLoginState
    data class Failed(val message: String) : SpotifyLoginState
}

class SpotifyClient(private val context: Context, private val prefs: Prefs) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _loginState = MutableStateFlow<SpotifyLoginState>(SpotifyLoginState.Idle)
    val loginState = _loginState.asStateFlow()

    @Volatile
    private var loginRunning = false

    /**
     * Runs OAuth in an application-scoped coroutine. The old call lived in the
     * settings composable scope, so opening the browser (and the activity being
     * recreated in the background) cancelled the loopback server and the login
     * failed with a generic error.
     */
    fun loginAsync(activityContext: Context) {
        if (loginRunning) return
        loginRunning = true
        _loginState.value = SpotifyLoginState.Waiting
        scope.launch {
            val result = runCatching { loginInternal(activityContext.applicationContext) }
            _loginState.value = result.fold(
                onSuccess = { SpotifyLoginState.Connected(it) },
                onFailure = {
                    Log.w(TAG, "Spotify login failed", it)
                    SpotifyLoginState.Failed(it.message ?: tr("Ошибка входа", "Sign-in error"))
                },
            )
            loginRunning = false
        }
    }

    private suspend fun loginInternal(activityContext: Context): String = runCatching {
        val p = prefs.snapshot()
        if (p.spotifyClientId.isBlank()) {
            error(tr("Сначала укажи Spotify client_id в настройках", "Set the Spotify client_id in settings first"))
        }
        val verifier = randomString(64)
        val challenge = base64Url(sha256(verifier.toByteArray()))
        val state = randomString(18)

        val url = Uri.parse(AUTH).buildUpon()
            .appendQueryParameter("client_id", p.spotifyClientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("show_dialog", "true")
            .build()
            .toString()

        val code = withContext(Dispatchers.IO) {
            val server = try {
                ServerSocket(8899, 1, InetAddress.getByName("127.0.0.1"))
            } catch (e: BindException) {
                error(tr("Порт 8899 занят. Закрой приложение, которое его использует, и повтори.", "Port 8899 is busy. Close the app using it and try again."))
            }
            server.soTimeout = 180_000
            try {
                val opened = withContext(Dispatchers.Main) {
                    try {
                        val intent = CustomTabsIntent.Builder()
                            .setShowTitle(true)
                            .build()
                            .intent
                            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        intent.data = Uri.parse(url)
                        activityContext.startActivity(intent)
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "Custom Tab failed, falling back to browser", e)
                        try {
                            activityContext.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            true
                        } catch (fallback: Exception) {
                            Log.e(TAG, "No browser available", fallback)
                            false
                        }
                    }
                }
                if (!opened) error(tr("Не удалось открыть браузер для входа в Spotify", "Could not open a browser for Spotify sign-in"))

                server.accept().use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val requestLine = reader.readLine().orEmpty()
                    val path = requestLine.split(" ").getOrNull(1).orEmpty()
                    val parsed = Uri.parse("http://localhost$path")
                    val returnedState = parsed.getQueryParameter("state")
                    val err = parsed.getQueryParameter("error")
                    val c = parsed.getQueryParameter("code")
                    val ok = c != null && returnedState == state
                    val html = if (ok) {
                        "<html><body style='background:#050505;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh'><h2>${tr("ONYX подключён. Вернись в приложение.", "ONYX is connected. Return to the app.")}</h2></body></html>"
                    } else {
                        "<html><body style='background:#050505;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh'><h2>${tr("Не получилось", "Failed")}: ${err ?: tr("нет кода", "no code")}</h2></body></html>"
                    }
                    val body = html.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().write(header.toByteArray(Charsets.UTF_8))
                    client.getOutputStream().write(body)
                    client.getOutputStream().flush()
                    if (returnedState != state) error("OAuth state mismatch")
                    c ?: error(tr("Spotify не вернул код", "Spotify returned no code") + " (${err ?: "unknown"})")
                }
            } catch (e: java.net.SocketTimeoutException) {
                error(tr("Браузер не вернул ответ за 3 минуты. Попробуй ещё раз.", "The browser did not respond within 3 minutes. Try again."))
            } finally {
                try {
                    server.close()
                } catch (_: Exception) {
                }
            }
        }

        exchangeCode(p.spotifyClientId, code, verifier)

        val token = token()
        val profile = token?.let { apiGet<Profile>("https://api.spotify.com/v1/me", it) }
        val name = profile?.display_name?.ifBlank { profile.id } ?: "Spotify"
        val cur = prefs.snapshot()
        prefs.setTokens(cur.spotifyAccess, cur.spotifyRefresh, cur.spotifyExpiresAt, name)
        name
    }.getOrThrow()

    suspend fun login(activityContext: Context): Result<String> = runCatching {
        loginInternal(activityContext)
    }

    private suspend fun exchangeCode(clientId: String, code: String, verifier: String) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        val req = Request.Builder().url(TOKEN).post(form).build()
        withContext(Dispatchers.IO) {
            Http.client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("Обмен кода: ${resp.code} $body")
                val t = json.decodeFromString<TokenResponse>(body)
                prefs.setTokens(
                    t.accessToken,
                    t.refreshToken,
                    System.currentTimeMillis() + t.expiresIn * 1000,
                    "",
                )
            }
        }
    }

    suspend fun token(): String? {
        val p = prefs.snapshot()
        if (p.spotifyAccess.isBlank() && p.spotifyRefresh.isBlank()) return null
        if (p.spotifyAccess.isNotBlank() && p.spotifyExpiresAt > System.currentTimeMillis() + 30_000) {
            return p.spotifyAccess
        }
        if (p.spotifyRefresh.isBlank() || p.spotifyClientId.isBlank()) return p.spotifyAccess.ifBlank { null }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", p.spotifyRefresh)
            .add("client_id", p.spotifyClientId)
            .build()
        val req = Request.Builder().url(TOKEN).post(form).build()
        return withContext(Dispatchers.IO) {
            try {
                Http.client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext null
                    val t = json.decodeFromString<TokenResponse>(body)
                    val refresh = t.refreshToken.ifBlank { p.spotifyRefresh }
                    val exp = System.currentTimeMillis() + t.expiresIn * 1000
                    prefs.setTokens(t.accessToken, refresh, exp, p.spotifyUser)
                    t.accessToken
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun queue(): QueueData? {
        val t = token() ?: return null
        val q = apiGet<SpotifyQueue>("https://api.spotify.com/v1/me/player/queue", t) ?: return null
        val player = apiGet<SpotifyPlayer>("https://api.spotify.com/v1/me/player", t)
        val current = q.currentlyPlaying ?: player?.item
        return QueueData(
            current = current?.toUi(),
            next = q.queue.map { it.toUi() },
            repeatState = player?.repeatState ?: "off",
            deviceName = player?.device?.name.orEmpty(),
        )
    }

    suspend fun setRepeat(state: String): Boolean {
        val t = token() ?: return false
        val req = Request.Builder()
            .url("https://api.spotify.com/v1/me/player/repeat?state=$state")
            .put(ByteArray(0).toRequestBody())
            .header("Authorization", "Bearer $t")
            .build()
        return withContext(Dispatchers.IO) {
            try {
                Http.client.newCall(req).execute().use { it.isSuccessful || it.code == 204 }
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun playlists(): List<SpotifyPlaylist>? {
        val t = token() ?: return null
        return apiGet<SpotifyPlaylistsResponse>(
            "https://api.spotify.com/v1/me/playlists?limit=50",
            t,
        )?.items
    }

    suspend fun playlistTracks(id: String): List<SpotifyTrack>? {
        val t = token() ?: return null
        return apiGet<SpotifyPlaylistTracksResponse>(
            "https://api.spotify.com/v1/playlists/$id/tracks?limit=100",
            t,
        )?.items?.mapNotNull { it.track }
    }

    suspend fun addToPlaylist(playlistId: String, trackUri: String): Boolean {
        val t = token() ?: return false
        val uri = java.net.URLEncoder.encode(trackUri, "UTF-8")
        val req = Request.Builder()
            .url("https://api.spotify.com/v1/playlists/$playlistId/tracks?uris=$uri")
            .post(ByteArray(0).toRequestBody())
            .header("Authorization", "Bearer $t")
            .build()
        return withContext(Dispatchers.IO) {
            try {
                Http.client.newCall(req).execute().use { it.isSuccessful || it.code == 201 }
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun removeFromPlaylist(playlistId: String, trackUri: String): Boolean {
        val t = token() ?: return false
        val body = """{"tracks":[{"uri":"$trackUri"}]}"""
        val req = Request.Builder()
            .url("https://api.spotify.com/v1/playlists/$playlistId/tracks")
            .delete(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $t")
            .build()
        return withContext(Dispatchers.IO) {
            try {
                Http.client.newCall(req).execute().use { it.isSuccessful || it.code == 200 }
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Starts a single track on the active Spotify device. Requires Premium. */
    suspend fun playTrack(uri: String): Boolean = playerCommand(
        "https://api.spotify.com/v1/me/player/play",
        """{"uris":["$uri"]}""",
    )

    /** Starts a playlist/album/artist context. Requires Premium. */
    suspend fun playContext(contextUri: String): Boolean = playerCommand(
        "https://api.spotify.com/v1/me/player/play",
        """{"context_uri":"$contextUri"}""",
    )

    private suspend fun playerCommand(url: String, body: String): Boolean {
        val t = token() ?: return false
        val req = Request.Builder()
            .url(url)
            .put(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $t")
            .build()
        return withContext(Dispatchers.IO) {
            try {
                Http.client.newCall(req).execute().use { it.isSuccessful || it.code == 204 }
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend inline fun <reified T> apiGet(url: String, token: String): T? {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", app.onyx.core.Http.UA)
            .build()
        return withContext(Dispatchers.IO) {
            try {
                Http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@withContext null
                    json.decodeFromString<T>(body)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun SpotifyTrack.toUi() = TrackUi(
        title = name,
        artist = artists.joinToString(", ") { it.name },
        album = album.name,
        art = album.images.firstOrNull()?.url,
        durationMs = durationMs,
        uri = uri,
    )

    private fun randomString(n: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val rnd = SecureRandom()
        return (1..n).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun base64Url(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    companion object {
        private const val TAG = "ONYX_Spotify"
        private const val REDIRECT = "http://127.0.0.1:8899/callback"
        private const val SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing " +
                "playlist-read-private playlist-read-collaborative " +
                "playlist-modify-private playlist-modify-public"
        private const val AUTH = "https://accounts.spotify.com/authorize"
        private const val TOKEN = "https://accounts.spotify.com/api/token"
    }
}


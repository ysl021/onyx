package app.onyx.sync

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.onyx.core.Http
import app.onyx.core.LikedArtist
import app.onyx.core.LikedTrack
import app.onyx.core.Prefs
import app.onyx.core.PrefsData
import app.onyx.music.LocalLibrary
import app.onyx.music.LocalPlaylist
import app.onyx.music.LocalPlaylists
import app.onyx.music.LocalTrack
import app.onyx.music.PlaylistStreamTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Outcome of a manual transfer, formatted by the settings screen. */
data class SyncResult(
    val ok: Boolean,
    val playlistsAdded: Int = 0,
    val tracksAdded: Int = 0,
    val likesAdded: Int = 0,
    val filesSent: Int = 0,
    val filesReceived: Int = 0,
    val filesFailed: Int = 0,
    val noFolder: Boolean = false,
    /** The PC answered but rejected the saved token. */
    val unauthorized: Boolean = false,
    /** The PC did not answer at all. */
    val offline: Boolean = false,
)

/**
 * Additive sync with the ONYX hub on the PC. Nothing is ever overwritten:
 * playlists are matched by name and only receive the tracks they are missing,
 * likes are unioned, and missing local files are copied when the user allows
 * it. The poll only reacts to an explicit request from the PC.
 */
class SyncClient(
    private val context: android.content.Context,
    private val prefs: Prefs,
    private val playlists: LocalPlaylists,
    private val library: LocalLibrary,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastAppliedRev = 0L

    /** Large files need a much longer write timeout than presence updates. */
    private val uploadClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
        .readTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
        .build()

    private val downloadClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
        .build()

    init {
        scope.launch {
            while (isActive) {
                runCatching { poll() }
                delay(POLL_MS)
            }
        }
    }

    suspend fun pushToPc(): SyncResult {
        val p = prefs.snapshot()
        if (p.discordBridgeHost.isBlank()) return SyncResult(false, offline = true)
        val (code, response) = postForStatus(p, "/v1/sync", JSONObject().put("state", buildState(p)))
        if (code == 0) return SyncResult(false, offline = true)
        if (code == 401) return SyncResult(false, unauthorized = true)
        if (response == null) return SyncResult(false)
        var filesSent = 0
        var filesFailed = 0
        val missing = response.optJSONArray("missing") ?: JSONArray()
        if (p.syncCopyFiles && missing.length() > 0) {
            missing.toObjects().forEach { entry ->
                val key = entry.optString("key")
                val track = library.state.value.tracks.firstOrNull { syncTrackKey(it) == key }
                if (track == null) {
                    filesFailed++
                } else if (uploadFile(p, track, entry)) {
                    filesSent++
                } else {
                    filesFailed++
                }
            }
            if (filesSent > 0) {
                postJson(p, "/v1/library/done", JSONObject().put("count", filesSent))
            }
        }
        return SyncResult(
            ok = true,
            playlistsAdded = response.optInt("playlistsAdded"),
            tracksAdded = response.optInt("tracksAdded"),
            likesAdded = response.optInt("likesAdded"),
            filesSent = filesSent,
            filesFailed = filesFailed,
        )
    }

    suspend fun pullFromPc(): SyncResult {
        val p = prefs.snapshot()
        if (p.discordBridgeHost.isBlank()) return SyncResult(false, offline = true)
        val (code, response) = postForStatus(p, "/v1/sync/pull", JSONObject())
        if (code == 0) return SyncResult(false, offline = true)
        if (code == 401) return SyncResult(false, unauthorized = true)
        val state = response?.optJSONObject("state") ?: return SyncResult(false)
        lastAppliedRev = response.optLong("rev", 0L)
        val merged = applyState(state)
        val files = if (p.syncCopyFiles) downloadMissing(p, state) else DownloadResult()
        return SyncResult(
            ok = true,
            playlistsAdded = merged.playlists,
            tracksAdded = merged.tracks,
            likesAdded = merged.likes,
            filesReceived = files.received,
            filesFailed = files.failed,
            noFolder = files.noFolder,
        )
    }

    fun close() {
        scope.cancel()
    }

    /** Forgets the PC locally and asks the hub to drop this device. */
    suspend fun unpair() {
        val p = prefs.snapshot()
        // Clear locally first: the button must work even when the PC is off.
        prefs.clearPairing()
        if (p.discordBridgeHost.isNotBlank()) {
            withTimeoutOrNull(4_000) {
                runCatching {
                    postJson(
                        p,
                        "/v1/unpair",
                        JSONObject().put("device", android.os.Build.MODEL ?: "Android"),
                    )
                }
            }
        }
    }

    private suspend fun poll() {
        val p = prefs.snapshot()
        if (!p.discordBridgeEnabled || p.discordBridgeHost.isBlank() || p.discordBridgeToken.isBlank()) {
            return
        }
        val response = getJson(p, "/v1/sync?since=$lastAppliedRev") ?: return
        val state = response.optJSONObject("state") ?: return
        val rev = response.optLong("rev", 0L)

        if (state.optString("pendingFor") == "phone" && rev > lastAppliedRev) {
            lastAppliedRev = rev
            applyState(state)
            if (p.syncCopyFiles) downloadMissing(p, state)
            postJson(p, "/v1/sync/ack", JSONObject())
        }

        // The PC pressed "С телефона": upload once, then the hub clears it.
        if (state.optString("requestFrom") == "phone") {
            pushToPc()
        }

        when {
            state.optString("libraryRequest") == "index" -> uploadIndex(p)
            state.optString("libraryRequest").startsWith("files:") -> {
                val ids = state.optString("libraryRequest")
                    .removePrefix("files:")
                    .split(',')
                    .filter { it.isNotBlank() }
                uploadFiles(p, ids)
            }
        }
    }

    private suspend fun uploadIndex(p: PrefsData) {
        val array = JSONArray()
        library.state.value.tracks.forEach { track ->
            array.put(
                JSONObject()
                    .put("id", track.id)
                    .put("title", track.title)
                    .put("artist", track.artist)
                    .put("album", track.album)
                    .put("durationMs", track.durationMs)
                    .put("size", track.size)
                    .put("mime", mimeOf(track.uri)),
            )
        }
        postJson(p, "/v1/library/index", JSONObject().put("tracks", array))
    }

    private suspend fun uploadFiles(p: PrefsData, ids: List<String>) {
        var sent = 0
        ids.forEach { id ->
            val track = library.state.value.tracks.firstOrNull { it.id == id } ?: return@forEach
            if (uploadFile(p, track, null)) sent++
        }
        postJson(p, "/v1/library/done", JSONObject().put("count", sent))
    }

    private fun uploadFile(p: PrefsData, track: LocalTrack, entry: JSONObject?): Boolean {
        val uri = Uri.parse(track.uri)
        val title = entry?.optString("title").orEmpty().ifBlank { track.title }
        val artist = entry?.optString("artist").orEmpty().ifBlank { track.artist }
        val album = entry?.optString("album").orEmpty().ifBlank { track.album }
        val name = entry?.optString("fileName").orEmpty().ifBlank { fileName(track) }
        val url = (baseUrl(p) + "/v1/library/file")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("name", name)
            .addQueryParameter("key", syncTrackKey(track))
            .addQueryParameter("title", title)
            .addQueryParameter("artist", artist)
            .addQueryParameter("album", album)
            .build()
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = track.size
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    sink.writeAll(input.source())
                }
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${p.discordBridgeToken}")
            .post(body)
            .build()
        return runCatching {
            uploadClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun mimeOf(uri: String): String =
        runCatching { context.contentResolver.getType(Uri.parse(uri)) }
            .getOrNull().orEmpty()

    private fun fileName(track: LocalTrack): String {
        val mime = mimeOf(track.uri)
        val ext = when {
            mime.contains("mpeg") -> "mp3"
            mime.contains("flac") -> "flac"
            mime.contains("mp4") || mime.contains("aac") -> "m4a"
            mime.contains("ogg") -> "ogg"
            mime.contains("wav") -> "wav"
            else -> track.uri.substringAfterLast('.', "").take(5).ifBlank { "mp3" }
        }
        val base = listOf(track.artist, track.title)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { "track" }
        return "$base.$ext"
    }

    private fun buildState(p: PrefsData): JSONObject {
        val tracks = library.state.value.tracks
        val playlistsJson = JSONArray()
        playlists.state.value.playlists.forEach { playlist ->
            val entries = JSONArray()
            val streams = playlist.streamTracks.associateBy { it.id }
            playlist.trackIds.forEach { id ->
                if (id.startsWith("yt:")) {
                    val videoId = id.removePrefix("yt:")
                    val meta = streams[id]
                    entries.put(
                        JSONObject()
                            .put("kind", "yt")
                            .put("videoId", videoId)
                            .put("title", meta?.title.orEmpty())
                            .put("artist", meta?.artist.orEmpty())
                            .put("durationMs", meta?.durationMs ?: 0L)
                            .put("thumbnail", meta?.thumbnailUrl ?: JSONObject.NULL),
                    )
                } else {
                    val track = tracks.firstOrNull { it.id == id } ?: return@forEach
                    entries.put(
                        JSONObject()
                            .put("kind", "local")
                            .put("key", syncTrackKey(track))
                            .put("title", track.title)
                            .put("artist", track.artist)
                            .put("album", track.album)
                            .put("durationMs", track.durationMs)
                            .put("fileName", fileName(track)),
                    )
                }
            }
            playlistsJson.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("description", playlist.description)
                    .put("pinned", playlist.pinned)
                    .put("folderId", playlist.folderId ?: JSONObject.NULL)
                    .put("createdAt", playlist.createdAt)
                    .put("updatedAt", playlist.createdAt)
                    .put("deleted", false)
                    .put("entries", entries),
            )
        }

        val likedTracks = JSONArray()
        p.likedTracks.forEach { track ->
            likedTracks.put(
                JSONObject()
                    .put("id", track.id)
                    .put("videoId", track.videoId ?: JSONObject.NULL)
                    .put("title", track.title)
                    .put("artist", track.artist)
                    .put("album", track.album)
                    .put("durationMs", track.durationMs)
                    .put("artwork", track.thumbnailUrl ?: JSONObject.NULL)
                    .put("at", track.at),
            )
        }
        val likedArtists = JSONArray()
        p.likedArtists.forEach { artist ->
            likedArtists.put(
                JSONObject()
                    .put("name", artist.name)
                    .put("thumbnail", artist.thumbnailUrl ?: JSONObject.NULL)
                    .put("browseId", artist.browseId ?: JSONObject.NULL)
                    .put("at", artist.at),
            )
        }
        val likes = JSONObject()
            .put("tracks", likedTracks)
            .put("artists", likedArtists)
            .put(
                "updatedAt",
                maxOf(
                    p.likedTracks.maxOfOrNull { it.at } ?: 0L,
                    p.likedArtists.maxOfOrNull { it.at } ?: 0L,
                ),
            )

        return JSONObject()
            .put("playlists", playlistsJson)
            .put("likes", likes)
            .put("updatedAt", System.currentTimeMillis())
            .put("source", "phone")
    }

    private data class MergeCounts(val playlists: Int, val tracks: Int, val likes: Int)

    private suspend fun applyState(state: JSONObject): MergeCounts {
        val tracks = library.state.value.tracks
        val incoming = mutableListOf<LocalPlaylist>()
        val playlistsJson = state.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until playlistsJson.length()) {
            val playlist = playlistsJson.optJSONObject(i) ?: continue
            if (playlist.optBoolean("deleted")) continue
            val ids = mutableListOf<String>()
            val streams = mutableListOf<PlaylistStreamTrack>()
            val entries = playlist.optJSONArray("entries") ?: JSONArray()
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                if (entry.optString("kind") == "yt") {
                    val videoId = entry.optString("videoId")
                    if (videoId.isBlank()) continue
                    val id = "yt:$videoId"
                    ids += id
                    streams += PlaylistStreamTrack(
                        id = id,
                        videoId = videoId,
                        title = entry.optString("title"),
                        artist = entry.optString("artist"),
                        durationMs = entry.optLong("durationMs"),
                        thumbnailUrl = entry.optString("thumbnail").ifBlank { null },
                    )
                } else {
                    val key = entry.optString("key")
                    tracks.firstOrNull { syncTrackKey(it) == key }?.let { ids += it.id }
                }
            }
            incoming += LocalPlaylist(
                id = playlist.optString("id"),
                name = playlist.optString("name"),
                description = playlist.optString("description"),
                coverPath = null,
                createdAt = playlist.optLong("createdAt"),
                trackIds = ids,
                folderId = playlist.optString("folderId").ifBlank { null },
                pinned = playlist.optBoolean("pinned"),
                streamTracks = streams,
            )
        }
        val merged = playlists.mergeIncoming(incoming)

        val likes = state.optJSONObject("likes") ?: JSONObject()
        val likedTracks = mutableListOf<LikedTrack>()
        val tracksJson = likes.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until tracksJson.length()) {
            val track = tracksJson.optJSONObject(i) ?: continue
            likedTracks += LikedTrack(
                id = track.optString("id"),
                videoId = track.optString("videoId").ifBlank { null },
                uri = null,
                title = track.optString("title"),
                artist = track.optString("artist"),
                album = track.optString("album"),
                durationMs = track.optLong("durationMs"),
                thumbnailUrl = track.optString("artwork").ifBlank { null },
                at = track.optLong("at"),
            )
        }
        val likedArtists = mutableListOf<LikedArtist>()
        val artistsJson = likes.optJSONArray("artists") ?: JSONArray()
        for (i in 0 until artistsJson.length()) {
            val artist = artistsJson.optJSONObject(i) ?: continue
            likedArtists += LikedArtist(
                name = artist.optString("name"),
                thumbnailUrl = artist.optString("thumbnail").ifBlank { null },
                browseId = artist.optString("browseId").ifBlank { null },
                at = artist.optLong("at"),
            )
        }
        val likesAdded = prefs.mergeLikedLists(likedTracks, likedArtists)
        return MergeCounts(merged.playlists, merged.tracks, likesAdded)
    }

    private data class DownloadResult(
        val received: Int = 0,
        val failed: Int = 0,
        val noFolder: Boolean = false,
    )

    /** Copies local files the phone does not have from the PC. */
    private fun downloadMissing(p: PrefsData, state: JSONObject): DownloadResult {
        val entries = mutableListOf<JSONObject>()
        val playlistsJson = state.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until playlistsJson.length()) {
            val playlist = playlistsJson.optJSONObject(i) ?: continue
            if (playlist.optBoolean("deleted")) continue
            val list = playlist.optJSONArray("entries") ?: JSONArray()
            for (j in 0 until list.length()) {
                val entry = list.optJSONObject(j) ?: continue
                if (entry.optString("kind") != "local") continue
                val key = entry.optString("key")
                if (key.isBlank()) continue
                if (library.state.value.tracks.any { syncTrackKey(it) == key }) continue
                if (entries.none { it.optString("key") == key }) entries += entry
            }
        }
        if (entries.isEmpty()) return DownloadResult()
        val folder = library.state.value.folders.firstOrNull()
            ?: return DownloadResult(failed = entries.size, noFolder = true)
        val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folder.uri)) }
            .getOrNull() ?: return DownloadResult(failed = entries.size, noFolder = true)
        var received = 0
        var failed = 0
        entries.forEach { entry ->
            if (downloadTrack(p, tree, entry)) received++ else failed++
        }
        if (received > 0) library.rescan()
        return DownloadResult(received, failed)
    }

    private fun downloadTrack(p: PrefsData, tree: DocumentFile, entry: JSONObject): Boolean {
        val key = entry.optString("key")
        val title = entry.optString("title").ifBlank { "track" }
        val artist = entry.optString("artist")
        val rawName = entry.optString("fileName").ifBlank {
            listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ") + ".mp3"
        }
        val extension = rawName.substringAfterLast('.', "mp3").lowercase(Locale.ROOT)
        val display = rawName.substringBeforeLast('.', rawName)
        val mime = when (extension) {
            "flac" -> "audio/flac"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "audio/mpeg"
        }
        val existing = runCatching { tree.findFile("$display.$extension") }.getOrNull()
        val doc = existing ?: runCatching { tree.createFile(mime, display) }.getOrNull() ?: return false
        val url = (baseUrl(p) + "/v1/library/track")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("key", key)
            .addQueryParameter("name", rawName)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${p.discordBridgeToken}")
            .build()
        return runCatching {
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body ?: return@use false
                context.contentResolver.openOutputStream(doc.uri, "wt")?.use { output ->
                    body.byteStream().copyTo(output)
                } ?: return@use false
                true
            }
        }.getOrDefault(false)
    }

    private fun getJson(p: PrefsData, path: String): JSONObject? {
        val request = Request.Builder()
            .url(baseUrl(p) + path)
            .header("Authorization", "Bearer ${p.discordBridgeToken}")
            .build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    /** POST that keeps the HTTP status so the UI can tell 401 from offline. */
    private fun postForStatus(
        p: PrefsData,
        path: String,
        body: JSONObject,
    ): Pair<Int, JSONObject?> {
        val request = Request.Builder()
            .url(baseUrl(p) + path)
            .header("Authorization", "Bearer ${p.discordBridgeToken}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed = if (text.isBlank()) {
                    null
                } else {
                    runCatching { JSONObject(text) }.getOrNull()
                }
                response.code to parsed
            }
        }.getOrDefault(0 to null)
    }

    private fun postJson(p: PrefsData, path: String, body: JSONObject): JSONObject? {
        val request = Request.Builder()
            .url(baseUrl(p) + path)
            .header("Authorization", "Bearer ${p.discordBridgeToken}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private fun baseUrl(p: PrefsData): String =
        "http://${p.discordBridgeHost.trim()}:${p.discordBridgePort.coerceIn(1, 65535)}"

    private fun syncTrackKey(track: LocalTrack): String =
        syncTrackKey(track.title, track.artist, track.durationMs)

    private fun syncTrackKey(title: String, artist: String, durationMs: Long): String =
        normalize(artist) + "|" + normalize(title) + "|" + (durationMs / 1000L).coerceAtLeast(0L)

    private fun normalize(value: String): String =
        value.lowercase()
            .replace('\u00A0', ' ')
            .replace(Regex("[\\p{Punct}\\s]+"), "")

    private companion object {
        const val POLL_MS = 15_000L
    }
}

private fun JSONArray.toObjects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

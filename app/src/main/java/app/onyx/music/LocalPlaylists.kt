package app.onyx.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.onyx.ui.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A streaming (YouTube) track saved into a playlist without downloading it. */
@Serializable
data class PlaylistStreamTrack(
    val id: String,
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0L,
    val thumbnailUrl: String? = null,
)

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val description: String = "",
    val coverPath: String? = null,
    val createdAt: Long = 0L,
    val trackIds: List<String> = emptyList(),
    val folderId: String? = null,
    val pinned: Boolean = false,
    /** Metadata for `yt:` ids inside [trackIds]; keeps the playlist order. */
    val streamTracks: List<PlaylistStreamTrack> = emptyList(),
)

@Serializable
data class PlaylistFolder(
    val id: String,
    val name: String,
    val createdAt: Long = 0L,
)

@Serializable
private data class PlaylistsFile(
    val playlists: List<LocalPlaylist> = emptyList(),
    val folders: List<PlaylistFolder> = emptyList(),
)

data class PlaylistsState(
    val playlists: List<LocalPlaylist> = emptyList(),
    val folders: List<PlaylistFolder> = emptyList(),
)

/** Result of an additive transfer: new playlists and newly added tracks. */
data class MergeCounts(val playlists: Int, val tracks: Int)

private fun sameName(a: String, b: String): Boolean {
    fun key(value: String) = value.lowercase()
        .replace('\u00A0', ' ')
        .replace(Regex("[\\p{Punct}\\s]+"), "")
    val left = key(a)
    return left.isNotEmpty() && left == key(b)
}

/**
 * User-built playlists persisted to JSON in filesDir. All mutations are
 * serialized on a single IO thread (same discipline as LocalLibrary) so
 * concurrent UI actions can never interleave read-modify-write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalPlaylists(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "local_playlists.json")
    private val coversDir = File(context.filesDir, "playlist_covers").apply { mkdirs() }

    private val _state = MutableStateFlow(PlaylistsState())
    val state: StateFlow<PlaylistsState> = _state.asStateFlow()

    init {
        scope.launch { read() }
    }

    fun create(
        name: String,
        description: String = "",
        folderId: String? = null,
        trackIds: List<String> = emptyList(),
        onCreated: (id: String) -> Unit = {},
    ) {
        val id = UUID.randomUUID().toString()
        scope.launch {
            val playlist = LocalPlaylist(
                id = id,
                name = name.ifBlank { tr("Без названия", "Untitled") },
                description = description,
                createdAt = System.currentTimeMillis(),
                trackIds = trackIds.distinct(),
                folderId = folderId,
            )
            mutate { s ->
                s.copy(playlists = s.playlists + playlist)
            }
            onCreated(id)
        }
    }

    fun createFolder(name: String, onCreated: (id: String) -> Unit = {}) {
        val id = UUID.randomUUID().toString()
        scope.launch {
            mutate { s ->
                s.copy(
                    folders = s.folders + PlaylistFolder(
                        id = id,
                        name = name.ifBlank { tr("Без названия", "Untitled") },
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
            onCreated(id)
        }
    }

    fun rename(id: String, name: String) = editPlaylist(id) { it.copy(name = name) }

    fun setDescription(id: String, description: String) =
        editPlaylist(id) { it.copy(description = description) }

    fun setPinned(id: String, pinned: Boolean) =
        editPlaylist(id) { it.copy(pinned = pinned) }

    fun moveToFolder(id: String, folderId: String?) =
        editPlaylist(id) { it.copy(folderId = folderId) }

    fun addTracks(id: String, trackIds: List<String>) = editPlaylist(id) { playlist ->
        playlist.copy(trackIds = (playlist.trackIds + trackIds).distinct())
    }

    fun addStreamTracks(id: String, tracks: List<PlaylistStreamTrack>) = editPlaylist(id) { playlist ->
        val incomingIds = tracks.map { it.id }
        val mergedMeta = playlist.streamTracks.filterNot { it.id in incomingIds } + tracks
        playlist.copy(
            trackIds = (playlist.trackIds + incomingIds).distinct(),
            streamTracks = mergedMeta,
        )
    }

    fun removeTrack(id: String, trackId: String) = editPlaylist(id) { playlist ->
        playlist.copy(trackIds = playlist.trackIds - trackId)
    }

    fun moveTrack(playlistId: String, from: Int, to: Int) {
        scope.launch {
            mutate { s ->
                val playlist = s.playlists.firstOrNull { it.id == playlistId }
                if (playlist != null && from in playlist.trackIds.indices &&
                    to in playlist.trackIds.indices
                ) {
                    val ids = playlist.trackIds.toMutableList()
                    ids.removeAt(from)
                    ids.add(to, playlist.trackIds[from])
                    val updated = playlist.copy(trackIds = ids)
                    s.copy(playlists = s.playlists.map { if (it.id == playlistId) updated else it })
                } else {
                    s
                }
            }
        }
    }

    fun delete(id: String) {
        scope.launch {
            val removed = _state.value.playlists.firstOrNull { it.id == id }
            mutate { s -> s.copy(playlists = s.playlists.filterNot { it.id == id }) }
            removed?.coverPath?.let { path -> runCatching { File(path).delete() } }
        }
    }

    fun deleteFolder(id: String) {
        scope.launch {
            mutate { s ->
                s.copy(
                    folders = s.folders.filterNot { it.id == id },
                    playlists = s.playlists.map {
                        if (it.folderId == id) it.copy(folderId = null) else it
                    },
                )
            }
        }
    }

    fun renameFolder(id: String, name: String) {
        scope.launch {
            mutate { s ->
                s.copy(folders = s.folders.map {
                    if (it.id == id) it.copy(name = name.ifBlank { it.name }) else it
                })
            }
        }
    }

    fun setCover(id: String, uri: Uri) {
        scope.launch {
            val decoded = decodeCover(uri)
            if (decoded == null) return@launch
            val f = File(coversDir, "$id.jpg")
            try {
                f.outputStream().use { out -> decoded.compress(Bitmap.CompressFormat.JPEG, 88, out) }
                editPlaylist(id) { it.copy(coverPath = f.absolutePath) }
            } finally {
                decoded.recycle()
            }
        }
    }

    fun clearCover(id: String) {
        scope.launch {
            val path = _state.value.playlists.firstOrNull { it.id == id }?.coverPath
            editPlaylist(id) { it.copy(coverPath = null) }
            path?.let { runCatching { File(it).delete() } }
        }
    }

    fun close() {
        scope.cancel()
    }

    /** Replaces playlists after a transfer from the PC. */
    fun replaceAll(playlists: List<LocalPlaylist>) {
        scope.launch {
            mutate { it.copy(playlists = playlists) }
        }
    }

    /** Full snapshot used by the JSON backup. */
    fun snapshot(): PlaylistsState = _state.value

    /** Replaces playlists and folders (backup restore). */
    fun replaceAll(state: PlaylistsState) {
        scope.launch {
            mutate { state }
        }
    }

    /**
     * Additive transfer from the PC. Same-name playlists are kept and only get
     * the tracks they do not have yet; nothing is renamed or overwritten.
     */
    suspend fun mergeIncoming(incoming: List<LocalPlaylist>): MergeCounts = scope.async {
        var addedPlaylists = 0
        var addedTracks = 0
        val now = System.currentTimeMillis()
        mutate { s ->
            val next = s.playlists.toMutableList()
            for (source in incoming) {
                if (source.name.isBlank()) continue
                val index = next.indexOfFirst { sameName(it.name, source.name) }
                if (index < 0) {
                    val copy = source.copy(
                        id = UUID.randomUUID().toString(),
                        coverPath = null,
                        createdAt = if (source.createdAt > 0) source.createdAt else now,
                    )
                    next += copy
                    addedPlaylists++
                    addedTracks += copy.trackIds.size
                    continue
                }
                val existing = next[index]
                val newIds = source.trackIds.filterNot { it in existing.trackIds }
                if (newIds.isEmpty()) continue
                val meta = source.streamTracks.filter { it.id in newIds }
                next[index] = existing.copy(
                    trackIds = existing.trackIds + newIds,
                    streamTracks = existing.streamTracks +
                        meta.filterNot { item -> existing.streamTracks.any { it.id == item.id } },
                )
                addedTracks += newIds.size
            }
            s.copy(playlists = next)
        }
        MergeCounts(addedPlaylists, addedTracks)
    }.await()

    private fun editPlaylist(id: String, transform: (LocalPlaylist) -> LocalPlaylist) {
        scope.launch {
            mutate { s ->
                s.copy(playlists = s.playlists.map {
                    if (it.id == id) transform(it) else it
                })
            }
        }
    }

    private fun mutate(transform: (PlaylistsState) -> PlaylistsState) {
        val next = transform(_state.value)
        _state.value = next
        write(next)
    }

    private fun read() {
        val loaded = runCatching {
            if (!file.exists()) return
            json.decodeFromString<PlaylistsFile>(file.readText())
        }.getOrNull() ?: return
        _state.value = PlaylistsState(loaded.playlists, loaded.folders)
    }

    private fun write(s: PlaylistsState) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(PlaylistsFile(s.playlists, s.folders)))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    /** Downscale a picked photo to at most 1024px so covers never bloat storage. */
    private fun decodeCover(uri: Uri): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching null
        val sample = maxOf(opts.outWidth, opts.outHeight) / 1024f
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = if (sample > 1f) sample.toInt().coerceAtLeast(1) else 1
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOpts)
        }
    }.getOrNull()
}

package app.onyx.music

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.onyx.core.LocalFolder
import app.onyx.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class LocalTrack(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val artPath: String? = null,
    val folderUri: String = "",
    val folderName: String = "",
)

data class LibraryState(
    val folders: List<LocalFolder> = emptyList(),
    val scanning: Boolean = false,
    val progress: Int = 0,
    val tracks: List<LocalTrack> = emptyList(),
    val error: String? = null,
)

@Serializable
private data class LibraryCache(
    val folders: List<LocalFolder> = emptyList(),
    val tracks: List<LocalTrack> = emptyList(),
)

class LocalLibrary(
    private val context: Context,
    private val prefs: Prefs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var job: Job? = null
    private val artDir = File(context.cacheDir, "local_art").apply { mkdirs() }
    private val cacheFile = File(context.filesDir, "local_library.json")

    private data class PendingFile(val file: DocumentFile, val folder: LocalFolder)

    init {
        scope.launch {
            val p = prefs.snapshot()
            _state.value = LibraryState(folders = p.localFolders)
            readCache()
            if (p.localFolders.isNotEmpty()) scan()
        }
    }

    fun addFolder(uri: Uri, name: String) {
        scope.launch {
            val p = prefs.snapshot()
            val folder = LocalFolder(uri.toString(), name)
            val merged = (p.localFolders + folder).distinctBy { it.uri }
            prefs.setLocalFolders(merged)
            _state.value = _state.value.copy(folders = merged)
            scan()
        }
    }

    fun removeFolder(uri: String) {
        job?.cancel()
        scope.launch {
            val p = prefs.snapshot()
            val merged = p.localFolders.filterNot { it.uri == uri }
            prefs.setLocalFolders(merged)
            _state.value = _state.value.copy(
                folders = merged,
                tracks = _state.value.tracks.filterNot { it.folderUri == uri },
            )
            writeCache(merged, _state.value.tracks)
            if (merged.isEmpty()) _state.value = LibraryState()
        }
    }

    fun clearFolder() {
        job?.cancel()
        scope.launch {
            prefs.clearLocalFolder()
            cacheFile.delete()
            _state.value = LibraryState()
        }
    }

    fun rescan() = scan()

    private fun scan() {
        job?.cancel()
        job = scope.launch {
            val folders = prefs.snapshot().localFolders
            if (folders.isEmpty()) {
                _state.value = LibraryState()
                return@launch
            }
            _state.value = _state.value.copy(folders = folders, scanning = true, progress = 0, error = null)
            val files = ArrayList<PendingFile>(512)
            for (folder in folders) {
                val root = try {
                    DocumentFile.fromTreeUri(context, Uri.parse(folder.uri))
                } catch (_: Exception) {
                    null
                }
                if (root == null || !root.exists()) {
                    _state.value = _state.value.copy(
                        error = "Нет доступа к папке «${folder.name}». Добавь её заново.",
                    )
                    continue
                }
                collect(root, folder, files)
            }
            val tracks = ArrayList<LocalTrack>(files.size)
            files.forEachIndexed { index, pending ->
                readTrack(pending.file, pending.folder)?.let { tracks += it }
                if (index % 20 == 0) {
                    _state.value = _state.value.copy(
                        progress = if (files.isEmpty()) 0 else index * 100 / files.size,
                    )
                    yield()
                }
            }
            val sorted = tracks
                .distinctBy { it.id }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            _state.value = _state.value.copy(
                scanning = false,
                progress = 100,
                tracks = sorted,
                error = if (sorted.isEmpty()) "В выбранных папках не найдено аудиофайлов." else null,
            )
            writeCache(folders, sorted)
        }
    }

    private fun collect(dir: DocumentFile, folder: LocalFolder, out: MutableList<PendingFile>) {
        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            return
        }
        for (child in children) {
            val name = child.name.orEmpty()
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                collect(child, folder, out)
            } else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXT) {
                out += PendingFile(child, folder)
            }
        }
    }

    private fun readTrack(file: DocumentFile, folder: LocalFolder): LocalTrack? {
        val uri = file.uri
        val fallback = file.name?.substringBeforeLast('.') ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim().orEmpty()
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim().orEmpty()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val art = retriever.embeddedPicture?.let { bytes ->
                runCatching {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching null
                    val f = File(artDir, sha1(uri.toString()) + ".jpg")
                    f.writeBytes(bytes)
                    f.absolutePath
                }.getOrNull()
            }
            LocalTrack(
                id = sha1(uri.toString()),
                uri = uri.toString(),
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                artPath = art,
                folderUri = folder.uri,
                folderName = folder.name,
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readCache() {
        if (!cacheFile.exists()) return
        runCatching {
            val cache = json.decodeFromString<LibraryCache>(cacheFile.readText())
            if (cache.tracks.isNotEmpty()) {
                _state.value = _state.value.copy(
                    folders = _state.value.folders.ifEmpty { cache.folders },
                    tracks = cache.tracks,
                )
            }
        }
    }

    private fun writeCache(folders: List<LocalFolder>, tracks: List<LocalTrack>) {
        runCatching {
            cacheFile.writeText(json.encodeToString(LibraryCache(folders, tracks)))
        }
    }

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    fun close() {
        job?.cancel()
        scope.cancel()
    }

    companion object {
        private val AUDIO_EXT = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "alac", "aiff", "aif",
        )
    }
}

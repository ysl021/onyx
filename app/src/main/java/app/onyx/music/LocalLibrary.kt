package app.onyx.music

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import app.onyx.core.LocalFolder
import app.onyx.core.Prefs
import app.onyx.ui.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
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
    val custom: Boolean = false,
    /** Last-modified time and size, used to skip unchanged files on rescans. */
    val modified: Long = 0L,
    val size: Long = 0L,
)

@Serializable
data class TrackOverride(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artPath: String? = null,
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

@OptIn(ExperimentalCoroutinesApi::class)
class LocalLibrary(
    private val context: Context,
    private val prefs: Prefs,
) {
    // Single-thread confinement: all mutations of rawTracks/overrides/cache are
    // serialized so concurrent UI actions can never interleave read-modify-write.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** UI installs this to launch MediaStore's delete-confirmation dialog. */
    var deleteConsent: ((IntentSender) -> Unit)? = null

    private var job: Job? = null
    private var rawTracks: List<LocalTrack> = emptyList()
    private var overrides: Map<String, TrackOverride> = emptyMap()
    private val artDir = File(context.cacheDir, "local_art").apply { mkdirs() }
    private val customArtDir = File(context.filesDir, "local_art").apply { mkdirs() }
    private val cacheFile = File(context.filesDir, "local_library.json")
    private val overridesFile = File(context.filesDir, "local_overrides.json")

    private data class PendingFile(val file: DocumentFile, val folder: LocalFolder)

    init {
        scope.launch {
            overrides = readOverrides()
            val p = prefs.snapshot()
            p.localFolders.forEach { folder ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        Uri.parse(folder.uri),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
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

    /** Adds a batch of folders found by [detectMediaFolders]. */
    fun addFolders(folders: List<LocalFolder>) {
        if (folders.isEmpty()) return
        scope.launch {
            val p = prefs.snapshot()
            val merged = (p.localFolders + folders).distinctBy { it.uri }
            prefs.setLocalFolders(merged)
            _state.value = _state.value.copy(folders = merged)
            scan()
        }
    }

    /** Re-reads folders after a backup restore and scans them again. */
    fun reload() {
        scope.launch {
            val p = prefs.snapshot()
            p.localFolders.forEach { folder ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        Uri.parse(folder.uri),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            _state.value = _state.value.copy(folders = p.localFolders)
            readCache()
            if (p.localFolders.isNotEmpty()) scan()
        }
    }

    /**
     * One-tap folder discovery through MediaStore. No SAF picker is involved:
     * the system index already knows every audio file, so we only group it by
     * its containing folder.
     */
    suspend fun detectMediaFolders(): List<LocalFolder> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, LocalFolder>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
            "${MediaStore.Audio.Media.DURATION} >= 30000"
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                null,
            )?.use { cursor ->
                val pathIndex = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                val bucketIndex = cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val relative = if (pathIndex >= 0) cursor.getString(pathIndex)?.trim().orEmpty() else ""
                    val bucket = if (bucketIndex >= 0) cursor.getString(bucketIndex)?.trim().orEmpty() else ""
                    // Group by the top-level folder (Music/, Download/, ...) so
                    // the library shows one pill per library root, not per album.
                    val root = if (relative.isNotBlank()) {
                        relative.substringBefore('/').trimEnd('/') + "/"
                    } else {
                        bucket
                    }
                    if (root.isBlank()) continue
                    val uri = MEDIA_PREFIX + URLEncoder.encode(root, "UTF-8")
                    if (found.containsKey(uri)) continue
                    val name = root.trimEnd('/').ifBlank { bucket }.ifBlank { "Music" }
                    found[uri] = LocalFolder(uri, name)
                }
            }
        }
        found.values.toList()
    }

    fun removeFolder(uri: String) {
        job?.cancel()
        scope.launch {
            val p = prefs.snapshot()
            val merged = p.localFolders.filterNot { it.uri == uri }
            prefs.setLocalFolders(merged)
            rawTracks = rawTracks.filterNot { it.folderUri == uri }
            dropOrphanOverrides()
            _state.value = _state.value.copy(
                folders = merged,
                tracks = rawTracks.map(::applyOverride),
            )
            writeCache(merged, rawTracks)
            if (merged.isEmpty()) {
                rawTracks = emptyList()
                _state.value = LibraryState()
            }
        }
    }

    fun clearFolder() {
        job?.cancel()
        scope.launch {
            prefs.clearLocalFolder()
            cacheFile.delete()
            rawTracks = emptyList()
            dropOrphanOverrides()
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
            val mediaFolders = ArrayList<LocalFolder>()
            val folderErrors = ArrayList<String>()
            for (folder in folders) {
                if (folder.uri.startsWith(MEDIA_PREFIX)) {
                    mediaFolders += folder
                    continue
                }
                val root = try {
                    DocumentFile.fromTreeUri(context, Uri.parse(folder.uri))
                } catch (_: Exception) {
                    null
                }
                if (root == null || !root.exists()) {
                    folderErrors += tr("Нет доступа к папке", "No access to folder") +
                        " «${folder.name}». " + tr("Добавь её заново.", "Add it again.")
                    continue
                }
                collect(root, folder, files)
            }
            val tracks = ArrayList<LocalTrack>(files.size + 64)
            val cachedByUri = rawTracks.associateBy { it.uri }
            var processed = 0
            // Metadata reading is the slow part; run a few files in parallel and
            // reuse cached entries whose size and mtime did not change.
            for (batch in files.chunked(24)) {
                val results = withContext(Dispatchers.IO) {
                    batch.map { pending ->
                        async {
                            val uri = pending.file.uri.toString()
                            val cached = cachedByUri[uri]
                            val modified = pending.file.lastModified()
                            val size = pending.file.length()
                            if (cached != null && cached.modified > 0L &&
                                cached.modified == modified && cached.size == size
                            ) {
                                cached
                            } else {
                                readTrack(pending.file, pending.folder, modified, size)
                            }
                        }
                    }.awaitAll()
                }
                results.filterNotNull().forEach { tracks += it }
                processed += batch.size
                _state.value = _state.value.copy(
                    progress = if (files.isEmpty()) 0 else processed * 100 / files.size,
                )
                yield()
            }
            for (folder in mediaFolders) {
                tracks += scanMediaFolder(folder)
            }
            val seenUris = HashSet<String>(tracks.size)
            val sorted = tracks.asSequence()
                .filter { seenUris.add(it.uri) }
                .distinctBy {
                    "${it.title.lowercase()}|${it.artist.lowercase()}|${it.durationMs}|${it.size}"
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                .toList()
            rawTracks = sorted
            _state.value = _state.value.copy(
                scanning = false,
                progress = 100,
                tracks = sorted.map(::applyOverride),
                error = when {
                    folderErrors.isNotEmpty() -> folderErrors.joinToString("\n")
                    sorted.isEmpty() -> tr("В выбранных папках не найдено аудиофайлов.", "No audio files found in the selected folders.")
                    else -> null
                },
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

    /**
     * Reads one MediaStore folder. The cursor already carries title/artist/album
     * and duration, so only album art needs an extra lookup (cached per album).
     */
    private fun scanMediaFolder(folder: LocalFolder): List<LocalTrack> {
        val key = runCatching {
            URLDecoder.decode(folder.uri.removePrefix(MEDIA_PREFIX), "UTF-8")
        }.getOrDefault("")
        if (key.isBlank()) return emptyList()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
        )
        val hasPath = key.contains('/')
        val selection = when {
            key.endsWith("/") -> "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            hasPath -> "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
            else -> "${MediaStore.Audio.Media.BUCKET_DISPLAY_NAME} = ?"
        }
        val argument = if (key.endsWith("/")) "$key%" else key
        val out = ArrayList<LocalTrack>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(argument),
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleIndex = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durationIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeIndex = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val modifiedIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val nameIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val albumIdIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idIndex)
                    if (mediaId <= 0L) continue
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        mediaId,
                    )
                    val fallback = cursor.getString(nameIndex)
                        ?.substringBeforeLast('.')
                        ?.takeIf { it.isNotBlank() }
                        ?: "Track"
                    val title = cursor.getString(titleIndex)
                        ?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
                    val artist = cursor.getString(artistIndex)?.trim().orEmpty()
                    val album = cursor.getString(albumIndex)?.trim().orEmpty()
                    val duration = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                    val modified = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) * 1000L else 0L
                    val albumId = if (albumIdIndex >= 0) cursor.getLong(albumIdIndex) else 0L
                    out += LocalTrack(
                        id = sha1("media:$uri"),
                        uri = uri.toString(),
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = duration,
                        artPath = saveAlbumArt(albumId),
                        folderUri = folder.uri,
                        folderName = folder.name,
                        modified = modified,
                        size = size,
                    )
                }
            }
        }
        return out
    }

    private fun saveAlbumArt(albumId: Long): String? {
        if (albumId <= 0L) return null
        val file = File(artDir, "album_$albumId.jpg")
        if (file.exists() && file.length() > 0L) return file.absolutePath
        return runCatching {
            val uri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                albumId,
            )
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file.takeIf { it.length() > 0L }?.absolutePath
        }.getOrNull()
    }

    private fun readTrack(
        file: DocumentFile,
        folder: LocalFolder,
        modified: Long = file.lastModified(),
        size: Long = file.length(),
    ): LocalTrack? {
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
                    // Skip the write when the identical cover is already cached:
                    // rescans otherwise rewrite every image every time.
                    if (!(f.exists() && f.length() == bytes.size.toLong())) {
                        f.writeBytes(bytes)
                    }
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
                modified = modified,
                size = size,
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun updateTrack(
        id: String,
        title: String,
        artist: String,
        album: String,
        artUri: Uri? = null,
    ) {
        scope.launch {
            val existing = overrides[id] ?: TrackOverride()
            val artPath = if (artUri != null) copyArt(id, artUri) ?: existing.artPath else existing.artPath
            val next = existing.copy(
                title = title.trim().ifBlank { null },
                artist = artist.trim(),
                album = album.trim(),
                artPath = artPath,
            )
            overrides = overrides + (id to next)
            writeOverrides()
            publishTracks()
        }
    }

    /** Deletes the actual files (via SAF or MediaStore) and drops cached metadata. */
    fun deleteTracks(ids: Collection<String>) {
        if (ids.isEmpty()) return
        scope.launch {
            val targets = rawTracks.filter { it.id in ids }
            val mediaUris = ArrayList<Uri>()
            targets.forEach { track ->
                val uri = Uri.parse(track.uri)
                if (uri.scheme == "content" && uri.authority?.startsWith("media") == true) {
                    mediaUris += uri
                } else {
                    runCatching { DocumentFile.fromSingleUri(context, uri)?.delete() }
                }
                overrides = overrides - track.id
                runCatching { File(artDir, "${track.id}.jpg").delete() }
                runCatching { File(customArtDir, "${track.id}_custom.jpg").delete() }
            }
            writeOverrides()

            var waitingForConsent = false
            if (mediaUris.isNotEmpty()) {
                val launcher = deleteConsent
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && launcher != null) {
                    runCatching {
                        val pending = MediaStore.createDeleteRequest(
                            context.contentResolver,
                            mediaUris,
                        )
                        launcher(pending.intentSender)
                        waitingForConsent = true
                    }.onFailure {
                        mediaUris.forEach { uri ->
                            runCatching { context.contentResolver.delete(uri, null, null) }
                        }
                    }
                } else {
                    mediaUris.forEach { uri ->
                        runCatching { context.contentResolver.delete(uri, null, null) }
                    }
                }
            }
            if (!waitingForConsent) rescan()
        }
    }

    fun resetTrack(id: String) {
        scope.launch {
            val art = overrides[id]?.artPath
            if (art != null && art.startsWith(customArtDir.absolutePath)) {
                runCatching { File(art).delete() }
            }
            overrides = overrides - id
            writeOverrides()
            publishTracks()
        }
    }

    private fun copyArt(id: String, uri: Uri): String? = runCatching {
        val f = File(customArtDir, "${id}_custom.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { out -> input.copyTo(out) }
        } ?: return@runCatching null
        if (f.length() <= 0L) return@runCatching null
        f.absolutePath
    }.getOrNull()

    /**
     * Overrides and custom artwork for tracks that are no longer in any folder are
     * pruned so local_overrides.json and filesDir/local_art cannot grow forever.
     */
    private fun dropOrphanOverrides() {
        val ids = rawTracks.mapTo(HashSet()) { it.id }
        val orphaned = overrides.filterKeys { it !in ids }
        if (orphaned.isEmpty()) return
        orphaned.values.forEach { override ->
            override.artPath
                ?.takeIf { it.startsWith(customArtDir.absolutePath) }
                ?.let { path -> runCatching { File(path).delete() } }
        }
        overrides -= orphaned.keys
        writeOverrides()
    }

    private fun publishTracks() {
        val current = _state.value
        val shown = rawTracks
            .map(::applyOverride)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        _state.value = current.copy(tracks = shown)
        writeCache(current.folders, rawTracks)
    }

    private fun applyOverride(track: LocalTrack): LocalTrack {
        val o = overrides[track.id] ?: return track
        return track.copy(
            title = o.title ?: track.title,
            artist = o.artist ?: track.artist,
            album = o.album ?: track.album,
            artPath = o.artPath ?: track.artPath,
            custom = true,
        )
    }

    private fun readOverrides(): Map<String, TrackOverride> {
        if (!overridesFile.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, TrackOverride>>(overridesFile.readText())
        }.getOrDefault(emptyMap())
    }

    private fun writeOverrides() {
        runCatching {
            overridesFile.writeText(json.encodeToString(overrides))
        }
    }

    private fun readCache() {
        if (!cacheFile.exists()) return
        runCatching {
            val cache = json.decodeFromString<LibraryCache>(cacheFile.readText())
            if (cache.tracks.isNotEmpty()) {
                rawTracks = cache.tracks
                _state.value = _state.value.copy(
                    folders = _state.value.folders.ifEmpty { cache.folders },
                    tracks = cache.tracks.map(::applyOverride),
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
        private const val MEDIA_PREFIX = "mediastore:path="

        private val AUDIO_EXT = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "alac", "aiff", "aif",
        )
    }
}

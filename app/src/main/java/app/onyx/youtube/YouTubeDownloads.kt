package app.onyx.youtube

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Download queue for YouTube chapters.
 *
 * One video is downloaded once, then every selected chapter is cut into its own
 * file, tagged and copied into the user's SAF folder. The queue runs inside
 * [DownloadService] so it survives the app being backgrounded; the UI only
 * observes [state].
 */
object YouTubeDownloads {

    private data class Batch(
        val video: YtVideo,
        val sections: List<YtSection>,
        val folderUri: String,
        val album: String,
        val jobIds: List<String>,
        val onFinished: (List<SavedTrack>) -> Unit,
        val whole: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val queue = ArrayDeque<Batch>()
    private val processing = AtomicBoolean(false)

    private val _state = MutableStateFlow(YouTubeState())
    val state: StateFlow<YouTubeState> = _state.asStateFlow()

    fun enqueue(
        video: YtVideo,
        sections: List<YtSection>,
        folderUri: String,
        album: String,
        onFinished: (List<SavedTrack>) -> Unit,
    ) {
        if (sections.isEmpty()) return
        val jobs = sections.mapIndexed { index, section ->
            DownloadJob(
                id = UUID.randomUUID().toString(),
                videoId = video.id,
                videoTitle = video.title,
                channel = video.channel,
                sectionTitle = section.title,
                index = index,
                total = sections.size,
                startMs = section.startMs,
                endMs = section.endMs,
            )
        }
        _state.update { it.copy(jobs = it.jobs + jobs, busy = true) }
        synchronized(lock) {
            queue.addLast(
                Batch(
                    video = video,
                    sections = sections,
                    folderUri = folderUri,
                    album = album,
                    jobIds = jobs.map { it.id },
                    onFinished = onFinished,
                ),
            )
        }
    }

    /** Downloads the whole audio as a single tagged file, without splitting. */
    fun enqueueWhole(
        video: YtVideo,
        folderUri: String,
        onFinished: (List<SavedTrack>) -> Unit,
    ) {
        val job = DownloadJob(
            id = UUID.randomUUID().toString(),
            videoId = video.id,
            videoTitle = video.title,
            channel = video.channel,
            sectionTitle = video.title,
            index = 0,
            total = 1,
            startMs = 0L,
            endMs = video.durationMs,
        )
        _state.update { it.copy(jobs = it.jobs + job, busy = true) }
        synchronized(lock) {
            queue.addLast(
                Batch(
                    video = video,
                    sections = listOf(YtSection(video.title, 0L, video.durationMs)),
                    folderUri = folderUri,
                    album = video.title,
                    jobIds = listOf(job.id),
                    onFinished = onFinished,
                    whole = true,
                ),
            )
        }
    }

    /** Drains the queue on the calling coroutine. Returns false if another worker owns it. */
    suspend fun processQueue(context: Context): Boolean {
        if (!processing.compareAndSet(false, true)) return false
        cleanupParts(context)
        try {
            while (true) {
                val batch = nextBatch() ?: break
                runBatch(context.applicationContext, batch)
            }
        } finally {
            processing.set(false)
            _state.update { it.copy(busy = false) }
            // Give the UI a moment to show the result, then drop finished jobs.
            main.postDelayed({ if (!processing.get()) clearDone() }, 4_000)
        }
        return true
    }

    fun clearFinished() {
        _state.update { s ->
            s.copy(jobs = s.jobs.filter { it.status != JobStatus.Done && it.status != JobStatus.Error })
        }
    }

    /**
     * Moves a still-queued job up or down in the visible queue. The worker reads
     * this order before every batch, so priority changes apply immediately to
     * the not-yet-started sections and to queued videos.
     */
    fun moveJob(id: String, delta: Int) {
        _state.update { state ->
            val jobs = state.jobs
            val from = jobs.indexOfFirst { it.id == id }
            if (from < 0 || jobs[from].status != JobStatus.Queued) return@update state
            var to = from + delta
            while (to in jobs.indices && jobs[to].status != JobStatus.Queued) to += delta
            if (to !in jobs.indices) return@update state
            val list = jobs.toMutableList()
            val item = list.removeAt(from)
            list.add(to, item)
            state.copy(jobs = list)
        }
    }

    /** Position of a job in the current priority order. */
    private fun positionOf(jobId: String): Int =
        _state.value.jobs.indexOfFirst { it.id == jobId }.let { if (it < 0) Int.MAX_VALUE else it }

    /** Picks the batch whose next section sits highest in the queue. */
    private fun nextBatch(): Batch? = synchronized(lock) {
        if (queue.isEmpty()) return null
        val next = queue.minByOrNull { batch ->
            batch.jobIds.minOfOrNull { positionOf(it) } ?: Int.MAX_VALUE
        } ?: return null
        queue.remove(next)
        next
    }

    /** True while a worker is draining the queue. */
    fun isProcessing(): Boolean = processing.get()

    /** Done jobs disappear on their own so the queue only shows real work. */
    private fun clearDone() {
        _state.update { state ->
            state.copy(jobs = state.jobs.filter { it.status != JobStatus.Done })
        }
    }

    /** Removes leftovers of downloads that were killed mid-flight. */
    private fun cleanupParts(context: Context) {
        runCatching {
            context.cacheDir.listFiles { file -> file.name.startsWith("onyx_yt_") && file.name.endsWith(".part") }
                ?.forEach { it.delete() }
        }
    }

    private suspend fun runBatch(context: Context, batch: Batch) {
        val safeVideoTitle = sanitize(batch.video.title, 60)
        val extension = if (batch.video.audioExt == "webm") "webm" else "m4a"
        val source = File(context.cacheDir, "onyx_yt_${batch.video.id}.$extension")
        val part = File(context.cacheDir, "onyx_yt_${batch.video.id}.$extension.part")
        try {
            if (!source.exists() || source.length() <= 0L) {
                // Download into a .part file: an interrupted batch must never
                // leave a truncated file that the next attempt would reuse.
                runCatching { part.delete() }
                batch.jobIds.forEach { setStatus(it, JobStatus.Downloading, 0f) }
                var lastReported = -1f
                AudioDownloader.download(batch.video.audioUrl, part) { done, total ->
                    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    if (fraction - lastReported >= 0.01f || fraction >= 1f) {
                        lastReported = fraction
                        batch.jobIds.forEach { update(it) { job -> job.copy(progress = fraction) } }
                    }
                }
                if (!part.renameTo(source)) {
                    part.copyTo(source, overwrite = true)
                    runCatching { part.delete() }
                }
            }

            val cover = AudioDownloader.bytes(
                batch.video.thumbnailUrl ?: YouTubeClient.thumbnailFallback(batch.video.id),
            )

            val root = DocumentFile.fromTreeUri(context, Uri.parse(batch.folderUri))
                ?: throw IllegalStateException("РџР°РїРєР° РЅРµРґРѕСЃС‚СѓРїРЅР° / folder is not available")
            val existing = root.findFile(safeVideoTitle)
            val folder = when {
                existing == null -> root.createDirectory(safeVideoTitle) ?: root
                existing.isDirectory -> existing
                else -> root
            }

            val tempDir = File(context.cacheDir, "onyx_yt_split").apply { mkdirs() }
            val created = ArrayList<SavedTrack>()

            if (batch.whole) {
                val jobId = batch.jobIds.first()
                val title = batch.video.title.trim()
                val artist = batch.video.channel.trim()
                // Avoid "Artist - Artist - Title" when YouTube already prefixes
                // the channel in the video title.
                val base = if (artist.isNotEmpty() && !title.startsWith(artist, ignoreCase = true)) {
                    "$artist - $title"
                } else {
                    title
                }
                val displayName = "${sanitize(base, 110)}.$extension"
                try {
                    update(jobId) { it.copy(status = JobStatus.Tagging, progress = 0.75f) }
                    YtTagging.write(
                        file = source,
                        title = title,
                        artist = batch.video.channel,
                        album = batch.album,
                        coverJpeg = cover,
                        cacheDir = context.cacheDir,
                    )
                    update(jobId) { it.copy(status = JobStatus.Saving, progress = 0.9f) }
                    root.findFile(displayName)?.delete()
                    val mime = if (extension == "webm") "audio/webm" else "audio/mp4"
                    val document = root.createFile(mime, displayName)
                        ?: throw IllegalStateException("createFile failed")
                    context.contentResolver.openOutputStream(document.uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("openOutputStream failed")
                    created += SavedTrack(sha1(document.uri.toString()), document.uri.toString(), title, batch.video.channel, document.name ?: displayName)
                    update(jobId) {
                        it.copy(
                            status = JobStatus.Done,
                            progress = 1f,
                            outputName = document.name ?: displayName,
                        )
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("ONYX/YouTube", "whole download failed", t)
                    update(jobId) {
                        it.copy(
                            status = JobStatus.Error,
                            progress = 1f,
                            message = t.message ?: t.javaClass.simpleName,
                        )
                    }
                }
                main.post { batch.onFinished(created) }
                return
            }

            // Process sections in the priority order shown in the queue panel.
            val order = _state.value.jobs.map { it.id }
            val ordered = batch.sections.mapIndexedNotNull { index, section ->
                batch.jobIds.getOrNull(index)?.let { jobId -> section to jobId }
            }.sortedBy { (_, jobId) ->
                order.indexOf(jobId).let { if (it < 0) Int.MAX_VALUE else it }
            }

            ordered.forEachIndexed { index, (section, jobId) ->
                val number = (index + 1).toString().padStart(2, '0')
                val title = section.title.ifBlank { "Track $number" }
                val displayName = "$number - ${sanitize(title, 70)}.${extension}"
                val temp = File(tempDir, "${batch.video.id}_$index.$extension")

                try {
                    update(jobId) { it.copy(status = JobStatus.Splitting, progress = 0.2f, message = null) }
                    runCatching { temp.delete() }
                    AudioSplitter.split(source, temp, section.startMs, section.endMs)

                    update(jobId) { it.copy(status = JobStatus.Tagging, progress = 0.65f) }
                    YtTagging.write(
                        file = temp,
                        title = title,
                        artist = batch.video.channel,
                        album = batch.album,
                        coverJpeg = cover,
                        cacheDir = context.cacheDir,
                    )

                    update(jobId) { it.copy(status = JobStatus.Saving, progress = 0.85f) }
                    folder.findFile(displayName)?.delete()
                    val mime = if (extension == "webm") "audio/webm" else "audio/mp4"
                    val document = folder.createFile(mime, displayName)
                        ?: throw IllegalStateException("createFile failed")
                    context.contentResolver.openOutputStream(document.uri)?.use { output ->
                        temp.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("openOutputStream failed")

                    created += SavedTrack(sha1(document.uri.toString()), document.uri.toString(), title, batch.video.channel, document.name ?: displayName)
                    update(jobId) {
                        it.copy(
                            status = JobStatus.Done,
                            progress = 1f,
                            outputName = document.name ?: displayName,
                        )
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("ONYX/YouTube", "section ${index + 1} failed", t)
                    update(jobId) {
                        it.copy(
                            status = JobStatus.Error,
                            progress = 1f,
                            message = t.message ?: t.javaClass.simpleName,
                        )
                    }
                } finally {
                    runCatching { temp.delete() }
                }
            }

            main.post { batch.onFinished(created) }
        } catch (t: Throwable) {
            android.util.Log.w("ONYX/YouTube", "batch failed", t)
            val message = t.message ?: t.javaClass.simpleName
            batch.jobIds.forEach { id ->
                update(id) { it.copy(status = JobStatus.Error, progress = 1f, message = message) }
            }
        } finally {
            runCatching { source.delete() }
        }
    }

    private fun update(id: String, block: (DownloadJob) -> DownloadJob) {
        _state.update { s ->
            s.copy(jobs = s.jobs.map { if (it.id == id) block(it) else it })
        }
    }

    private fun setStatus(id: String, status: JobStatus, progress: Float) {
        update(id) { it.copy(status = status, progress = progress) }
    }

    private fun sanitize(value: String, max: Int): String {
        val cleaned = value
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trimEnd('.')
            .take(max)
        return cleaned.ifBlank { "track" }
    }

    /** Same id scheme as LocalLibrary, so playlist entries resolve after a rescan. */
    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

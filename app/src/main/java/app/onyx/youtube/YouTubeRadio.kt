package app.onyx.youtube

import android.content.Context
import app.onyx.music.LocalPlaybackController
import app.onyx.music.LocalTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Online streaming with automatic recommendations.
 *
 * Queue entries use `yt://<videoId>`; ExoPlayer resolves the direct audio URL
 * when it opens a track. The whole queue can therefore be built instantly and
 * the next few tracks are resolved in the background, so track changes do not
 * wait for NewPipe extraction.
 */
class YouTubeRadio(
    private val scope: CoroutineScope,
    private val context: Context,
    private val player: LocalPlaybackController,
) {
    private val loading = AtomicBoolean(false)
    private val known: MutableSet<String> = Collections.synchronizedSet(HashSet())

    @Volatile
    private var lastUrl: String? = null

    init {
        player.onQueueNearEnd = { extend() }
    }

    fun start(video: YtVideo, withRadio: Boolean) {
        known.clear()
        known += video.id
        lastUrl = video.url
        loading.set(true)
        scope.launch(Dispatchers.IO) {
            try {
                val seed = pendingTrack(video)
                withContext(Dispatchers.Main) { player.play(seed, listOf(seed)) }
                YouTubeStreamCache.prefetch(video.id)
                if (!withRadio) return@launch
                val related = video.related.ifEmpty { YouTubeClient.related(video.url) }
                    .filter { known.add(it.id) }
                appendPending(related)
            } finally {
                loading.set(false)
            }
        }
    }

    /** Plays an explicit list (artist top songs, album) and keeps radio going. */
    fun playQueue(items: List<YtSearchResult>, fallbackArtist: String) {
        if (items.isEmpty()) return
        known.clear()
        items.forEach { known += it.id }
        lastUrl = items.first().url
        loading.set(true)
        scope.launch(Dispatchers.IO) {
            try {
                // The whole album/playlist lands in the queue at once.
                val tracks = items.map { pendingTrack(it, fallbackArtist) }
                withContext(Dispatchers.Main) { player.play(tracks.first(), tracks) }
                items.take(PREFETCH).forEach { YouTubeStreamCache.prefetch(it.id) }
            } finally {
                loading.set(false)
            }
        }
    }

    private fun extend() {
        if (!loading.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val seedUrl = seedFromQueue() ?: lastUrl ?: return@launch
                var fresh = runCatching { YouTubeClient.related(seedUrl) }
                    .getOrDefault(emptyList())
                    .filter { known.add(it.id) }

                if (fresh.isEmpty()) {
                    lastUrl?.takeIf { it != seedUrl }?.let { previous ->
                        fresh = runCatching { YouTubeClient.related(previous) }
                            .getOrDefault(emptyList())
                            .filter { known.add(it.id) }
                    }
                }

                if (fresh.isEmpty()) {
                    val track = player.state.value.queue.lastOrNull()
                    val query = listOf(track?.artist.orEmpty(), track?.title.orEmpty())
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    fresh = runCatching { YouTubeClient.search(query, music = true) }
                        .getOrNull()
                        ?.items
                        ?.filter { known.add(it.id) }
                        .orEmpty()
                }

                if (fresh.isEmpty()) return@launch
                appendPending(fresh)
            } finally {
                loading.set(false)
            }
        }
    }

    private suspend fun appendPending(items: List<YtSearchResult>) {
        val batch = items.take(RADIO_BATCH)
        if (batch.isEmpty()) return
        val tracks = batch.map { pendingTrack(it, "") }
        withContext(Dispatchers.Main) { player.appendToQueue(tracks) }
        batch.take(PREFETCH).forEach { YouTubeStreamCache.prefetch(it.id) }
        lastUrl = batch.last().url
    }

    private fun seedFromQueue(): String? {
        val track = player.state.value.queue.lastOrNull() ?: return null
        val id = track.id.removePrefix("yt:")
        return if (id.length == 11) "https://www.youtube.com/watch?v=$id" else null
    }

    private fun pendingTrack(video: YtVideo): LocalTrack = pendingTrack(
        id = video.id,
        title = video.title,
        artist = video.channel,
        durationMs = video.durationMs,
        thumbnailUrl = video.thumbnailUrl,
    )

    private fun pendingTrack(item: YtSearchResult, fallbackArtist: String): LocalTrack =
        pendingTrack(
            id = item.id,
            title = item.title,
            artist = item.channel.ifBlank { fallbackArtist },
            durationMs = item.durationMs,
            thumbnailUrl = item.thumbnailUrl,
        )

    private fun pendingTrack(
        id: String,
        title: String,
        artist: String,
        durationMs: Long,
        thumbnailUrl: String?,
    ): LocalTrack = LocalTrack(
        id = "yt:$id",
        uri = "yt://$id",
        title = title.ifBlank { id },
        artist = artist,
        album = "YouTube",
        durationMs = durationMs,
        artPath = cachedArt(id) ?: thumbnailUrl,
        folderName = "YouTube",
    )

    private fun cachedArt(videoId: String): String? {
        val file = File(context.cacheDir, "yt-art").resolve("$videoId.jpg")
        return file.takeIf { it.exists() && it.length() > 0L }?.absolutePath
    }

    private companion object {
        const val RADIO_BATCH = 10
        const val PREFETCH = 4
    }
}

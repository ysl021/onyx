package app.onyx.youtube

import android.content.Context
import app.onyx.music.LocalPlaybackController
import app.onyx.music.LocalTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Online streaming with automatic recommendations.
 *
 * The seed video is resolved to a direct audio URL and played by the same
 * ExoPlayer session as local files. Related videos from YouTube become the
 * queue; when it gets close to the end another batch is resolved and appended.
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
        scope.launch(Dispatchers.IO) {
            val first = toTrack(video) ?: return@launch
            if (!withRadio) {
                withContext(Dispatchers.Main) { player.play(first, listOf(first)) }
                return@launch
            }
            val related = video.related.ifEmpty { YouTubeClient.related(video.url) }
            val batch = related.take(8)
            val tracks = resolve(batch)
            lastUrl = batch.lastOrNull()?.url ?: video.url
            withContext(Dispatchers.Main) {
                player.play(first, listOf(first) + tracks)
            }
        }
    }

    private fun extend() {
        if (!loading.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val url = lastUrl ?: return@launch
                val related = YouTubeClient.related(url)
                val fresh = related.filter { known.add(it.id) }
                if (fresh.isEmpty()) return@launch
                val tracks = resolve(fresh.take(5))
                if (tracks.isNotEmpty()) {
                    lastUrl = fresh.last().url
                    withContext(Dispatchers.Main) { player.appendToQueue(tracks) }
                }
            } finally {
                loading.set(false)
            }
        }
    }

    private suspend fun resolve(items: List<YtSearchResult>): List<LocalTrack> = coroutineScope {
        items.map { item ->
            async {
                runCatching { YouTubeClient.resolve(item.url) }
                    .getOrNull()
                    ?.let { video ->
                        known += video.id
                        toTrack(video)
                    }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun toTrack(video: YtVideo): LocalTrack? = withContext(Dispatchers.IO) {
        if (video.audioUrl.isBlank()) return@withContext null
        LocalTrack(
            id = "yt:${video.id}",
            uri = video.audioUrl,
            title = video.title.ifBlank { video.id },
            artist = video.channel,
            album = "YouTube",
            durationMs = video.durationMs,
            artPath = cacheArt(video),
            folderName = "YouTube",
        )
    }

    private fun cacheArt(video: YtVideo): String? {
        val url = video.thumbnailUrl ?: return null
        val file = File(context.cacheDir, "yt-art").apply { mkdirs() }
            .resolve("${video.id}.jpg")
        if (file.exists() && file.length() > 0L) return file.absolutePath
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", YouTubeHttp.USER_AGENT)
                .build()
            YouTubeHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.bytes() ?: return null
                file.writeBytes(body)
                file.absolutePath
            }
        }.getOrNull()
    }
}

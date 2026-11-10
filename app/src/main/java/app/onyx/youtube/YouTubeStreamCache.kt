package app.onyx.youtube

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the direct googlevideo URL for a video id once and shares it between
 * the prefetcher and ExoPlayer. A queue can be filled with `yt://<id>` entries
 * instantly; the actual extraction only happens when a track is played, and
 * the next few tracks are resolved in the background so transitions are fast.
 */
object YouTubeStreamCache {

    private const val TTL_MS = 2 * 60 * 60 * 1000L

    private data class Entry(val url: String, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun cached(videoId: String): String? {
        val entry = cache[videoId] ?: return null
        if (System.currentTimeMillis() - entry.at > TTL_MS) {
            cache.remove(videoId)
            return null
        }
        return entry.url
    }

    /** Blocks until the stream URL is known; used by the ExoPlayer data source. */
    fun resolveBlocking(videoId: String): String? {
        cached(videoId)?.let { return it }
        while (true) {
            inFlight[videoId]?.let { pending ->
                return runBlocking { pending.await() }
            }
            val pending = CompletableDeferred<String?>()
            if (inFlight.putIfAbsent(videoId, pending) != null) continue
            return try {
                val video = runBlocking {
                    YouTubeClient.resolve("https://www.youtube.com/watch?v=$videoId")
                }
                val url = video.audioUrl.takeIf { it.isNotBlank() }
                if (url != null) {
                    cache[videoId] = Entry(url, System.currentTimeMillis())
                }
                pending.complete(url)
                url
            } catch (t: Throwable) {
                pending.complete(null)
                null
            } finally {
                inFlight.remove(videoId, pending)
            }
        }
    }

    fun prefetch(videoId: String) {
        if (videoId.length != 11) return
        if (cached(videoId) != null || inFlight.containsKey(videoId)) return
        scope.launch { resolveBlocking(videoId) }
    }
}

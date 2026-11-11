package app.onyx.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeMusicSearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

/** Shared HTTP client: NewPipe extraction, audio download and cover fetch. */
internal object YouTubeHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}

/**
 * NewPipe's extractor talks HTTP through this adapter so the app keeps a single
 * OkHttp stack (and a single timeout policy) instead of pulling its own copy.
 */
private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val payload = request.dataToSend()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(
                method,
                if (method == "GET" || method == "HEAD") {
                    null
                } else {
                    (payload ?: ByteArray(0)).toRequestBody()
                },
            )
        request.headers().forEach { (name, values) ->
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { response ->
            val body = if (method == "HEAD") "" else response.body?.string().orEmpty()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                body,
                response.request.url.toString(),
            )
        }
    }
}

/**
 * Thin, testable wrapper around NewPipeExtractor: search, resolve a video and
 * pick the best audio-only stream. Everything runs on the IO dispatcher and
 * throws on failure — callers decide how to surface the error.
 */
object YouTubeClient {

    private val initLock = Any()

    @Volatile
    private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            NewPipe.init(OkHttpDownloader(YouTubeHttp.client), Localization("en", "US"))
            initialized = true
        }
    }

    private fun service() = NewPipe.getService(ServiceList.YouTube.serviceId)

    /**
     * Search is done by [YouTubeSearch] (direct HTML/innerTube) because the
     * extractor's search breaks on current YouTube layouts; only stream
     * resolution still goes through NewPipe.
     *
     * Music mode uses the same HTML strategy against `music.youtube.com`:
     * NewPipe's music extractor NPEs on the current layout, so it cannot be
     * used for search.
     */
    suspend fun search(query: String, page: String? = null, music: Boolean = false): YtSearchPage {
        val result = if (music) {
            YouTubeSearch.searchMusic(query, page)
        } else {
            YouTubeSearch.search(query, page)
        }
        return YtSearchPage(items = result.items, next = result.continuation)
    }

    suspend fun resolve(rawUrl: String, preferNewPipe: Boolean = false): YtVideo = withContext(Dispatchers.IO) {
        ensureInit()
        val url = normalizeUrl(rawUrl)
        val id = videoIdOf(url)

        // innerTube answers in well under a second, while NewPipe's extractor
        // can spend 5-20 seconds on the same video. Trust the fast path here;
        // if googlevideo rejects the URL, the ExoPlayer data source catches
        // the 403 and re-resolves this video through NewPipe (preferNewPipe).
        // Probing every innerTube URL first made normal playback noticeably
        // slower, so the fallback now happens only on a real failure.
        if (!preferNewPipe) {
            resolveWithInnertube(id)?.let { return@withContext it }
        }
        val failure = runCatching { resolveWithNewPipe(url, id) }
        failure.getOrNull()?.let {
            println("[yt] $id: resolved via NewPipe")
            return@withContext it
        }

        val error = failure.exceptionOrNull()
        throw error ?: IllegalStateException("YouTube did not return a playable audio stream")
    }

    private fun resolveWithNewPipe(url: String, id: String): YtVideo {
        val info = StreamInfo.getInfo(url)
        val audio = info.audioStreams
            .filter { it.isUrl && it.content.isNotBlank() }
            .sortedWith(
                compareByDescending<AudioStream> { it.format?.mimeType == "audio/mp4" }
                    .thenByDescending { it.averageBitrate },
            )
            .firstOrNull()
            ?: throw IllegalStateException("YouTube did not return a playable audio stream")

        val durationMs = (info.duration.coerceAtLeast(0L)) * 1000L
        val chapters = chaptersOf(info.streamSegments, durationMs)
        return YtVideo(
            id = id,
            url = url,
            title = info.name.orEmpty().ifBlank { "YouTube" },
            channel = info.uploaderName.orEmpty(),
            durationMs = durationMs,
            thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url?.takeIf { it.isNotBlank() },
            description = info.description?.content.orEmpty(),
            audioUrl = audio.content,
            audioExt = (audio.format?.suffix ?: "m4a").lowercase(),
            audioBitrate = audio.averageBitrate,
            chapters = chapters,
            related = info.relatedStreams
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toSearchResult() },
        )
    }

    private fun StreamInfoItem.toSearchResult(): YtSearchResult? {
        val videoId = videoIdOf(url)
        if (videoId.isBlank()) return null
        val fullUrl = when {
            url.startsWith("http", ignoreCase = true) -> url
            url.startsWith("/") -> "https://www.youtube.com$url"
            else -> normalizeUrl(url)
        }
        return YtSearchResult(
            id = videoId,
            url = fullUrl,
            title = name.orEmpty(),
            channel = uploaderName.orEmpty(),
            durationMs = duration * 1000L,
            thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
        )
    }

    /** Related videos for a resolved track; used when the radio needs more. */
    suspend fun related(rawUrl: String): List<YtSearchResult> = withContext(Dispatchers.IO) {
        ensureInit()
        runCatching {
            val info = StreamInfo.getInfo(normalizeUrl(rawUrl))
            info.relatedStreams
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toSearchResult() }
        }.getOrDefault(emptyList())
    }

    private data class Client(
        val name: String,
        val version: String,
        val extra: String = "",
        val userAgent: String = YouTubeHttp.USER_AGENT,
    )

    /**
     * Direct innerTube player request. Returns null when every client is
     * blocked or returns ciphered URLs only.
     */
    private fun resolveWithInnertube(videoId: String): YtVideo? {
        val clients = listOf(
            Client(
                name = "ANDROID",
                version = "19.44.38",
                extra = "\"androidSdkVersion\":30,",
                userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 13) gzip",
            ),
            Client(
                name = "IOS",
                version = "19.45.4",
                extra = "\"deviceModel\":\"iPhone16,2\",",
                userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_0 like Mac OS X)",
            ),
            Client(
                name = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                version = "2.0",
                userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
            ),
            Client(name = "WEB", version = "2.20260101.00.00"),
        )
        for (client in clients) {
            val parsed = runCatching { innertubePlayer(videoId, client) }.getOrNull() ?: continue
            return parsed
        }
        return null
    }

    private fun innertubePlayer(videoId: String, client: Client): YtVideo? {
        val body = buildString {
            append("{\"context\":{\"client\":{\"clientName\":\"")
            append(client.name)
            append("\",\"clientVersion\":\"")
            append(client.version)
            append("\",")
            append(client.extra)
            append("\"hl\":\"en\",\"gl\":\"US\"}},\"videoId\":\"")
            append(videoId)
            append("\",\"contentCheckOk\":true,\"racyCheckOk\":true}")
        }
        val request = okhttp3.Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", client.userAgent)
            .header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", clientHeader(client.name))
            .header("X-Youtube-Client-Version", client.version)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val json = YouTubeHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            runCatching { org.json.JSONObject(response.body?.string().orEmpty()) }.getOrNull()
        } ?: return null

        val status = json.optJSONObject("playabilityStatus")
        val state = status?.optString("status").orEmpty()
        if (state.isNotEmpty() && state != "OK") return null

        val details = json.optJSONObject("videoDetails")
        val formats = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
            ?: json.optJSONObject("streamingData")?.optJSONArray("formats")
            ?: return null

        data class Candidate(val url: String, val mime: String, val bitrate: Int)

        val candidates = ArrayList<Candidate>()
        for (i in 0 until formats.length()) {
            val item = formats.optJSONObject(i) ?: continue
            val mime = item.optString("mimeType")
            if (!mime.startsWith("audio/")) continue
            val direct = item.optString("url")
            if (direct.isBlank()) continue // signatureCipher needs JS deciphering
            candidates += Candidate(direct, mime, item.optInt("bitrate", 0))
        }
        val best = candidates
            .sortedWith(
                compareByDescending<Candidate> { it.mime.startsWith("audio/mp4") }
                    .thenByDescending { it.bitrate },
            )
            .firstOrNull() ?: return null

        val title = details?.optString("title").orEmpty().ifBlank { "YouTube" }
        val channel = details?.optString("author").orEmpty()
        val durationMs = (details?.optLong("lengthSeconds", 0L) ?: 0L) * 1000L
        val description = details?.optString("shortDescription").orEmpty()
        val extension = if (best.mime.contains("webm")) "webm" else "m4a"
        val parsed = TimecodeParser.parse(description, durationMs)

        return YtVideo(
            id = videoId,
            url = "https://www.youtube.com/watch?v=$videoId",
            title = title,
            channel = channel,
            durationMs = durationMs,
            thumbnailUrl = YouTubeClient.thumbnailFallback(videoId),
            description = description,
            audioUrl = best.url,
            audioExt = extension,
            audioBitrate = best.bitrate / 1000,
            chapters = if (parsed.size >= 2) parsed else emptyList(),
        )
    }

    private fun clientHeader(name: String): String = when (name) {
        "ANDROID" -> "3"
        "IOS" -> "5"
        "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> "85"
        else -> "1"
    }

    /**
     * YouTube's own chapter markers (the ones shown on the progress bar). They
     * are more reliable than scraping the description: a video can have
     * chapters while the description text itself is empty or truncated.
     */
    private fun chaptersOf(
        segments: List<org.schabi.newpipe.extractor.stream.StreamSegment>,
        durationMs: Long,
    ): List<YtSection> {
        val starts = segments
            .mapNotNull { segment ->
                val title = segment.title?.trim().orEmpty()
                val start = segment.startTimeSeconds.toLong() * 1000L
                if (title.isEmpty() || start < 0L) null else start to title
            }
            .sortedBy { it.first }
            .distinctBy { it.first }
        if (starts.size < 2) return emptyList()
        return starts.mapIndexed { index, (start, title) ->
            val end = starts.getOrNull(index + 1)?.first ?: durationMs
            YtSection(title = title, startMs = start, endMs = end)
        }.filter { it.endMs > it.startMs }
    }

    /** Accepts a full watch URL, a short link, a `/shorts/` link or a bare id. */
    fun normalizeUrl(input: String): String {
        val value = input.trim()
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (Regex("""^[A-Za-z0-9_-]{11}$""").matches(value)) {
            return "https://www.youtube.com/watch?v=$value"
        }
        return if (value.isBlank()) value else "https://$value"
    }

    fun videoIdOf(url: String): String {
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
            Regex("""/embed/([A-Za-z0-9_-]{11})"""),
            Regex("""/live/([A-Za-z0-9_-]{11})"""),
        )
        patterns.forEach { pattern ->
            pattern.find(url)?.let { return it.groupValues[1] }
        }
        val bare = Regex("""^[A-Za-z0-9_-]{11}$""").find(url.trim())
        return bare?.value ?: url.hashCode().toUInt().toString(16)
    }

    fun thumbnailFallback(videoId: String): String =
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    /** Logs the real cause for the rare cases when UI only shows a short message. */
    fun describe(t: Throwable): String {
        val message = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        Log.w("ONYX/YouTube", "extraction failed", t)
        return message
    }
}

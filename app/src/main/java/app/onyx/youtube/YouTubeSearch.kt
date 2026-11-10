package app.onyx.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * YouTube search without NewPipe's search extractor.
 *
 * The extractor assumes the old `twoColumnSearchResultsRenderer` layout, which
 * YouTube now serves only to some clients — search then dies with an NPE.
 * Here the results page is fetched once, `ytInitialData` is sliced out of the
 * HTML and every `videoRenderer` is collected by walking the JSON tree, so a
 * layout change no longer breaks search.
 *
 * "More" uses the innerTube continuation token with the client version taken
 * from the same page (`ytcfg`), which is what the web player itself sends.
 */
object YouTubeSearch {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Result(val items: List<YtSearchResult>, val continuation: String?)

    private class Config(
        val apiKey: String?,
        val clientVersion: String?,
    )

    @Volatile
    private var cachedConfig: Config? = null

    suspend fun search(query: String, continuation: String? = null): Result =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext Result(emptyList(), null)
            if (continuation != null) {
                return@withContext loadMore(continuation)
            }

            val html = fetchResultsPage(q)
            val initial = extractJson(html, "ytInitialData", "window[\"ytInitialData\"]")
            if (initial != null) {
                val root = runCatching { JSONObject(initial) }.getOrNull()
                val videos = ArrayList<YtSearchResult>()
                if (root != null) collectVideos(root, videos)
                if (videos.isNotEmpty()) {
                    val token = findContinuation(root) ?: findContinuationInHtml(html)
                    return@withContext Result(videos, token)
                }
            }

            // Fallback: ask innerTube directly. Some rollouts serve an almost
            // empty HTML page but still answer the JSON endpoint.
            val apiKey = regexValue(html, "INNERTUBE_API_KEY")
            val clientVersion = regexValue(html, "INNERTUBE_CLIENT_VERSION")
            cachedConfig = Config(apiKey, clientVersion)
            val videos = ArrayList<YtSearchResult>()
            val response = innerTubeSearch(q, apiKey, clientVersion)
            if (response != null) collectVideos(response, videos)
            Result(videos, findContinuation(response))
        }

    private fun fetchResultsPage(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return fetchPage(
            "https://www.youtube.com/results?search_query=$encoded&hl=en&gl=US&ucbcb=1",
            "https://www.youtube.com/",
        )
    }

    private fun fetchPage(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", YouTubeHttp.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", referer)
            .build()
        return YouTubeHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    // ------------------------------------------------------------------ music

    private var musicConfig: Config? = null

    /**
     * Music-only search. NewPipe's music extractor NPEs on the current
     * `music.youtube.com` layout, so this follows the same strategy as the
     * regular search: fetch the page once, slice `ytInitialData` and walk it.
     */
    suspend fun searchMusic(query: String, continuation: String? = null): Result =
        withContext(Dispatchers.IO) {
            if (continuation != null) return@withContext loadMoreMusic(continuation)
            val q = query.trim()
            if (q.isEmpty()) return@withContext Result(emptyList(), null)
            val html = fetchMusicPage(q)
            val initial = extractJson(html, "ytInitialData", "window[\"ytInitialData\"]")
            val root = initial?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return@withContext Result(emptyList(), null)
            musicConfig = Config(
                regexValue(html, "INNERTUBE_API_KEY"),
                regexValue(html, "INNERTUBE_CLIENT_VERSION"),
            )
            val videos = ArrayList<YtSearchResult>()
            collectMusicItems(root, videos)
            Result(videos, findContinuation(root))
        }

    private fun fetchMusicPage(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return fetchPage(
            "https://music.youtube.com/search?q=$encoded&sp=$MUSIC_VIDEOS_PARAM&hl=en&gl=US",
            "https://music.youtube.com/",
        )
    }

    private fun loadMoreMusic(continuation: String): Result {
        val config = musicConfig ?: Config(null, null)
        val body = buildString {
            append("{\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"")
            append(escape(config.clientVersion ?: FALLBACK_MUSIC_CLIENT_VERSION))
            append("\",\"hl\":\"en\",\"gl\":\"US\"}},\"continuation\":\"")
            append(escape(continuation))
            append("\"}")
        }
        val response = musicInnerTubePost(body, config) ?: return Result(emptyList(), null)
        val videos = ArrayList<YtSearchResult>()
        collectMusicItems(response, videos)
        return Result(videos, findContinuation(response))
    }

    private fun musicInnerTubePost(body: String, config: Config): JSONObject? {
        val url = buildString {
            append("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            if (!config.apiKey.isNullOrBlank()) append("&key=").append(config.apiKey)
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", YouTubeHttp.USER_AGENT)
            .header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", config.clientVersion ?: FALLBACK_MUSIC_CLIENT_VERSION)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .post(body.toRequestBody(JSON))
            .build()
        return YouTubeHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val text = response.body?.string().orEmpty()
            runCatching { JSONObject(text) }.getOrNull()
        }
    }

    private fun collectMusicItems(node: Any?, out: MutableList<YtSearchResult>, depth: Int = 0) {
        if (node == null || depth > 50 || out.size >= 80) return
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")?.let { parseMusicItem(it, out) }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectMusicItems(node.opt(keys.next()), out, depth + 1)
                    if (out.size >= 80) return
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectMusicItems(node.opt(i), out, depth + 1)
                    if (out.size >= 80) return
                }
            }
        }
    }

    private fun parseMusicItem(renderer: JSONObject, out: MutableList<YtSearchResult>) {
        val id = renderer.optJSONObject("playlistItemData")
            ?.optString("videoId")
            ?.takeIf { it.length == 11 }
            ?: return
        if (out.any { it.id == id }) return
        val columns = renderer.optJSONArray("flexColumns") ?: return

        fun column(index: Int): String {
            val column = columns.optJSONObject(index) ?: return ""
            val text = column.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?: return ""
            return textOf(text)
        }

        val title = column(0).ifBlank { return }
        val subtitle = column(1)
        val durationText = renderer.optJSONArray("fixedColumns")
            ?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
            ?.optJSONObject("text")
            ?.let { textOf(it) }
            ?: Regex("""\d{1,2}:\d{2}(?::\d{2})?""").find(subtitle)?.value
        val artist = subtitle.substringBefore(" • ").trim().ifBlank { subtitle.trim() }
        val thumbnail = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.let { bestThumbnail(it) }
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        out += YtSearchResult(
            id = id,
            url = "https://www.youtube.com/watch?v=$id",
            title = title,
            channel = artist,
            durationMs = parseDuration(durationText),
            thumbnailUrl = thumbnail,
        )
    }

    private fun loadMore(continuation: String): Result {
        val config = cachedConfig ?: Config(null, null)
        val response = innerTubeContinuation(continuation, config)
            ?: return Result(emptyList(), null)
        val videos = ArrayList<YtSearchResult>()
        collectVideos(response, videos)
        return Result(videos, findContinuation(response))
    }

    private fun innerTubeSearch(query: String, apiKey: String?, clientVersion: String?): JSONObject? {
        validConfig(apiKey, clientVersion)?.let { cachedConfig = it }
        val attempts = listOf(
            "WEB" to (clientVersion ?: FALLBACK_CLIENT_VERSION),
            "ANDROID" to "19.44.38",
            "IOS" to "19.45.4",
        )
        for ((clientName, version) in attempts) {
            val body = buildString {
                append("{\"context\":{\"client\":{\"clientName\":\"")
                append(clientName)
                append("\",\"clientVersion\":\"")
                append(escape(version))
                append("\",\"hl\":\"en\",\"gl\":\"US\"}},\"query\":\"")
                append(escape(query))
                append("\"}")
            }
            val json = innerTubePost(body, apiKey, version) ?: continue
            if (hasVideo(json)) return json
        }
        return null
    }

    private fun hasVideo(node: Any?, depth: Int = 0): Boolean {
        if (node == null || depth > 40) return false
        return when (node) {
            is JSONObject -> {
                if (node.has("videoRenderer") || node.has("compactVideoRenderer")) {
                    true
                } else {
                    val keys = node.keys()
                    var found = false
                    while (keys.hasNext() && !found) {
                        found = hasVideo(node.opt(keys.next()), depth + 1)
                    }
                    found
                }
            }
            is JSONArray -> {
                var found = false
                var i = 0
                while (i < node.length() && !found) {
                    found = hasVideo(node.opt(i), depth + 1)
                    i++
                }
                found
            }
            else -> false
        }
    }

    private fun innerTubeContinuation(continuation: String, config: Config): JSONObject? {
        val body = buildString {
            append("{\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"")
            append(escape(config.clientVersion ?: FALLBACK_CLIENT_VERSION))
            append("\",\"hl\":\"en\",\"gl\":\"US\"}},\"continuation\":\"")
            append(escape(continuation))
            append("\"}")
        }
        return innerTubePost(body, config.apiKey, config.clientVersion)
    }

    private fun validConfig(apiKey: String?, clientVersion: String?): Config? =
        if (apiKey.isNullOrBlank() && clientVersion.isNullOrBlank()) null
        else Config(apiKey, clientVersion)

    private fun innerTubePost(body: String, apiKey: String?, clientVersion: String?): JSONObject? {
        val url = buildString {
            append("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            if (!apiKey.isNullOrBlank()) append("&key=").append(apiKey)
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", YouTubeHttp.USER_AGENT)
            .header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", clientVersion ?: FALLBACK_CLIENT_VERSION)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .post(body.toRequestBody(JSON))
            .build()
        return YouTubeHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val text = response.body?.string().orEmpty()
            runCatching { JSONObject(text) }.getOrNull()
        }
    }

    /**
     * Depth-first walk: collects every videoRenderer (plus compactVideoRenderer)
     * regardless of which shelf it is nested in. Without a layout assumption a
     * YouTube experiment cannot break this.
     */
    private fun collectVideos(node: Any?, out: MutableList<YtSearchResult>, depth: Int = 0) {
        if (node == null || depth > 40 || out.size >= 80) return
        when (node) {
            is JSONObject -> {
                if (node.has("videoRenderer")) {
                    parseVideo(node.optJSONObject("videoRenderer"), out)
                } else if (node.has("compactVideoRenderer")) {
                    parseVideo(node.optJSONObject("compactVideoRenderer"), out)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectVideos(node.opt(keys.next()), out, depth + 1)
                    if (out.size >= 80) return
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectVideos(node.opt(i), out, depth + 1)
                    if (out.size >= 80) return
                }
            }
        }
    }

    private fun parseVideo(renderer: JSONObject?, out: MutableList<YtSearchResult>) {
        if (renderer == null) return
        val id = renderer.optString("videoId")
        if (id.length != 11) return
        if (out.any { it.id == id }) return
        val title = textOf(renderer.optJSONObject("title"))
        if (title.isBlank()) return
        val channel = textOf(renderer.optJSONObject("ownerText"))
            .ifBlank { textOf(renderer.optJSONObject("longBylineText")) }
            .ifBlank { textOf(renderer.optJSONObject("shortBylineText")) }
        val duration = parseDuration(
            renderer.optJSONObject("lengthText")?.optString("simpleText")
                ?: textOf(renderer.optJSONObject("lengthText")),
        )
        out += YtSearchResult(
            id = id,
            url = "https://www.youtube.com/watch?v=$id",
            title = title,
            channel = channel,
            durationMs = duration,
            thumbnailUrl = bestThumbnail(renderer.optJSONObject("thumbnail")),
        )
    }

    private fun textOf(obj: JSONObject?): String {
        if (obj == null) return ""
        obj.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = obj.optJSONArray("runs") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until runs.length()) {
            builder.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return builder.toString().trim()
    }

    private fun bestThumbnail(obj: JSONObject?): String? {
        val list = obj?.optJSONArray("thumbnails") ?: return null
        var best: String? = null
        var bestWidth = -1
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
            val width = item.optInt("width", 0)
            if (width >= bestWidth) {
                bestWidth = width
                best = url
            }
        }
        return best
    }

    private fun findContinuation(node: Any?, depth: Int = 0): String? {
        if (node == null || depth > 40) return null
        when (node) {
            is JSONObject -> {
                if (node.has("continuationCommand")) {
                    val token = node.optJSONObject("continuationCommand")?.optString("token")
                    if (!token.isNullOrBlank()) return token
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findContinuation(node.opt(keys.next()), depth + 1)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                findContinuation(node.opt(i), depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun findContinuationInHtml(html: String): String? {
        val match = Regex(""""continuationCommand":\{"token":"([^"]+)"""").find(html)
        return match?.groupValues?.getOrNull(1)
    }

    private fun parseDuration(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val parts = text.trim().split(':')
        if (parts.size !in 2..3) return 0L
        val seconds = parts.getOrNull(parts.size - 1)?.toLongOrNull() ?: return 0L
        val minutes = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: return 0L
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return 0L else 0L
        return ((hours * 60 + minutes) * 60 + seconds) * 1000L
    }

    /** Slices a balanced JSON object that starts right after [markers]. */
    private fun extractJson(html: String, vararg markers: String): String? {
        for (marker in markers) {
            var index = html.indexOf(marker)
            while (index >= 0) {
                val start = html.indexOf('{', index)
                if (start >= 0) {
                    val end = endOfJson(html, start)
                    if (end > start) return html.substring(start, end + 1)
                }
                index = html.indexOf(marker, index + marker.length)
            }
        }
        return null
    }

    /** Returns the index of the closing brace matching the one at [start]. */
    private fun endOfJson(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun regexValue(html: String, key: String): String? =
        Regex("\"$key\":\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)

    private fun escape(value: String): String = buildString(value.length + 8) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    private const val FALLBACK_CLIENT_VERSION = "2.20260101.00.00"
    private const val FALLBACK_MUSIC_CLIENT_VERSION = "1.20260101.01.00"
    private const val MUSIC_VIDEOS_PARAM = "EgWKAQIQAWoKEAoQAxAEEAkQBQ%3D%3D"
}

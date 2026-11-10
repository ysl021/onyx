package app.onyx.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube Music catalogue access for artist pages: artist search, the artist
 * page (top songs, albums, singles, videos) and album track lists.
 *
 * The phone talks to the same innerTube endpoint as the desktop client; no
 * proxy is needed because the phone's VPN covers the whole system.
 */
object YouTubeMusic {

    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val CLIENT_VERSION = "1.20261104.00.00"
    private const val VIDEOS_PARAM = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="
    private const val ARTISTS_PARAM = "EgWKAQIgAWoKEAoQAxAEEAkQBQ=="
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val RANDOM_QUERIES = listOf(
        "trending music", "top hits", "new music", "chill mix", "indie mix",
        "hip hop hits", "electronic mix", "r&b hits", "rock hits", "phonk",
        "lo-fi beats", "pop hits", "alternative mix", "house music",
        "russian pop", "russian rap", "фонк", "популярная музыка",
    )

    /**
     * Random-ish feed for the empty YouTube tab. The home shelf is preferred;
     * a random music search fills it when the logged-out feed is empty.
     */
    suspend fun homeRecommendations(): List<YtSearchResult> = withContext(Dispatchers.IO) {
        val items = mutableListOf<YtSearchResult>()
        val root = post("browse", browseBody("FEmusic_home", null))
        if (root != null) {
            val renderers = mutableListOf<JSONObject>()
            collect(root, "musicResponsiveListItemRenderer", renderers)
            renderers.mapNotNullTo(items) { parseSong(it, "") }

            val twoRows = mutableListOf<JSONObject>()
            collect(root, "musicTwoRowItemRenderer", twoRows)
            twoRows.forEach { item ->
                val id = findWatchId(item)?.takeIf { it.length == 11 } ?: return@forEach
                val title = textOf(item.opt("title")).ifBlank { return@forEach }
                val subtitle = textOf(item.opt("subtitle"))
                items += YtSearchResult(
                    id = id,
                    url = "https://www.youtube.com/watch?v=$id",
                    title = title,
                    channel = subtitle.substringBefore(" • ").trim(),
                    durationMs = parseDuration(
                        Regex("""\d{1,2}:\d{2}(?::\d{2})?""").find(subtitle)?.value,
                    ),
                    thumbnailUrl = thumbOf(item) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                )
            }
        }
        if (items.size < 8) {
            val query = RANDOM_QUERIES.random()
            items += YouTubeSearch.searchMusic(query).items
        }
        items.distinctBy { it.id }.shuffled().take(24)
    }

    suspend fun searchArtist(name: String): YtArtist? = withContext(Dispatchers.IO) {
        val root = post("search", searchBody(name, ARTISTS_PARAM)) ?: return@withContext null
        val renderers = mutableListOf<JSONObject>()
        collect(root, "musicResponsiveListItemRenderer", renderers)
        renderers.firstNotNullOfOrNull { renderer ->
            val browse = findBrowse(renderer, artistOnly = true)?.first
                ?: return@firstNotNullOfOrNull null
            val title = textOf(column(renderer, 0)).ifBlank { return@firstNotNullOfOrNull null }
            YtArtist(title, browse, thumbOf(renderer))
        }
    }

    suspend fun artistPage(artist: YtArtist): YtArtistPage? = withContext(Dispatchers.IO) {
        val root = post("browse", browseBody(artist.browseId, null)) ?: return@withContext null
        val songs = mutableListOf<YtSearchResult>()
        val albums = mutableListOf<YtAlbum>()
        val singles = mutableListOf<YtAlbum>()
        val videos = mutableListOf<YtSearchResult>()

        val shelves = mutableListOf<JSONObject>()
        collect(root, "musicShelfRenderer", shelves)
        shelves.forEach { shelf ->
            val title = textOf(shelf.opt("title"))
            if (title.contains("song", true) || title.contains("трек", true)) {
                val items = mutableListOf<JSONObject>()
                collect(shelf, "musicResponsiveListItemRenderer", items)
                items.mapNotNullTo(songs) { parseSong(it, artist.name) }
            }
        }

        val carousels = mutableListOf<JSONObject>()
        collect(root, "musicCarouselShelfRenderer", carousels)
        carousels.forEach { carousel ->
            val header = carousel.optJSONObject("header")
                ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
            val title = textOf(header?.opt("title"))
            val items = mutableListOf<JSONObject>()
            collect(carousel, "musicTwoRowItemRenderer", items)
            val parsed = items.mapNotNull { parseAlbumOrVideo(it, artist.name) }
            when {
                title.contains("album", true) && !title.contains("single", true) ->
                    parsed.mapNotNull { it.first }.mapNotNullTo(albums) { it }
                title.contains("single", true) || title.contains("ep", true) ->
                    parsed.mapNotNull { it.first }.mapNotNullTo(singles) { it }
                title.contains("video", true) ->
                    parsed.mapNotNull { it.second }.mapNotNullTo(videos) { it }
                else -> {
                    parsed.mapNotNull { it.first }.mapNotNullTo(albums) { it }
                    parsed.mapNotNull { it.second }.mapNotNullTo(videos) { it }
                }
            }
        }

        YtArtistPage(
            artist = artist.copy(thumbnailUrl = artist.thumbnailUrl ?: headerThumbnail(root)),
            songs = songs.distinctBy { it.id },
            albums = albums.distinctBy { it.browseId },
            singles = singles.distinctBy { it.browseId },
            videos = videos.distinctBy { it.id },
        )
    }

    suspend fun albumTracks(album: YtAlbum): List<YtSearchResult> = withContext(Dispatchers.IO) {
        val root = post("browse", browseBody(album.browseId, album.params))
            ?: return@withContext emptyList()
        val renderers = mutableListOf<JSONObject>()
        collect(root, "musicResponsiveListItemRenderer", renderers)
        renderers.mapNotNull { parseSong(it, "") }
    }

    private fun searchBody(query: String, params: String): JSONObject = JSONObject().apply {
        put("context", JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", CLIENT_VERSION)
                put("hl", "en")
                put("gl", "US")
            })
        })
        put("query", query)
        put("params", params)
    }

    private fun browseBody(browseId: String, params: String?): JSONObject = JSONObject().apply {
        put("context", JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", CLIENT_VERSION)
                put("hl", "en")
                put("gl", "US")
            })
        })
        put("browseId", browseId)
        if (!params.isNullOrBlank()) put("params", params)
    }

    private fun post(path: String, body: JSONObject): JSONObject? {
        val url = "https://music.youtube.com/youtubei/v1/$path?prettyPrint=false&key=$API_KEY"
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", YouTubeHttp.USER_AGENT)
            .header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", CLIENT_VERSION)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return runCatching {
            YouTubeHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONObject(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { out += it }
                val keys = node.keys()
                while (keys.hasNext()) collect(node.opt(keys.next()), key, out)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun column(renderer: JSONObject, index: Int): Any? {
        val columns = renderer.optJSONArray("flexColumns") ?: return null
        val column = columns.optJSONObject(index) ?: return null
        return column.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.opt("text")
    }

    private fun parseSong(renderer: JSONObject, fallbackArtist: String): YtSearchResult? {
        val id = renderer.optJSONObject("playlistItemData")
            ?.optString("videoId")
            ?.takeIf { it.length == 11 }
            ?: findWatchId(renderer)?.takeIf { it.length == 11 }
            ?: return null
        val title = textOf(column(renderer, 0)).ifBlank { return null }
        val subtitle = textOf(column(renderer, 1))
        val artist = subtitle.substringBefore(" • ").trim().ifBlank { fallbackArtist }
        val fixed = renderer.optJSONArray("fixedColumns")?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
            ?.opt("text")
        val duration = textOf(fixed).ifBlank {
            Regex("""\d{1,2}:\d{2}(?::\d{2})?""").find(subtitle)?.value.orEmpty()
        }
        return YtSearchResult(
            id = id,
            url = "https://www.youtube.com/watch?v=$id",
            title = title,
            channel = artist,
            durationMs = parseDuration(duration),
            thumbnailUrl = thumbOf(renderer) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        )
    }

    private fun parseAlbumOrVideo(
        item: JSONObject,
        artist: String,
    ): Pair<YtAlbum?, YtSearchResult?>? {
        val title = textOf(item.opt("title")).ifBlank { return null }
        val browseId = findBrowse(item, artistOnly = false)
        val watchId = findWatchId(item)
        val thumbnail = thumbOf(item)
        val subtitle = textOf(item.opt("subtitle"))
        val album = browseId?.let {
            YtAlbum(
                title = title,
                browseId = it.first,
                params = it.second,
                thumbnailUrl = thumbnail,
                subtitle = subtitle,
            )
        }
        val video = watchId?.takeIf { it.length == 11 }?.let {
            YtSearchResult(
                id = it,
                url = "https://www.youtube.com/watch?v=$it",
                title = title,
                channel = artist,
                durationMs = parseDuration(subtitle),
                thumbnailUrl = thumbnail ?: "https://i.ytimg.com/vi/$it/hqdefault.jpg",
            )
        }
        if (album == null && video == null) return null
        return album to video
    }

    private fun findBrowse(node: Any?, artistOnly: Boolean): Pair<String, String?>? {
        when (node) {
            is JSONObject -> {
                val endpoint = node.optJSONObject("browseEndpoint")
                if (endpoint != null) {
                    val id = endpoint.optString("browseId").takeIf { it.isNotBlank() }
                    val type = endpoint.optJSONObject("browseEndpointContextSupportedConfigs")
                        ?.optJSONObject("browseEndpointContextMusicConfig")
                        ?.optString("pageType")
                        .orEmpty()
                    if (id != null) {
                        val isArtist = type.contains("ARTIST") || id.startsWith("UC")
                        if (!artistOnly || isArtist) {
                            return id to endpoint.optString("params").takeIf { it.isNotBlank() }
                        }
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findBrowse(node.opt(keys.next()), artistOnly)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                findBrowse(node.opt(i), artistOnly)?.let { return it }
            }
        }
        return null
    }

    private fun findWatchId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val id = node.optJSONObject("watchEndpoint")?.optString("videoId")
                if (!id.isNullOrBlank()) return id
                val keys = node.keys()
                while (keys.hasNext()) {
                    findWatchId(node.opt(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                findWatchId(node.opt(i))?.let { return it }
            }
        }
        return null
    }

    private fun textOf(element: Any?): String {
        val obj = element as? JSONObject ?: return ""
        obj.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = obj.optJSONArray("runs") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until runs.length()) {
            builder.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return builder.toString().trim()
    }

    private fun thumbOf(obj: JSONObject): String? {
        val holder = obj.optJSONObject("thumbnail") ?: obj.optJSONObject("thumbnailRenderer")
            ?: return null
        val music = holder.optJSONObject("musicThumbnailRenderer") ?: return null
        val thumbs = music.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: return null
        var best: String? = null
        var bestWidth = -1
        for (i in 0 until thumbs.length()) {
            val item = thumbs.optJSONObject(i) ?: continue
            val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
            val width = item.optInt("width", 0)
            if (width >= bestWidth) {
                bestWidth = width
                best = url
            }
        }
        return best
    }

    private fun headerThumbnail(root: JSONObject): String? {
        val headers = mutableListOf<JSONObject>()
        collect(root, "musicImmersiveHeaderRenderer", headers)
        collect(root, "musicVisualHeaderRenderer", headers)
        return headers.firstNotNullOfOrNull { thumbOf(it) }
    }

    private fun parseDuration(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val parts = text.trim().split(':')
        if (parts.size !in 2..3) return 0L
        val seconds = parts.lastOrNull()?.toLongOrNull() ?: return 0L
        val minutes = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: return 0L
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return 0L else 0L
        return ((hours * 60 + minutes) * 60 + seconds) * 1000L
    }
}

package app.onyx.lyrics

import android.content.Context
import app.onyx.core.Http
import app.onyx.core.Prefs
import app.onyx.media.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import org.jsoup.Jsoup
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Provider chain for lyrics:
 *  1. Musixmatch appmobile (word-level "richsync" karaoke + official translations)
 *  2. Spotify official color-lyrics (line sync)
 *  3. LRCLIB (enhanced LRC → word level, plain LRC → line level)
 *  4. Plain text fallback
 *
 * Everything is cached in memory and on disk. Track changes cancel the previous request.
 */
class LyricsRepository(
    context: Context,
    private val prefs: Prefs,
    private val spotifyToken: suspend () -> String?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val _state = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private val cacheDir = File(context.cacheDir, "lyrics").apply { mkdirs() }
    private val memory = object : LinkedHashMap<String, LyricsDoc>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsDoc>?): Boolean =
            size > 24
    }

    private var current: NowPlaying = NowPlaying()
    private var currentKey: String? = null
    private var job: Job? = null
    private var anonSpotifyToken: String? = null

    @Volatile
    private var mmToken: String? = null

    /** Called whenever media metadata changes. Does nothing when the track is unchanged. */
    fun sync(now: NowPlaying, force: Boolean = false) {
        if (!now.hasTrack) {
            current = now
            currentKey = null
            job?.cancel()
            _state.value = LyricsState.Idle
            return
        }
        val key = keyOf(now)
        current = now
        if (!force && key == currentKey && _state.value !is LyricsState.Error) return
        currentKey = key
        job?.cancel()
        job = scope.launch { load(now, key) }
    }

    fun retry() {
        val now = current
        if (now.hasTrack) sync(now, force = true)
    }

    /** Manual title/artist lookup, including downloaded tracks with poor metadata. */
    fun searchManual(title: String, artist: String) {
        val t = title.trim()
        if (t.isBlank()) return
        val now = NowPlaying(
            packageName = "manual",
            title = t,
            artist = artist.trim(),
            album = "",
            durationMs = 0L,
            mediaId = "",
        )
        val key = keyOf(now)
        current = now
        currentKey = key
        job?.cancel()
        job = scope.launch { load(now, key) }
    }

    /** Called when the user turns translation on after the lyrics are already loaded. */
    fun translateCurrent() {
        val doc = (_state.value as? LyricsState.Ready)?.doc ?: return
        if (doc.lines.any { it.translation != null }) return
        val key = currentKey ?: return
        job?.cancel()
        job = scope.launch {
            val translated = runCatching { withTranslation(doc) }.getOrNull() ?: return@launch
            memory[key] = translated
            writeDisk(key, translated)
            _state.value = LyricsState.Ready(translated)
        }
    }

    fun clear() {
        job?.cancel()
        currentKey = null
        _state.value = LyricsState.Idle
    }

    fun close() {
        job?.cancel()
        scope.cancel()
    }

    private suspend fun load(now: NowPlaying, key: String) {
        _state.value = LyricsState.Loading
        val prefsNow = prefs.snapshot()

        val cached = memory[key] ?: readDisk(key)?.also { memory[key] = it }
        if (cached != null) {
            if (prefsNow.translate && cached.synced && cached.lines.none { it.translation != null }) {
                val translated = runCatching { withTranslation(cached) }.getOrNull()
                if (translated != null) {
                    memory[key] = translated
                    writeDisk(key, translated)
                    _state.value = LyricsState.Ready(translated)
                    return
                }
            }
            _state.value = LyricsState.Ready(cached)
            return
        }

        val attempts = ArrayList<String>(5)
        var found: LyricsDoc? = null
        val providers: List<Pair<String, suspend () -> LyricsDoc?>> = listOf(
            "Spicy" to { spicy(now, prefsNow.spicyKey) },
            "Musixmatch" to { musixmatch(now, prefsNow.translate) },
            "Spotify" to { spotify(now) },
            "LRCLIB" to { lrclib(now) },
            "Genius" to { genius(now) },
        )
        for ((name, provider) in providers) {
            val doc = runCatching { provider() }.getOrElse {
                attempts += "$name: ошибка"
                null
            }
            if (doc != null) {
                found = doc
                attempts += "$name: найдено"
                break
            }
            if (attempts.none { it.startsWith("$name:") }) attempts += "$name: нет"
        }

        if (found == null) {
            _state.value = LyricsState.Error(
                "Текст не найден.\n" + attempts.joinToString(" · ") +
                    "\nМожно указать название и исполнителя вручную.",
            )
            return
        }
        if (app.onyx.BuildConfig.DEBUG) {
            android.util.Log.d("ONYX_Lyrics", "provider=${found.provider} karaoke=${found.karaoke}")
        }

        var doc = found
        if (prefsNow.translate && doc.lines.none { it.translation != null }) {
            val translated = runCatching { withTranslation(doc) }.getOrNull()
            if (translated != null) doc = translated
        }

        memory[key] = doc
        writeDisk(key, doc)
        _state.value = LyricsState.Ready(doc)
    }

    // ------------------------------------------------------------- Spicy Lyrics

    /**
     * Community TTML catalogue used by the Spicy Lyrics extension. This is the
     * source that has word-level lyrics for remixes and slowed versions which
     * Musixmatch/Spotify do not cover.
     */
    private suspend fun spicy(now: NowPlaying, key: String): LyricsDoc? {
        if (key.isBlank()) return null
        var id = now.spotifyTrackId
        if (id != null) {
            spicyById(id, now, key)?.let { return it }
        }
        // Non-Spotify players, or a Spotify id that is not in the catalogue:
        // search by title/artist and trust only an exact/near result.
        val seconds = (now.durationMs / 1000.0).takeIf { it > 0 }
        val searchUrl = buildString {
            append("https://api.spicylyrics.org/v1/tracks/search?")
            append("title=").append(enc(cleanTitle(now.title)))
            append("&artist=").append(enc(cleanArtist(now.artist)))
            if (now.album.isNotBlank()) append("&album=").append(enc(now.album))
            if (seconds != null) append("&duration=").append(seconds.toInt())
            append("&limit=5")
        }
        val body = spicyGet(searchUrl, key) ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val results = root["Body"]?.jsonObject?.get("results")?.jsonArray ?: return null
        for (el in results) {
            val obj = el.jsonObject
            val tier = obj["tier"]?.str()?.lowercase()
            if (tier != null && tier != "exact" && tier != "close") continue
            val foundId = obj["id"]?.str()?.takeIf { it.isNotBlank() } ?: continue
            spicyById(foundId, now, key)?.let { return it }
            // Different releases of the same recording can have different ids;
            // one of the variants may be the one with community TTML.
            val variants = obj["variants"]?.jsonArray ?: continue
            for (variant in variants) {
                val vid = variant.jsonObject["id"]?.str()?.takeIf { it.isNotBlank() } ?: continue
                spicyById(vid, now, key)?.let { return it }
            }
        }
        return null
    }

    private suspend fun spicyById(id: String, now: NowPlaying, key: String): LyricsDoc? {
        val body = spicyGet("https://api.spicylyrics.org/v1/lyrics/$id", key)
        if (app.onyx.BuildConfig.DEBUG) {
            android.util.Log.d("ONYX_Lyrics", "spicy lyrics $id -> ${body?.length ?: 0} bytes")
        }
        if (body == null) return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val payload = root["Body"]?.jsonObject ?: return null
        val type = payload["Type"]?.str().orEmpty()
        val content = payload["Content"]?.jsonArray ?: return null
        if (content.isEmpty()) return null

        if (type.equals("Static", ignoreCase = true)) {
            val texts = content.mapNotNull { el ->
                val lead = el.jsonObject["Lead"]?.jsonObject ?: return@mapNotNull null
                val syllables = lead["Syllables"]?.jsonArray
                if (syllables == null) lead["Text"]?.str() else joinSyllables(syllables).first
            }.map { it.trim() }.filter { it.isNotEmpty() }
            return docOf(texts, provider = "Spicy Lyrics", now = now)
        }

        val lines = ArrayList<LyricLine>(content.size)
        for (el in content) {
            val obj = el.jsonObject
            val lead = obj["Lead"]?.jsonObject ?: continue
            val syllables = lead["Syllables"]?.jsonArray
            val (text, words) = if (syllables != null && syllables.isNotEmpty()) {
                joinSyllables(syllables)
            } else {
                val t = lead["Text"]?.str().orEmpty()
                t to emptyList()
            }
            if (text.isBlank()) continue

            val lineStart = when {
                words.isNotEmpty() -> words.first().startMs
                lead["StartTime"]?.dbl() != null -> (lead["StartTime"]!!.dbl()!! * 1000).toLong()
                else -> 0L
            }
            val lineEnd = when {
                words.isNotEmpty() -> words.last().endMs
                lead["EndTime"]?.dbl() != null -> (lead["EndTime"]!!.dbl()!! * 1000).toLong()
                else -> lineStart + 4000
            }
            val translated = lead["TranslatedText"]?.str()?.trim()?.takeIf { it.isNotEmpty() }
            lines += LyricLine(
                text = text,
                startMs = lineStart,
                endMs = maxOf(lineEnd, lineStart + 400),
                words = words,
                translation = translated,
            )
        }
        if (lines.isEmpty()) return null
        val sorted = lines.sortedBy { it.startMs }
        return LyricsDoc(
            lines = sorted.mapIndexed { i, line ->
                val next = sorted.getOrNull(i + 1)?.startMs
                line.copy(endMs = if (line.endMs > line.startMs) line.endMs else (next ?: line.startMs + 5000))
            },
            synced = true,
            karaoke = sorted.any { it.words.isNotEmpty() },
            provider = "Spicy Lyrics",
            title = now.title,
            artist = now.artist,
        )
    }

    /** Joins syllable runs into a readable line + per-syllable karaoke words. */
    private fun joinSyllables(syllables: kotlinx.serialization.json.JsonArray): Pair<String, List<LyricWord>> {
        val sb = StringBuilder()
        val words = ArrayList<LyricWord>(syllables.size)
        for ((i, el) in syllables.withIndex()) {
            val s = el.jsonObject
            val text = s["Text"]?.str().orEmpty()
            if (text.isEmpty()) continue
            val prevPart = i > 0 && syllables[i - 1].jsonObject["IsPartOfWord"]?.bool() == true
            val space = i > 0 && !prevPart
            val start = ((s["StartTime"]?.dbl() ?: 0.0) * 1000).toLong()
            val end = ((s["EndTime"]?.dbl() ?: (s["StartTime"]?.dbl() ?: 0.0) + 0.12) * 1000).toLong()
            val chunk = if (space) " $text" else text
            sb.append(chunk)
            words += LyricWord(text = chunk, startMs = start, endMs = maxOf(end, start + 60))
        }
        return sb.toString() to words
    }

    private suspend fun spicyGet(url: String, key: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .header("User-Agent", Http.UA)
            .build()
        return get(req)
    }

    // ---------------------------------------------------------------- Musixmatch

    private suspend fun musixmatch(now: NowPlaying, translate: Boolean): LyricsDoc? {
        val token = musixmatchToken() ?: MM_TOKEN
        return try {
            musixmatchWith(now, translate, token)
        } catch (_: MmTokenExpired) {
            // The appmobile token is short-lived ("renew" / 401). Fetch a fresh one once.
            val fresh = musixmatchToken(force = true) ?: return null
            if (fresh == token) return null
            runCatching { musixmatchWith(now, translate, fresh) }.getOrNull()
        }
    }

    /** Guest token from the iOS app API; unlike the plugin constant it does not expire. */
    private suspend fun musixmatchToken(force: Boolean = false): String? {
        if (!force) mmToken?.let { return it }
        val url = "https://apic-appmobile.musixmatch.com/ws/1.1/token.get?format=json&app_id=mac-ios-v2.0"
        val req = Request.Builder()
            .url(url)
            .header("Host", "apic-appmobile.musixmatch.com")
            .header("X-Cookie", "x-mxm-token-guid=")
            .header("x-mxm-app-version", "10.1.1")
            .header("X-User-Agent", MM_UA)
            .header("Accept", "application/json")
            .header("User-Agent", Http.UA)
            .build()
        val body = get(req) ?: return null
        val token = runCatching {
            json.parseToJsonElement(body).jsonObject["message"]?.jsonObject
                ?.get("body")?.jsonObject
                ?.get("user_token")?.str()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        mmToken = token
        return token
    }

    private suspend fun musixmatchWith(
        now: NowPlaying,
        translate: Boolean,
        token: String,
    ): LyricsDoc? {
        val seconds = (now.durationMs / 1000.0).takeIf { it > 0 } ?: 0.0
        val searchTitle = cleanTitle(now.title)
        val searchArtist = cleanArtist(now.artist)
        val params = buildList {
            add("format=json")
            add("namespace=lyrics_richsynched")
            add("subtitle_format=mxm")
            add("app_id=mac-ios-v2.0")
            add("q_album=" + enc(now.album))
            add("q_artist=" + enc(searchArtist))
            add("q_artists=" + enc(searchArtist))
            add("q_track=" + enc(searchTitle))
            now.spotifyTrackId?.let { add("track_spotify_id=" + enc("spotify:track:$it")) }
            if (seconds > 0) {
                add("q_duration=" + enc(seconds.toString()))
                add("f_subtitle_length=" + seconds.toInt())
            }
            add("usertoken=$token")
            add("optional_calls=track.richsync")
            add("richsync_compact_type=words")
            add("part=track_lyrics_translation_status,track_structure,track_performer_tagging")
        }
        val url = "https://apic-appmobile.musixmatch.com/ws/1.1/macro.subtitles.get?" +
            params.joinToString("&")

        val req = Request.Builder()
            .url(url)
            .header("Host", "apic-appmobile.musixmatch.com")
            .header("X-Cookie", "x-mxm-token-guid=")
            .header("x-mxm-app-version", "10.1.1")
            .header("X-User-Agent", MM_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "application/json")
            .header("User-Agent", Http.UA)
            .build()

        val body = get(req) ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val macro = root["message"]?.jsonObject
            ?.get("body")?.jsonObject
            ?.get("macro_calls")?.jsonObject ?: return null

        val matcherMessage = macro["matcher.track.get"]?.jsonObject?.get("message")?.jsonObject
        val status = matcherMessage?.get("header")?.jsonObject?.get("status_code")?.int()
        if (status == 401) throw MmTokenExpired()
        if (status != null && status != 200) return null

        val trackBody = matcherMessage
            ?.get("body")?.jsonObject ?: return null
        val track = trackBody["track"]?.jsonObject ?: return null
        if (track["instrumental"]?.bool() == true) {
            return docOf(listOf("♪ Инструментал ♪"), provider = "Musixmatch")
        }
        val trackId = track["track_id"]?.int()

        // 1. word-level richsync
        val richBody = macro["track.richsync.get"]?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("body")?.jsonObject
            ?.get("richsync")?.jsonObject
            ?.get("richsync_body")?.str()
        if (!richBody.isNullOrBlank()) {
            val lines = parseRichsync(richBody)
            if (lines.isNotEmpty()) {
                val translations = if (translate && trackId != null) {
                    runCatching { musixmatchTranslation(trackId, lines.map { it.text }, token) }.getOrNull()
                } else {
                    null
                }
                return LyricsDoc(
                    lines = lines.map { line ->
                        line.copy(translation = translations?.get(normalize(line.text)))
                    },
                    synced = true,
                    karaoke = true,
                    provider = "Musixmatch",
                    title = now.title,
                    artist = now.artist,
                )
            }
        }

        // 2. line-synced subtitles
        val subtitleBody = macro["track.subtitles.get"]?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("body")?.jsonObject
            ?.get("subtitle_list")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("subtitle")?.jsonObject
            ?.get("subtitle_body")?.str()
        if (!subtitleBody.isNullOrBlank()) {
            val doc = parseMusixmatchSubtitle(subtitleBody, now)
            if (doc != null) {
                val translations = if (translate && trackId != null) {
                    runCatching { musixmatchTranslation(trackId, doc.lines.map { it.text }, token) }.getOrNull()
                } else {
                    null
                }
                return doc.copy(
                    lines = doc.lines.map { line ->
                        line.copy(translation = translations?.get(normalize(line.text)))
                    },
                )
            }
        }

        // 3. plain lyrics
        val plain = macro["track.lyrics.get"]?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("body")?.jsonObject
            ?.get("lyrics")?.jsonObject
            ?.get("lyrics_body")?.str()
        if (!plain.isNullOrBlank()) {
            val cleaned = plain.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("*******") }
                .toList()
            return docOf(cleaned, provider = "Musixmatch", now = now)
        }
        return null
    }

    private suspend fun musixmatchTranslation(
        trackId: Int,
        lines: List<String>,
        token: String,
    ): Map<String, String>? {
        val url = "https://apic-appmobile.musixmatch.com/ws/1.1/crowd.track.translations.get" +
            "?translation_fields_set=minimal&comment_format=text&format=json&app_id=mac-ios-v2.0" +
            "&track_id=$trackId&selected_language=ru&usertoken=$token"
        val req = Request.Builder()
            .url(url)
            .header("x-mxm-app-version", "10.1.1")
            .header("X-User-Agent", MM_UA)
            .header("Accept", "application/json")
            .header("User-Agent", Http.UA)
            .build()
        val body = get(req) ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val list = root["message"]?.jsonObject
            ?.get("body")?.jsonObject
            ?.get("translations_list")?.jsonArray ?: return null
        val out = HashMap<String, String>()
        for (el in list) {
            val tr = el.jsonObject["translation"]?.jsonObject ?: continue
            // The endpoint returns every crowd translation, not only the requested one.
            if (tr["language"]?.str()?.lowercase() != "ru") continue
            val text = tr["description"]?.str()?.trim().orEmpty()
            val matched = tr["matched_line"]?.str()?.trim().orEmpty()
            if (text.isNotEmpty() && matched.isNotEmpty()) out[normalize(matched)] = text
        }
        return out.ifEmpty { null }
    }

    private fun parseRichsync(raw: String): List<LyricLine> {
        val arr = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        val lines = ArrayList<LyricLine>(arr.size)
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val ts = o["ts"]?.dbl() ?: 0.0
            val te = o["te"]?.dbl() ?: (ts + 4.0)
            val start = (ts * 1000).toLong()
            val end = (te * 1000).toLong()
            val wordArr = o["l"]?.jsonArray ?: JsonArray(emptyList())
            val words = ArrayList<LyricWord>(wordArr.size)
            for (w in wordArr) {
                val wo = w as? JsonObject ?: continue
                val text = wo["c"]?.str() ?: continue
                val offset = wo["o"]?.dbl() ?: 0.0
                // Richsync emits spaces as separate timed tokens; dropping them glues words.
                if (text.isEmpty()) continue
                val wStart = start + (offset * 1000).toLong()
                words.add(LyricWord(text, wStart, wStart + 400))
            }
            for (i in words.indices) {
                val next = words.getOrNull(i + 1)?.startMs ?: end
                val w = words[i]
                words[i] = w.copy(endMs = maxOf(next, w.startMs + 80))
            }
            val text = words.joinToString("") { it.text }.trim()
            if (text.isEmpty()) continue
            lines.add(
                LyricLine(
                    text = text,
                    startMs = start,
                    endMs = maxOf(end, start + 400),
                    words = words,
                ),
            )
        }
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedBy { it.startMs }
        return sorted.mapIndexed { i, line ->
            val next = sorted.getOrNull(i + 1)?.startMs
            if (next != null && next > line.startMs) line.copy(endMs = maxOf(next, line.endMs)) else line
        }
    }

    private fun parseMusixmatchSubtitle(raw: String, now: NowPlaying): LyricsDoc? {
        val arr = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return null
        val lines = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val text = o["text"]?.str()?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val total = o["time"]?.jsonObject?.get("total")?.dbl() ?: return@mapNotNull null
            LyricLine(text = text, startMs = (total * 1000).toLong(), endMs = (total * 1000).toLong() + 4000)
        }
        if (lines.isEmpty()) return null
        val sorted = lines.sortedBy { it.startMs }
        return LyricsDoc(
            lines = sorted.mapIndexed { i, line ->
                val next = sorted.getOrNull(i + 1)?.startMs
                line.copy(endMs = next ?: (line.startMs + 5000))
            },
            synced = true,
            karaoke = false,
            provider = "Musixmatch",
            title = now.title,
            artist = now.artist,
        )
    }

    // ------------------------------------------------------------------- Spotify

    private suspend fun spotify(now: NowPlaying): LyricsDoc? {
        val id = now.spotifyTrackId ?: return null
        val token = anonSpotifyToken ?: runCatching { spotifyToken() }.getOrNull()
            ?: spotifyWebToken()
            ?: return null
        val url = "https://spclient.wg.spotify.com/color-lyrics/v2/track/$id" +
            "?format=json&vocalRemoval=false&market=from_token"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("app-platform", "WebPlayer")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept", "application/json")
            .build()
        val body = get(req) ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val lyrics = root["lyrics"]?.jsonObject ?: return null
        val syncType = lyrics["syncType"]?.str().orEmpty()
        val list = lyrics["lines"]?.jsonArray ?: return null
        if (list.isEmpty()) return null
        if (!syncType.contains("SYNCED") || syncType == "UNSYNCED") {
            val texts = list.mapNotNull { it.jsonObject["words"]?.str()?.trim()?.takeIf(String::isNotEmpty) }
            return docOf(texts, provider = "Spotify", now = now)
        }
        val lines = list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val text = o["words"]?.str()?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val start = o["startTimeMs"]?.str()?.toLongOrNull() ?: return@mapNotNull null
            val syllables = o["syllables"]?.jsonArray
            val words = ArrayList<LyricWord>()
            if (syllables != null && syllables.isNotEmpty()) {
                for (s in syllables) {
                    val so = s as? JsonObject ?: continue
                    val st = so["startTimeMs"]?.str()?.toLongOrNull() ?: continue
                    val sw = so["words"]?.str() ?: continue
                    if (sw.isEmpty()) continue
                    words.add(LyricWord(sw, st, st + 300))
                }
                for (i in words.indices) {
                    val next = words.getOrNull(i + 1)?.startMs ?: (start + 4000)
                    words[i] = words[i].copy(endMs = maxOf(next, words[i].startMs + 80))
                }
            }
            LyricLine(text = text, startMs = start, endMs = start + 4000, words = words)
        }
        if (lines.isEmpty()) return null
        val sorted = lines.sortedBy { it.startMs }
        return LyricsDoc(
            lines = sorted.mapIndexed { i, line ->
                val next = sorted.getOrNull(i + 1)?.startMs
                line.copy(endMs = next ?: (line.startMs + 5000))
            },
            synced = true,
            karaoke = sorted.any { it.words.isNotEmpty() },
            provider = "Spotify",
            title = now.title,
            artist = now.artist,
        )
    }

    private suspend fun spotifyWebToken(): String? {
        val req = Request.Builder()
            .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept", "application/json")
            .build()
        val body = get(req) ?: return null
        val token = runCatching {
            json.parseToJsonElement(body).jsonObject["accessToken"]?.str()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        anonSpotifyToken = token
        return token
    }

    // -------------------------------------------------------------------- LRCLIB

    private suspend fun lrclib(now: NowPlaying): LyricsDoc? {
        val seconds = (now.durationMs / 1000).toInt()
        val direct = "https://lrclib.net/api/get?" + buildList {
            add("track_name=" + enc(cleanTitle(now.title)))
            add("artist_name=" + enc(cleanArtist(now.artist)))
            if (now.album.isNotBlank()) add("album_name=" + enc(now.album))
            if (seconds > 0) add("duration=" + seconds)
        }.joinToString("&")

        val directBody = fetchLrclib(direct)
        directBody?.let { body ->
            parseLrclib(body, now)?.let { return it }
        }

        val search = "https://lrclib.net/api/search?" + buildList {
            add("track_name=" + enc(cleanTitle(now.title)))
            add("artist_name=" + enc(cleanArtist(now.artist)))
        }.joinToString("&")
        val searchBody = fetchLrclib(search) ?: return null
        val arr = runCatching { json.parseToJsonElement(searchBody).jsonArray }.getOrNull() ?: return null
        val candidates = arr.mapNotNull { it as? JsonObject }
            .sortedByDescending { it["syncedLyrics"]?.str().isNullOrBlank().not() }
        for (c in candidates.take(4)) {
            val doc = parseLrclib(c.toString(), now)
            if (doc != null) return doc
        }
        return null
    }

    private suspend fun fetchLrclib(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("x-user-agent", "ONYX/0.1 (https://github.com/onyx)")
            .header("User-Agent", Http.UA)
            .build()
        return try {
            withContext(Dispatchers.IO) {
                Http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLrclib(body: String, now: NowPlaying): LyricsDoc? {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (o["instrumental"]?.bool() == true) {
            return docOf(listOf("♪ Инструментал ♪"), provider = "LRCLIB", now = now)
        }
        val synced = o["syncedLyrics"]?.str()
        if (!synced.isNullOrBlank()) {
            val doc = runCatching { Lrc.parse(synced) }.getOrNull()
            if (doc != null && doc.lines.isNotEmpty()) {
                return doc.copy(
                    provider = "LRCLIB",
                    title = now.title,
                    artist = now.artist,
                )
            }
        }
        val plain = o["plainLyrics"]?.str()
        if (!plain.isNullOrBlank()) {
            val texts = plain.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            return docOf(texts, provider = "LRCLIB", now = now)
        }
        return null
    }

    // -------------------------------------------------------------------- Genius

    private suspend fun genius(now: NowPlaying): LyricsDoc? {
        val query = enc("${cleanArtist(now.artist)} ${cleanTitle(now.title)}".trim())
        val searchUrl = "https://genius.com/api/search/song?per_page=10&q=$query"
        val searchReq = Request.Builder()
            .url(searchUrl)
            .header("Accept", "application/json")
            .header("User-Agent", Http.UA)
            .build()
        val searchBody = get(searchReq) ?: return null
        val root = runCatching { json.parseToJsonElement(searchBody).jsonObject }.getOrNull() ?: return null
        val sections = root["response"]?.jsonObject?.get("sections")?.jsonArray ?: return null
        val url = sections.asSequence()
            .mapNotNull { it.jsonObject["hits"]?.jsonArray }
            .flatMap { it.asSequence() }
            .mapNotNull { it.jsonObject["result"]?.jsonObject }
            .mapNotNull { it["url"]?.str() }
            .firstOrNull { it.isNotBlank() }
            ?: return null

        val pageReq = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept", "text/html")
            .build()
        val html = get(pageReq) ?: return null
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        val containers = doc.select("div[data-lyrics-container=true]")
        if (containers.isEmpty()) return null
        val text = containers.joinToString("\n") { el ->
            el.select("br").forEach { br -> br.replaceWith(org.jsoup.nodes.TextNode("\n")) }
            el.wholeText()
        }
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("Embed") }
        if (lines.isEmpty()) return null
        return docOf(lines, provider = "Genius", now = now)
    }

    // ---------------------------------------------------------------- Translation

    private suspend fun withTranslation(doc: LyricsDoc): LyricsDoc? {
        val texts = doc.lines.map { it.text }
        if (texts.none(::hasLetters)) return doc
        if (isMostlyRussian(texts)) return doc

        val translated = translateBatch(texts) ?: return null
        if (translated.size != doc.lines.size) return null
        val out = doc.lines.mapIndexed { i, line ->
            val t = translated[i].trim()
            if (t.isEmpty() || normalize(t) == normalize(line.text)) line else line.copy(translation = t)
        }
        if (out.none { it.translation != null }) return null
        return doc.copy(lines = out)
    }

    private suspend fun translateBatch(lines: List<String>): List<String>? {
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val chunk = ArrayList<String>()
            var len = 0
            while (i < lines.size && chunk.size < 24 && len + lines[i].length < 1400) {
                chunk.add(lines[i])
                len += lines[i].length + 1
                i++
            }
            if (chunk.all { it.isBlank() }) {
                chunk.forEach { out.add("") }
                continue
            }
            val joined = chunk.joinToString("\n")
            val res = googleTranslate(joined)
            if (res == null) {
                for (l in chunk) out.add(googleTranslate(l) ?: l)
            } else {
                val parts = res.split("\n")
                if (parts.size == chunk.size) {
                    out.addAll(parts)
                } else {
                    for (l in chunk) out.add(googleTranslate(l) ?: l)
                }
            }
        }
        return if (out.size == lines.size) out else null
    }

    private suspend fun googleTranslate(text: String): String? {
        if (text.isBlank()) return ""
        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=auto&tl=ru&dt=t&q=" + enc(text)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .build()
        val body = get(req) ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return null
        val segments = root.firstOrNull()?.jsonArray ?: return null
        val sb = StringBuilder()
        for (seg in segments) {
            val arr = seg as? JsonArray ?: continue
            arr.firstOrNull()?.str()?.let { sb.append(it) }
        }
        return sb.toString().ifBlank { null }
    }

    // ---------------------------------------------------------------------- Utils

    private suspend fun get(req: Request): String? = withContext(Dispatchers.IO) {
        try {
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun docOf(
        texts: List<String>,
        provider: String,
        now: NowPlaying? = null,
    ): LyricsDoc? {
        val lines = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return LyricsDoc(
            lines = lines.mapIndexed { i, t ->
                LyricLine(text = t, startMs = (i * 4000).toLong(), endMs = (i + 1) * 4000L)
            },
            synced = false,
            karaoke = false,
            provider = provider,
            title = now?.title.orEmpty(),
            artist = now?.artist.orEmpty(),
        )
    }

    private fun keyOf(now: NowPlaying): String {
        val dur = (now.durationMs / 2000).toLong()
        // Bump when the provider order changes so stale Musixmatch-only entries
        // don't hide the new Spicy Lyrics source.
        return sha1("v2|" + normalize(now.title) + "|" + normalize(now.artist) + "|" + dur)
    }

    private fun readDisk(key: String): LyricsDoc? {
        val f = File(cacheDir, "$key.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(LyricsDoc.serializer(), f.readText()) }.getOrNull()
    }

    private fun writeDisk(key: String, doc: LyricsDoc) {
        runCatching {
            File(cacheDir, "$key.json").writeText(json.encodeToString(LyricsDoc.serializer(), doc))
            val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            files.drop(120).forEach { it.delete() }
        }
    }

    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private fun cleanTitle(raw: String): String {
        var t = raw.substringBeforeLast('.').replace('_', ' ')
        t = Regex(
            """[\[(（].*?(official|video|audio|lyrics|slowed|reverb|hardtekk|remix|sped\s*up|nightcore|prod\.).*?[\])）]""",
            RegexOption.IGNORE_CASE,
        ).replace(t, " ")
        t = Regex(
            """\b(slowed|reverb|hardtekk|sped\s*up|nightcore|remix|remaster(ed)?|version)\b""",
            RegexOption.IGNORE_CASE,
        ).replace(t, " ")
        t = t.replace(Regex("""^\s*\d{1,3}[\s._-]+"""), "")
        return t.replace(Regex("""\s{2,}"""), " ").trim().ifBlank { raw }
    }

    private fun cleanArtist(raw: String): String =
        Regex("""\s*[-–—]\s*topic$""", RegexOption.IGNORE_CASE)
            .replace(raw.trim(), "")
            .trim()
            .ifBlank { raw.trim() }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun hasLetters(s: String): Boolean = s.any { it.isLetter() }

    private fun isMostlyRussian(lines: List<String>): Boolean {
        var cyr = 0
        var lat = 0
        for (line in lines) {
            for (c in line) {
                if (c in '\u0400'..'\u04FF') cyr++
                else if (c in 'a'..'z' || c in 'A'..'Z') lat++
            }
        }
        return cyr > 0 && cyr >= lat
    }

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    // Safe JsonElement accessors (JsonPrimitive can be string or number).
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    private class MmTokenExpired : Exception()

    companion object {
        // Fallback token shipped with the lyrics-plus plugin. Musixmatch now rejects it with
        // "renew", so mmToken from token.get is used first.
        private const val MM_TOKEN = "21051986b9886beabe1ce01c3ce94c96319411f8f2c122676365e3"
        private const val MM_UA = "Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0"
    }
}

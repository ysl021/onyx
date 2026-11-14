package app.onyx.open

import app.onyx.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.ceil
import kotlin.math.min

/**
 * Search across catalogues that publish their audio under open licences.
 *
 * Only sources with an official API and a clear licence are queried: Openverse
 * (CC audio aggregator), Internet Archive (net labels, public domain) and
 * ccMixter (CC remixes). Every result carries a direct file URL and the licence
 * line, so playback needs no stream extraction and downloading is unambiguous.
 *
 * This is deliberately not a "find any mp3" search: it does not scrape pirate
 * sites, which would break their terms and make the app unpublishable.
 */
data class OpenResult(
    val id: String,
    val title: String,
    val artist: String,
    val provider: String,
    val providerLabel: String,
    val pageUrl: String,
    val fileUrl: String,
    val format: String,
    val durationMs: Long = 0L,
    val license: String = "",
    val coverUrl: String = "",
    val score: Double = 0.0,
)

data class OpenSearchResult(
    val results: List<OpenResult> = emptyList(),
    val errors: List<String> = emptyList(),
    val tried: String = "",
)

object OpenSources {

    private const val TIMEOUT_MS = 15_000
    private const val MAX_RESULTS = 40
    private val AUDIO_RE = Regex("\\.(mp3|ogg|oga|flac|wav|m4a|opus)(\\?|$)", RegexOption.IGNORE_CASE)

    suspend fun search(rawQuery: String): OpenSearchResult = withContext(Dispatchers.IO) {
        val base = cleanQuery(rawQuery)
        if (base.length < 2) return@withContext OpenSearchResult()

        val variants = variants(base)
        val errors = LinkedHashSet<String>()
        val raw = ArrayList<OpenResult>()

        for ((index, variant) in variants.withIndex()) {
            val batches = coroutineScope {
                listOf(
                    async { runCatching { openverse(variant) } },
                    async { runCatching { internetArchive(variant) } },
                    async { runCatching { ccMixter(variant) } },
                ).awaitAll()
            }
            batches.forEach { result ->
                result.onSuccess { raw += it }
                result.onFailure { e ->
                    // A timeout on the second spelling is not worth reporting
                    // when the first one already found something.
                    if (index == 0) errors += providerError(e)
                }
            }
            val words = norm(variant).split(' ').count { it.isNotBlank() }
            if (index > 0 && relevantCount(raw, base) >= 6) break
            if (index > 0 && words == 0) break
        }

        val words = norm(base).split(' ').count { it.isNotBlank() }.coerceAtLeast(1)
        val seenUrl = HashSet<String>()
        val seenName = HashSet<String>()
        val scored = ArrayList<OpenResult>()
        for (item in raw) {
            if (!item.fileUrl.startsWith("https://", ignoreCase = true)) continue
            val nameKey = norm(item.artist) + "|" + norm(item.title)
            if (!seenUrl.add(item.fileUrl) || !seenName.add(nameKey)) continue
            scored += item.copy(score = score(base, item.title, item.artist))
        }
        val threshold = ceil(words / 2.0)
        val relevant = scored.filter { it.score * (words + 4) >= threshold }
        val list = (if (relevant.size >= 3) relevant else scored)
            .sortedByDescending { it.score }
            .take(MAX_RESULTS)

        OpenSearchResult(
            results = list,
            errors = errors.toList(),
            tried = if (variants.firstOrNull() != null && variants.first() != rawQuery) variants.first() else "",
        )
    }

    private fun relevantCount(items: List<OpenResult>, query: String): Int {
        val words = norm(query).split(' ').count { it.isNotBlank() }.coerceAtLeast(1)
        return items.count { score(query, it.title, it.artist) * (words + 4) >= ceil(words / 2.0) }
    }

    private fun providerError(e: Throwable): String {
        val text = e.message ?: e.javaClass.simpleName
        return if (text.contains("timeout", ignoreCase = true)) "timeout" else text.take(90)
    }

    /* ------------------------------- providers ------------------------------- */

    private suspend fun openverse(query: String): List<OpenResult> {
        val url = "https://api.openverse.org/v1/audio/?q=${enc(query)}&category=music&page_size=20"
        val root = JSONObject(get(url))
        val results = root.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<OpenResult>()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val file = r.optString("url")
            if (!file.startsWith("https://", true)) continue
            val format = r.optString("filetype").ifBlank {
                AUDIO_RE.find(file)?.groupValues?.get(1).orEmpty()
            }
            if (!AUDIO_RE.containsMatchIn(file) && format.isBlank()) continue
            val duration = r.optDouble("duration", 0.0).toLong() // milliseconds
            if (duration in 1..39_999) continue // jingles and samples
            val licence = buildString {
                append(r.optString("license").uppercase())
                val v = r.optString("license_version")
                if (v.isNotBlank()) append(' ').append(v)
            }.trim()
            val source = r.optString("source").ifBlank { r.optString("provider") }
            out += OpenResult(
                id = "ov-" + r.optString("id"),
                title = r.optString("title").ifBlank { "Без названия" },
                artist = r.optString("creator"),
                provider = "openverse",
                providerLabel = if (source.isBlank()) "Openverse" else "Openverse · $source",
                pageUrl = r.optString("foreign_landing_url"),
                fileUrl = file,
                format = format.uppercase(),
                durationMs = duration,
                license = licence,
                coverUrl = r.optString("thumbnail"),
            )
        }
        return out
    }

    private suspend fun internetArchive(query: String): List<OpenResult> {
        val escaped = query.replace(Regex("[()\":\\\\]"), " ")
        val q = "(title:($escaped) OR creator:($escaped)) AND mediatype:(audio) " +
            "AND -collection:(podcasts OR oldtimeradio OR librivoxaudio)"
        val url = "https://archive.org/advancedsearch.php?q=${enc(q)}" +
            "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=licenseurl&rows=10&output=json&sort[]=downloads+desc"
        val root = JSONObject(get(url))
        val docs = root.optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()

        val jobs = ArrayList<OpenResult>()
        val identifiers = ArrayList<String>()
        val creators = HashMap<String, String>()
        val licences = HashMap<String, String>()
        for (i in 0 until min(docs.length(), 8)) {
            val doc = docs.optJSONObject(i) ?: continue
            val id = doc.optString("identifier")
            if (id.isBlank()) continue
            identifiers += id
            creators[id] = firstString(doc.opt("creator"))
            licences[id] = doc.optString("licenseurl")
        }

        val batches = coroutineScope {
            identifiers.map { id ->
                async {
                    runCatching {
                        val meta = JSONObject(get("https://archive.org/metadata/${enc(id)}/files", 10_000))
                        val files = meta.optJSONArray("result") ?: JSONArray()
                        val found = ArrayList<OpenResult>()
                        for (f in 0 until files.length()) {
                            if (found.size >= 3) break
                            val file = files.optJSONObject(f) ?: continue
                            val name = file.optString("name")
                            val format = file.optString("format")
                            val isMp3 = format.contains("mp3", true) || name.endsWith(".mp3", true)
                            if (!isMp3 || name.isBlank()) continue
                            val title = file.optString("title").ifBlank {
                                name.substringBeforeLast('.').replace('_', ' ')
                            }
                            found += OpenResult(
                                id = "ia-$id-$name",
                                title = title,
                                artist = file.optString("creator").ifBlank {
                                    file.optString("artist").ifBlank { creators[id].orEmpty() }
                                },
                                provider = "archive",
                                providerLabel = "Internet Archive",
                                pageUrl = "https://archive.org/details/$id",
                                fileUrl = "https://archive.org/download/${enc(id)}/" +
                                    name.split('/').joinToString("/") { enc(it) },
                                format = "MP3",
                                durationMs = iaLength(file.optString("length")) * 1000L,
                                license = licenceFromUrl(licences[id].orEmpty()),
                            )
                        }
                        found
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        batches.forEach { jobs += it }
        return jobs
    }

    private suspend fun ccMixter(query: String): List<OpenResult> {
        val url = "https://ccmixter.org/api/query?f=js&search=${enc(query)}&search_type=all&limit=20"
        val text = get(url)
        val root = runCatching { JSONArray(text) }.getOrElse {
            runCatching { JSONObject(text).optJSONArray("results") ?: JSONArray() }.getOrDefault(JSONArray())
        }
        val out = ArrayList<OpenResult>()
        for (i in 0 until root.length()) {
            val upload = root.optJSONObject(i) ?: continue
            val files = upload.optJSONArray("files") ?: continue
            var fileUrl = ""
            var length = 0L
            for (f in 0 until files.length()) {
                val file = files.optJSONObject(f) ?: continue
                val download = file.optString("download_url")
                val name = file.optString("file_name")
                if (!download.contains(".mp3", true) && !name.contains(".mp3", true)) continue
                if (download.isBlank()) continue
                fileUrl = download.replaceFirst("http://", "https://")
                length = mmss(file.optJSONObject("file_format_info")?.optString("ps").orEmpty()) * 1000L
                break
            }
            if (fileUrl.isBlank()) continue
            out += OpenResult(
                id = "cc-" + upload.optString("upload_id"),
                title = upload.optString("upload_name").ifBlank { "Без названия" },
                artist = upload.optString("user_real_name").ifBlank { upload.optString("user_name") },
                provider = "ccmixter",
                providerLabel = "ccMixter",
                pageUrl = upload.optString("file_page_url"),
                fileUrl = fileUrl,
                format = "MP3",
                durationMs = length,
                license = upload.optString("license_name").ifBlank { "CC" },
            )
        }
        return out
    }

    /* --------------------------------- helpers -------------------------------- */

    private suspend fun get(url: String, timeout: Int = TIMEOUT_MS): String =
        withContext(Dispatchers.IO) {
            withTimeout(timeout.toLong()) {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", Http.UA)
                    .header("Accept", "application/json")
                    .build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
            }
        }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun firstString(value: Any?): String = when (value) {
        null -> ""
        is JSONArray -> value.optString(0)
        is String -> value
        else -> value.toString()
    }

    private fun iaLength(value: String): Long {
        if (value.isBlank()) return 0L
        if (value.matches(Regex("\\d+(\\.\\d+)?"))) return value.toDouble().toLong()
        return value.split(':').mapNotNull { it.toLongOrNull() }
            .fold(0L) { acc, part -> acc * 60 + part }
    }

    private fun mmss(value: String): Long {
        if (value.isBlank()) return 0L
        return value.split(':').mapNotNull { it.toLongOrNull() }
            .fold(0L) { acc, part -> acc * 60 + part }
    }

    internal fun licenceFromUrl(url: String): String {
        if (url.isBlank()) return "Archive.org"
        val match = Regex("licenses/([a-z-]+)/([\\d.]+)", RegexOption.IGNORE_CASE).find(url)
        if (match != null) {
            return "CC ${match.groupValues[1].uppercase()} ${match.groupValues[2]}"
        }
        if (url.contains("publicdomain", true)) return "Public Domain"
        return "см. страницу"
    }

    /* ------------------------------ query helpers ----------------------------- */

    private val RU = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )
    private const val EN_KEYS = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private const val RU_KEYS = "йцукенгшщзхъфывапролджэячсмитьбюё"

    private val toRu = EN_KEYS.mapIndexed { i, c -> c to RU_KEYS.getOrElse(i) { c } }.toMap()
    private val toEn = RU_KEYS.mapIndexed { i, c -> c to EN_KEYS.getOrElse(i) { c } }.toMap()

    internal fun norm(value: String): String = value.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    internal fun translit(value: String): String = buildString {
        for (c in norm(value)) append(RU[c] ?: c.toString())
    }

    internal fun latin(value: String): String = translit(value)
        .replace("kh", "h").replace("ck", "k").replace("ph", "f")
        .replace("w", "v").replace("x", "ks").replace("q", "k")
        .replace("j", "y").replace(Regex("c(?=[eiy])"), "s").replace("c", "k")
        .replace("ee", "i").replace("oo", "u")
        .replace(Regex("(.)\\1+"), "$1")

    private fun layoutSwap(value: String): String? {
        val s = value.lowercase()
        val cyr = s.count { it in 'а'..'я' || it == 'ё' }
        val lat = s.count { it in 'a'..'z' }
        if (cyr == 0 && lat == 0) return null
        val map = if (cyr > lat) toEn else toRu
        val out = s.map { map[it] ?: it }.joinToString("")
        return out.takeIf { it != s }
    }

    /** A string typed in the wrong layout has almost no vowels. */
    private fun looksMistyped(value: String): Boolean {
        val words = norm(value).split(' ').filter { it.length >= 4 }
        if (words.isEmpty()) return false
        fun vowels(w: String) = w.count { it in "aeiouyаеёиоуыэюя" }.toDouble() / w.length
        return words.count { vowels(it) < .2 } >= ceil(words.size / 2.0).toInt()
    }

    private val NOISE = Regex(
        "\\b(official|video|music video|lyrics?|lyric video|audio|hq|hd|4k|mp3|flac|" +
            "320 ?kbps|download|free download|full album|скачать|бесплатно|слушать|онлайн|" +
            "без регистрации|текст песни|клип)\\b",
        RegexOption.IGNORE_CASE,
    )

    internal fun cleanQuery(value: String): String = value
        .replace(Regex("\\([^)]*(official|video|lyric|audio|клип)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
        .replace(NOISE, " ")
        .replace(Regex("\\b(feat|ft)\\.?\\s", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    internal fun variants(query: String): List<String> {
        val base = cleanQuery(query).ifBlank { query.trim() }
        val out = LinkedHashSet<String>()
        val swapped = layoutSwap(base)
        if (swapped != null && looksMistyped(base)) out += swapped
        out += base
        if (base.any { it in 'а'..'я' || it == 'ё' }) out += translit(base)
        return out.filter { it.length >= 2 }.take(3)
    }

    private fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var best = i
            for (j in 1..b.length) {
                val v = min(
                    min(prev[j] + 1, cur[j - 1] + 1),
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
                cur[j] = v
                if (v < best) best = v
            }
            if (best > max) return max + 1
            prev = cur
        }
        return prev[b.length]
    }

    private fun wordHit(word: String, words: List<String>, whole: String): Double {
        var best = 0.0
        for (candidate in words) {
            if (candidate == word) return 1.0
            if (candidate.startsWith(word)) best = maxOf(best, if (word.length >= 2) .85 else .6)
            else if (word.length >= 3 && candidate.contains(word)) best = maxOf(best, .7)
            else if (best < .55) {
                val budget = when {
                    word.length >= 8 -> 2
                    word.length >= 4 -> 1
                    else -> 0
                }
                if (budget > 0 && distance(word, candidate, budget) <= budget) best = .55
            }
        }
        if (best < .7 && word.length >= 4 && whole.contains(word)) best = .7
        return best
    }

    internal fun score(query: String, title: String, artist: String): Double {
        val q = latin(query).split(' ').filter { it.isNotBlank() }
        if (q.isEmpty()) return 0.0
        val hay = latin("$artist $title")
        val titleKey = latin(title)
        val words = hay.split(' ').filter { it.isNotBlank() }
        val whole = hay.replace(" ", "")
        var sum = 0.0
        for (word in q) sum += wordHit(word, words, whole)
        val phrase = q.joinToString(" ")
        if (hay.contains(phrase)) sum += 2
        if (titleKey == phrase) sum += 2
        if (Regex("\\b(sample|loop|sfx|jingle|ringtone)\\b").containsMatchIn(titleKey)) sum -= 1.5
        return sum / (q.size + 4)
    }
}

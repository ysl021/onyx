package app.onyx.music

import java.net.URLDecoder
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Local library search that forgives the way people actually type.
 *
 *  • Cyrillic and Latin meet in the middle: both sides are transliterated, so
 *    «кино» finds "Kino" and "bladi" finds «Блади»;
 *  • a query typed in the wrong keyboard layout is tried as well
 *    («ьфвщттф» → madonna), and the UI can show which reading produced hits;
 *  • a word may be a prefix, sit inside a longer word, be glued together
 *    ("silentboy") or contain 1–2 typos (Damerau–Levenshtein, budget by length);
 *  • ranking: exact > prefix > inside > typo, title > artist > album > file name.
 */
object LocalSearch {

    data class Result(val tracks: List<LocalTrack>, val via: String? = null)

    private const val TYPO_TWO_FROM = 8
    private const val TYPO_ONE_FROM = 4

    private val RU = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        'і' to "i", 'ї' to "i", 'є' to "e", 'ґ' to "g",
    )
    private const val EN_KEYS = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private const val RU_KEYS = "йцукенгшщзхъфывапролджэячсмитьбюё"
    private val toRu = EN_KEYS.mapIndexed { i, c -> c to RU_KEYS.getOrElse(i) { c } }.toMap()
    private val toEn = RU_KEYS.mapIndexed { i, c -> c to EN_KEYS.getOrElse(i) { c } }.toMap()

    private val DIACRITICS = Regex("\\p{M}+")
    private val NOT_LETTER = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(value: String): String {
        val folded = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFKD)
            .replace(DIACRITICS, "")
            .replace('ё', 'е')
        return folded.replace(NOT_LETTER, " ").trim()
    }

    fun latin(value: String): String = buildString {
        for (c in normalize(value)) append(RU[c] ?: c.toString())
    }
        .replace("kh", "h").replace("ck", "k").replace("ph", "f")
        .replace("w", "v").replace("x", "ks").replace("q", "k")
        .replace("j", "y").replace(Regex("c(?=[eiy])"), "s").replace("c", "k")
        .replace("ee", "i").replace("oo", "u")
        .replace(Regex("(.)\\1+"), "$1")

    fun layoutSwap(query: String): String? {
        val s = query.lowercase()
        val cyr = s.count { it in 'а'..'я' || it == 'ё' }
        val lat = s.count { it in 'a'..'z' }
        if (cyr == 0 && lat == 0) return null
        val map = if (cyr > lat) toEn else toRu
        val swapped = s.map { map[it] ?: it }.joinToString("")
        return swapped.takeIf { it != s }
    }

    private data class Field(val whole: String, val words: List<String>)

    private data class Indexed(
        val signature: String,
        val title: Field,
        val artist: Field,
        val album: Field,
        val file: Field,
    )

    private val cache = HashMap<String, Indexed>()

    private fun field(value: String): Field {
        val latinValue = latin(value)
        return Field(latinValue.replace(" ", ""), latinValue.split(' ').filter { it.isNotBlank() })
    }

    private fun indexOf(track: LocalTrack): Indexed {
        val fileName = runCatching {
            URLDecoder.decode(track.uri.substringAfterLast('/'), "UTF-8")
                .substringBeforeLast('.')
        }.getOrDefault("")
        val signature = "${track.title}|${track.artist}|${track.album}|$fileName"
        val cached = synchronized(cache) { cache[track.id] }
        if (cached != null && cached.signature == signature) return cached
        val indexed = Indexed(
            signature = signature,
            title = field(track.title),
            artist = field(track.artist),
            album = field(track.album),
            file = field(fileName),
        )
        synchronized(cache) {
            cache[track.id] = indexed
            if (cache.size > 20_000) cache.clear()
        }
        return indexed
    }

    private fun wordScore(word: String, field: Field): Double {
        var best = 0.0
        for (candidate in field.words) {
            if (candidate == word) return 1.0
            if (candidate.startsWith(word)) best = max(best, if (word.length >= 2) .9 else .6)
            else if (word.length >= 3 && candidate.contains(word)) best = max(best, .7)
            else if (best < .55) {
                val budget = when {
                    word.length >= TYPO_TWO_FROM -> 2
                    word.length >= TYPO_ONE_FROM -> 1
                    else -> 0
                }
                if (budget > 0 && (
                        distance(word, candidate, budget) <= budget ||
                            (candidate.length > word.length &&
                                distance(word, candidate.take(word.length), budget) <= budget)
                        )
                ) {
                    best = .55
                }
            }
        }
        if (best < .7 && word.length >= 4 && field.whole.contains(word)) best = .7
        return best
    }

    private fun score(track: LocalTrack, words: List<String>, phrase: String): Double {
        val indexed = indexOf(track)
        var total = 0.0
        var missed = 0
        for (word in words) {
            val best = maxOf(
                wordScore(word, indexed.title) * 1.0,
                wordScore(word, indexed.artist) * .9,
                wordScore(word, indexed.album) * .6,
                wordScore(word, indexed.file) * .3,
            )
            if (best <= 0.0) missed++
            total += best
        }
        if (missed > if (words.size >= 4) 1 else 0) return 0.0
        val title = indexed.title.words.joinToString(" ")
        if (phrase.length > 2) {
            when {
                title.startsWith(phrase) -> total += 1.2
                title.contains(phrase) -> total += .8
                indexed.artist.words.joinToString(" ").startsWith(phrase) -> total += .6
            }
        }
        return total
    }

    fun search(tracks: List<LocalTrack>, query: String, limit: Int = 500): Result {
        fun run(spelling: String): List<Pair<Double, LocalTrack>> {
            val phrase = latin(spelling)
            val words = phrase.split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) return emptyList()
            val found = ArrayList<Pair<Double, LocalTrack>>()
            for (track in tracks) {
                val score = score(track, words, phrase)
                if (score > 0.0) found += score to track
            }
            found.sortByDescending { it.first }
            return found
        }

        val main = run(query)
        val swapped = layoutSwap(query)
        var via: String? = null
        var result = main
        if (swapped != null) {
            val other = run(swapped)
            if (other.isNotEmpty() && (main.isEmpty() || other.first().first > main.first().first * 1.4)) {
                result = other
                via = swapped
            }
        }
        return Result(result.take(limit).map { it.second }, via)
    }

    /** Damerau–Levenshtein with an early exit above [max]. */
    private fun distance(a: String, b: String, max: Int): Int {
        if (abs(a.length - b.length) > max) return max + 1
        var prev2: IntArray? = null
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var best = i
            for (j in 1..b.length) {
                var value = min(
                    min(prev[j] + 1, cur[j - 1] + 1),
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
                if (prev2 != null && i > 1 && j > 1 &&
                    a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]
                ) {
                    value = min(value, prev2!![j - 2] + 1)
                }
                cur[j] = value
                if (value < best) best = value
            }
            if (best > max) return max + 1
            prev2 = prev
            prev = cur
        }
        return prev[b.length]
    }
}

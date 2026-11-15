package app.onyx.music

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

enum class Mood { Calm, Deep, Energetic, Dark }

/**
 * Turns the cached analysis into two things the player can use: a list of
 * similar tracks and a mood-based session. Both are pure functions over
 * [TrackFeatures], so they can be reasoned about (and tested) without Android.
 */
object Similarity {

    data class Scored(
        val track: LocalTrack,
        val features: TrackFeatures,
        val score: Float,
    )

    private data class MoodTarget(
        val energy: Float,
        val bright: Float,
        val bass: Float,
        val minor: Boolean?,
    )

    private val MOODS = mapOf(
        Mood.Calm to MoodTarget(energy = 0.30f, bright = 0.35f, bass = 0.40f, minor = null),
        Mood.Deep to MoodTarget(energy = 0.50f, bright = 0.30f, bass = 0.70f, minor = true),
        Mood.Energetic to MoodTarget(energy = 0.82f, bright = 0.60f, bass = 0.65f, minor = null),
        Mood.Dark to MoodTarget(energy = 0.45f, bright = 0.20f, bass = 0.65f, minor = true),
    )

    fun find(
        seed: LocalTrack,
        tracks: List<LocalTrack>,
        featuresOf: (LocalTrack) -> TrackFeatures?,
        limit: Int = 15,
    ): List<Scored> {
        val seedFeatures = featuresOf(seed) ?: return emptyList()
        val seen = HashSet<String>()
        seen += seed.title.lowercase() + "|" + seed.artist.lowercase()
        return tracks.asSequence()
            .filter { it.id != seed.id }
            .mapNotNull { track ->
                val features = featuresOf(track) ?: return@mapNotNull null
                Scored(track, features, score(seedFeatures, features))
            }
            .filter { it.score >= 0.45f }
            .sortedByDescending { it.score }
            .filter { seen.add(it.track.title.lowercase() + "|" + it.track.artist.lowercase()) }
            .take(limit)
            .toList()
    }

    /**
     * Rank analysed tracks for a mood, then order them so each next track is
     * close to the previous one instead of jumping between tempos.
     */
    fun buildSession(
        tracks: List<LocalTrack>,
        featuresOf: (LocalTrack) -> TrackFeatures?,
        mood: Mood,
        minutes: Int,
        seed: LocalTrack? = null,
        artistCap: Int = 2,
    ): List<Scored> {
        val target = minutes * 60_000L
        val pool = tracks.asSequence()
            .filter { it.durationMs > 30_000 }
            .mapNotNull { track ->
                val features = featuresOf(track) ?: return@mapNotNull null
                Scored(track, features, moodScore(features, mood))
            }
            .sortedByDescending { it.score }
            .toList()
        if (pool.isEmpty()) return emptyList()

        val candidates = ArrayList<Scored>()
        var seconds = 0L
        for (item in pool) {
            candidates += item
            seconds += item.track.durationMs
            if (seconds >= target * 1.6 && candidates.size >= 8) break
        }

        val out = ArrayList<Scored>()
        val used = HashSet<String>()
        var current = seed?.let { s -> candidates.firstOrNull { it.track.id == s.id } }
            ?: candidates.first()
        var total = 0L
        while (total < target && out.size < candidates.size) {
            out += current
            used += current.track.id
            total += current.track.durationMs
            val lastArtists = out.takeLast(artistCap).map { it.track.artist.lowercase() }
            val capHit = lastArtists.size == artistCap &&
                lastArtists.all { it.isNotBlank() && it == lastArtists.first() }
            var best: Scored? = null
            var bestValue = -1f
            for (candidate in candidates) {
                if (candidate.track.id in used) continue
                if (capHit && candidate.track.artist.lowercase() == lastArtists.first()) continue
                val value = candidate.score * 0.55f + score(current.features, candidate.features) * 0.45f
                if (value > bestValue) {
                    bestValue = value
                    best = candidate
                }
            }
            if (best == null) break
            current = best
        }
        return out
    }

    private fun moodScore(features: TrackFeatures, mood: Mood): Float {
        val target = MOODS[mood] ?: return 0.5f
        var distance = 0f
        var weight = 0f
        distance += abs(features.energy - target.energy) * 2f; weight += 2f
        distance += abs(features.bright - target.bright); weight += 1f
        distance += abs(features.bass - target.bass); weight += 1f
        var score = 1f - distance / weight
        val minor = features.key?.contains("minor", ignoreCase = true)
        if (target.minor != null && minor != null) {
            score += if (minor == target.minor) 0.08f else -0.08f
        }
        return score.coerceIn(0f, 1f)
    }

    internal fun score(a: TrackFeatures, b: TrackFeatures): Float {
        var sum = 0f
        var weights = 0f
        fun add(weight: Float, value: Float?) {
            if (value == null) return
            sum += weight * value
            weights += weight
        }
        add(0.22f, bpmClose(a.bpm, b.bpm))
        add(0.16f, keyCompat(a.camelot, b.camelot))
        add(0.16f, near(a.energy, b.energy, 0.5f))
        add(0.10f, near(a.bass, b.bass, 0.6f))
        add(0.08f, near(a.vocal, b.vocal, 0.6f))
        add(0.10f, near(a.bright, b.bright, 0.6f))
        add(0.06f, a.lufs?.let { la -> b.lufs?.let { lb -> near(la.toFloat(), lb.toFloat(), 10f) } })
        if (weights <= 0f) return 0f
        val raw = sum / weights
        // Few known features → pull towards the middle instead of pretending certainty.
        return raw * min(1f, weights / 0.6f) + 0.35f * (1f - min(1f, weights / 0.6f))
    }

    internal fun keyCompat(a: String?, b: String?): Float? {
        val x = camelot(a) ?: return null
        val y = camelot(b) ?: return null
        val distance = min((x.first - y.first + 12) % 12, (y.first - x.first + 12) % 12)
        return when {
            distance == 0 -> if (x.second == y.second) 1f else 0.85f
            distance == 1 -> if (x.second == y.second) 0.8f else 0.45f
            distance == 2 -> if (x.second == y.second) 0.4f else 0.15f
            else -> 0f
        }
    }

    internal fun bpmClose(a: Double?, b: Double?): Float? {
        if (a == null || b == null || a <= 0 || b <= 0) return null
        val diff = min(abs(a - b), min(abs(a - 2 * b), abs(2 * a - b)))
        val ratio = diff / max(a, b)
        val value = exp(-((ratio / 0.07) * (ratio / 0.07))).toFloat()
        return value
    }

    private fun near(a: Float, b: Float, span: Float): Float =
        (1f - abs(a - b) / span).coerceIn(0f, 1f)

    private fun camelot(value: String?): Pair<Int, Char>? {
        if (value == null || value.length < 2) return null
        val number = value.dropLast(1).toIntOrNull() ?: return null
        val letter = value.last().uppercaseChar()
        if (letter != 'A' && letter != 'B') return null
        return number to letter
    }
}

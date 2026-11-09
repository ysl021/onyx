package app.onyx.youtube

import app.onyx.ui.tr

/**
 * Turns a video description (or a hand-typed list) into chapters.
 *
 * Supported shapes, all commonly found on YouTube mixes:
 *
 * ```
 * 0:00 Intro
 * 00:00 - Intro
 * (0:00) Intro
 * [0:00] Intro
 * 1:02:03 — Long set
 * ```
 *
 * Lines without a timestamp are ignored, so timestamps inside links and prose
 * do not produce junk chapters. A section ends where the next one starts; the
 * last one runs to [durationMs].
 */
object TimecodeParser {

    /** h:mm:ss or m:ss, never part of a longer number or a URL fragment. */
    private val STAMP = Regex("""(?<![\d:])(\d{1,2}):([0-5]\d)(?::([0-5]\d))?(?![\d:])""")

    private val DECOR = " \t-–—|•·:.,()[]{}\"'«»*/\\".toCharArray()

    private const val MIN_SECTION_MS = 8_000L

    fun parse(description: String, durationMs: Long): List<YtSection> {
        if (description.isBlank()) return emptyList()

        val raw = ArrayList<Pair<Long, String>>()
        description.lineSequence().forEach { line ->
            val trimmed = line.trim().trimStart('-', '*', '•', '·', '>').trim()
            if (trimmed.isEmpty() || trimmed.length > 200) return@forEach
            val match = STAMP.find(trimmed) ?: return@forEach
            // Chapter lists put the timestamp at the start of the line. If real
            // words precede it ("Recorded 12:30 in Berlin") the line is prose.
            if (match.range.first > 0) {
                val prefix = trimmed.substring(0, match.range.first)
                if (prefix.any { it.isLetter() }) return@forEach
            }

            val first = match.groupValues[1].toInt()
            val second = match.groupValues[2].toInt()
            val third = match.groupValues[3]
            val ms = if (third.isNotEmpty()) {
                (first * 3600L + second * 60L + third.toInt()) * 1000L
            } else {
                (first * 60L + second) * 1000L
            }
            if (durationMs > 0 && ms >= durationMs) return@forEach

            val label = trimmed
                .removeRange(match.range)
                .trim(*DECOR)
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
            raw += ms to label
        }

        val starts = raw
            .sortedBy { it.first }
            .distinctBy { it.first }
            .filter { (ms, _) -> ms >= 0 }

        if (starts.size < 2) return emptyList()

        val sections = ArrayList<YtSection>(starts.size)
        var number = 0
        for ((index, entry) in starts.withIndex()) {
            val start = entry.first
            val end = starts.getOrNull(index + 1)?.first ?: durationMs
            if (end <= start) continue
            if (end - start < MIN_SECTION_MS) continue
            number += 1
            val title = entry.second.ifBlank { tr("Трек $number", "Track $number") }
            sections += YtSection(title = title, startMs = start, endMs = end)
        }
        return sections
    }
}

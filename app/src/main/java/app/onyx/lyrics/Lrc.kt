package app.onyx.lyrics

object Lrc {

    private val lineTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val offsetTag = Regex("""\[offset:([+-]?\d+)]""")

    fun parse(raw: String): LyricsDoc? {
        val offset = offsetTag.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val result = ArrayList<LyricLine>()

        raw.lineSequence().forEach { src ->
            val tags = lineTag.findAll(src).toList()
            if (tags.isEmpty()) return@forEach
            val clean = lineTag.replace(src, "")
            val text = clean.replace(wordTag, "").trim()
            if (text.isEmpty()) return@forEach

            val words = parseWords(clean, offset)
            tags.forEach { m ->
                val start = stamp(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3)) + offset
                result.add(
                    LyricLine(
                        text = text,
                        startMs = start,
                        endMs = start + 4000,
                        words = if (words.isEmpty()) emptyList() else shiftWords(words, start),
                    ),
                )
            }
        }

        if (result.isEmpty()) return null
        val sorted = result.sortedBy { it.startMs }
        val lines = sorted.mapIndexed { i, line ->
            val end = sorted.getOrNull(i + 1)?.startMs ?: (line.startMs + 5000)
            line.copy(endMs = maxOf(line.endMs.coerceAtMost(end), line.startMs + 400))
        }
        return LyricsDoc(
            lines = lines,
            synced = true,
            karaoke = lines.any { it.words.isNotEmpty() },
            provider = "LRC",
        )
    }

    fun parsePlain(raw: String): LyricsDoc? {
        val lines = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { i, text -> LyricLine(text = text, startMs = -1L, endMs = -1L) }
            .toList()
        if (lines.isEmpty()) return null
        return LyricsDoc(lines = lines, synced = false, karaoke = false, provider = "Text")
    }

    /** Parses richsync "x" fields: <12.34>word <13.00>next */
    fun parseRichsyncLine(text: String, x: String): List<LyricWord> {
        val stamps = Regex("""<([^>]+)>""").findAll(x).toList()
        if (stamps.isEmpty()) return emptyList()
        val words = ArrayList<LyricWord>()
        for ((i, m) in stamps.withIndex()) {
            val start = parseFlexible(m.groupValues[1])
            val end = stamps.getOrNull(i + 1)?.let { parseFlexible(it.groupValues[1]) } ?: (start + 700)
            val from = m.range.last + 1
            val to = stamps.getOrNull(i + 1)?.range?.first ?: x.length
            val w = if (to > from) x.substring(from, to).trim() else ""
            if (w.isNotEmpty()) {
                words.add(LyricWord(w, start, maxOf(end, start + 120)))
            }
        }
        if (words.isEmpty()) return emptyList()
        return words
    }

    private fun shiftWords(words: List<LyricWord>, lineStart: Long): List<LyricWord> {
        val base = words.firstOrNull()?.startMs ?: return words
        // richsync word timestamps are absolute, so only shift if they look line-relative
        if (base < lineStart - 1500 || base > lineStart + 1500) return words
        return words
    }

    private fun parseWords(clean: String, offset: Long): List<LyricWord> {
        val stamps = wordTag.findAll(clean).toList()
        if (stamps.isEmpty()) return emptyList()
        val words = ArrayList<LyricWord>()
        for ((i, m) in stamps.withIndex()) {
            val start = stamp(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3)) + offset
            val from = m.range.last + 1
            val to = stamps.getOrNull(i + 1)?.range?.first ?: clean.length
            val w = clean.substring(from, to).trim()
            if (w.isNotEmpty()) {
                val end = stamps.getOrNull(i + 1)
                    ?.let { stamp(it.groupValues[1], it.groupValues[2], it.groupValues.getOrNull(3)) + offset }
                    ?: (start + 700)
                words.add(LyricWord(w, start, maxOf(end, start + 120)))
            }
        }
        return words
    }

    private fun stamp(min: String, sec: String, frac: String?): Long {
        val m = min.toLongOrNull() ?: 0
        val s = sec.toLongOrNull() ?: 0
        val ms = when (frac?.length ?: 0) {
            0 -> 0L
            1 -> (frac!!.toLongOrNull() ?: 0) * 100
            2 -> (frac!!.toLongOrNull() ?: 0) * 10
            else -> (frac!!.take(3).toLongOrNull() ?: 0)
        }
        return (m * 60 + s) * 1000 + ms
    }

    private fun parseFlexible(v: String): Long {
        val t = v.trim()
        return if (t.contains(':')) {
            val parts = t.split(':')
            val m = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val s = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            ((m * 60 + s) * 1000).toLong()
        } else {
            ((t.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        }
    }
}

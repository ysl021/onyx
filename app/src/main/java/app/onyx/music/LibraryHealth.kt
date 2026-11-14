package app.onyx.music

import java.nio.charset.Charset

/**
 * Local library health: broken titles, duplicates, missing covers and tags.
 *
 * Nothing here touches the files on disk. "Fixing" a title writes the same
 * override the tag editor uses, so the SAF file stays exactly as it was and the
 * change can be reverted by clearing the override.
 */
object LibraryHealth {

    data class BrokenTitle(
        val track: LocalTrack,
        val fixedTitle: String?,
        val fixedArtist: String?,
    )

    data class Duplicate(
        val best: LocalTrack,
        val others: List<LocalTrack>,
    )

    data class Report(
        val total: Int = 0,
        val artists: Int = 0,
        val albums: Int = 0,
        val noCover: List<LocalTrack> = emptyList(),
        val noArtist: List<LocalTrack> = emptyList(),
        val noAlbum: List<LocalTrack> = emptyList(),
        val broken: List<BrokenTitle> = emptyList(),
        val duplicates: List<Duplicate> = emptyList(),
    ) {
        val problems: Int
            get() = broken.size + duplicates.sumOf { it.others.size } + noCover.size
    }

    private val CYRILLIC = Regex("[Ѐ-ӿ]")
    private val LATIN_1 = Regex("[À-ÿ]")
    private val NORMALIZE = Regex("[^\\p{L}\\p{N}]+")

    fun analyze(tracks: List<LocalTrack>): Report {
        val artists = HashSet<String>()
        val albums = HashSet<String>()
        val noCover = ArrayList<LocalTrack>()
        val noArtist = ArrayList<LocalTrack>()
        val noAlbum = ArrayList<LocalTrack>()
        val broken = ArrayList<BrokenTitle>()

        for (track in tracks) {
            if (track.artist.isNotBlank()) artists += track.artist.lowercase()
            if (track.album.isNotBlank()) albums += (track.artist.lowercase() + "|" + track.album.lowercase())
            if (track.artPath.isNullOrBlank()) noCover += track
            if (track.artist.isBlank()) noArtist += track
            if (track.album.isBlank()) noAlbum += track

            val titleFix = fixMojibake(track.title)
            val artistFix = fixMojibake(track.artist)
            if (titleFix != null || artistFix != null) {
                broken += BrokenTitle(track, titleFix, artistFix)
            }
        }

        val groups = tracks
            .filter { it.title.isNotBlank() }
            .groupBy { normalize(it.title) + "|" + normalize(it.artist) }
        val duplicates = ArrayList<Duplicate>()
        for (group in groups.values) {
            if (group.size < 2) continue
            // A different edit of the same song is not a duplicate: split by length (±4 s).
            val sorted = group.sortedBy { it.durationMs }
            var cluster = arrayListOf(sorted.first())
            fun flush() {
                if (cluster.size > 1) {
                    val best = cluster.maxWithOrNull(
                        compareBy<LocalTrack> { it.size }.thenBy { it.durationMs },
                    ) ?: cluster.first()
                    duplicates += Duplicate(best, cluster.filter { it.id != best.id })
                }
            }
            for (track in sorted.drop(1)) {
                if (kotlin.math.abs(track.durationMs - cluster.first().durationMs) <= 4_000) {
                    cluster += track
                } else {
                    flush()
                    cluster = arrayListOf(track)
                }
            }
            flush()
        }

        return Report(
            total = tracks.size,
            artists = artists.size,
            albums = albums.size,
            noCover = noCover,
            noArtist = noArtist,
            noAlbum = noAlbum,
            broken = broken,
            duplicates = duplicates.sortedByDescending { it.others.size },
        )
    }

    /**
     * "Ïðèâåò" → "Привет": cp1251 bytes that were read as Latin-1. Only applied
     * when the source has no Cyrillic and the decoded text is mostly Cyrillic,
     * so normal Latin titles are never touched.
     */
    fun fixMojibake(value: String): String? {
        if (value.isBlank() || CYRILLIC.containsMatchIn(value)) return null
        val latinHigh = LATIN_1.findAll(value).count()
        if (latinHigh < 3) return null
        if (value.any { it.code > 255 }) return null
        val bytes = ByteArray(value.length) { value[it].code.toByte() }
        val decoded = runCatching {
            String(bytes, Charset.forName("windows-1251"))
        }.getOrNull() ?: return null
        val letters = decoded.count { it.isLetter() }.coerceAtLeast(1)
        val cyrillic = decoded.count { it in 'Ѐ'..'ӿ' }
        return decoded.takeIf { cyrillic.toFloat() / letters > 0.5f }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("\\.(mp3|flac|wav|m4a|ogg)$"), "")
        .replace(Regex("\\((\\d+|copy|копия|remaster(ed)?[^)]*)\\)|\\[[^]]*]"), " ")
        .replace(Regex("\\b(copy|копия|feat\\.?|ft\\.?)\\b.*$"), " ")
        .replace(NORMALIZE, " ")
        .trim()
}

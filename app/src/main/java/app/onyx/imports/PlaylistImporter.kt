package app.onyx.imports

import app.onyx.core.Http
import app.onyx.music.LocalLibrary
import app.onyx.music.LocalTrack
import app.onyx.music.PlaylistStreamTrack
import app.onyx.spotify.SpotifyClient
import app.onyx.youtube.YouTubeMusic
import app.onyx.youtube.YouTubeSearch
import app.onyx.youtube.YtSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

data class ImportedTrack(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val videoId: String? = null,
    val thumbnail: String? = null,
)

data class ImportedPlaylist(
    val name: String,
    val description: String = "",
    val coverUrl: String? = null,
    val tracks: List<ImportedTrack> = emptyList(),
)

data class ImportResult(
    val localIds: List<String>,
    val streams: List<PlaylistStreamTrack>,
    val unresolved: Int,
)

/**
 * Loads playlist metadata from public links and matches it against the local
 * library / YouTube Music. Only names and metadata are imported; no audio is
 * copied from Spotify, Deezer or Apple Music.
 */
object PlaylistImporter {

    suspend fun load(url: String, spotify: SpotifyClient): ImportedPlaylist =
        withContext(Dispatchers.IO) {
            val clean = url.trim()
            if (clean.isBlank()) error("Вставь ссылку на плейлист")
            val lower = clean.lowercase(Locale.ROOT)
            when {
                lower.contains("spotify.com") -> spotify(clean, spotify)
                lower.contains("youtube.com") || lower.contains("youtu.be") ->
                    youtube(clean)
                lower.contains("deezer.com") -> deezer(clean)
                lower.contains("music.apple.com") -> apple(clean)
                clean.startsWith("#EXTM3U") || clean.contains("#EXTINF") -> m3u(clean)
                clean.contains('\n') || clean.contains(',') -> csv(clean)
                else -> error("Неизвестная ссылка. Поддерживаются Spotify, YouTube, Deezer, Apple Music, M3U/CSV")
            }
        }

    suspend fun match(playlist: ImportedPlaylist, library: LocalLibrary): ImportResult {
        val localIds = mutableListOf<String>()
        val streams = mutableListOf<PlaylistStreamTrack>()
        var unresolved = 0
        for (item in playlist.tracks) {
            if (item.title.isBlank()) continue
            val local = findLocal(item, library.state.value.tracks)
            if (local != null) {
                if (local.id !in localIds) localIds += local.id
                continue
            }
            val video = item.videoId?.let {
                YtSearchResult(
                    id = it,
                    url = "https://www.youtube.com/watch?v=$it",
                    title = item.title,
                    channel = item.artist,
                    durationMs = item.durationMs,
                    thumbnailUrl = item.thumbnail,
                )
            } ?: findYouTube(item)
            if (video != null) {
                val id = "yt:${video.id}"
                if (streams.none { it.id == id }) {
                    streams += PlaylistStreamTrack(
                        id = id,
                        videoId = video.id,
                        title = video.title.ifBlank { item.title },
                        artist = video.channel.ifBlank { item.artist },
                        durationMs = video.durationMs.takeIf { it > 0 } ?: item.durationMs,
                        thumbnailUrl = video.thumbnailUrl ?: item.thumbnail,
                    )
                }
            } else {
                unresolved++
            }
        }
        return ImportResult(localIds, streams, unresolved)
    }

    private fun findLocal(item: ImportedTrack, tracks: List<LocalTrack>): LocalTrack? {
        val title = norm(item.title)
        val artist = norm(item.artist)
        val candidates = tracks.filter {
            norm(it.title) == title && (artist.isBlank() || norm(it.artist) == artist)
        }
        return candidates.firstOrNull {
            item.durationMs <= 0L || it.durationMs <= 0L ||
                kotlin.math.abs(it.durationMs - item.durationMs) <= 5_000L
        } ?: candidates.firstOrNull()
    }

    private suspend fun findYouTube(item: ImportedTrack): YtSearchResult? {
        val query = listOf(item.artist, item.title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null
        val result = runCatching { YouTubeSearch.searchMusic(query) }.getOrNull() ?: return null
        val title = norm(item.title)
        val artist = norm(item.artist)
        return result.items.firstOrNull {
            norm(it.title) == title && (artist.isBlank() || norm(it.channel) == artist)
        } ?: result.items.firstOrNull()
    }

    private suspend fun spotify(url: String, client: SpotifyClient): ImportedPlaylist {
        val id = Regex("playlist/([A-Za-z0-9]+)").find(url)?.groupValues?.getOrNull(1)
            ?: error("Не удалось найти ID плейлиста Spotify")
        val playlist = client.playlist(id) ?: error("Подключи Spotify или проверь ссылку")
        val tracks = client.playlistTracks(id).orEmpty().map {
            ImportedTrack(
                title = it.name,
                artist = it.artists.joinToString(", ") { artist -> artist.name },
                album = it.album.name,
                durationMs = it.durationMs,
            )
        }
        return ImportedPlaylist(
            name = playlist.name.ifBlank { "Spotify playlist" },
            description = "Импортировано из Spotify",
            coverUrl = playlist.images.firstOrNull()?.url,
            tracks = tracks,
        )
    }

    private suspend fun youtube(url: String): ImportedPlaylist {
        val id = Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.getOrNull(1)
            ?: error("Не удалось найти ID плейлиста YouTube")
        val tracks = YouTubeMusic.playlistTracks(id).map {
            ImportedTrack(
                title = it.title,
                artist = it.channel,
                durationMs = it.durationMs,
                videoId = it.id,
                thumbnail = it.thumbnailUrl,
            )
        }
        if (tracks.isEmpty()) error("YouTube не вернул треки плейлиста")
        return ImportedPlaylist(
            name = "YouTube playlist",
            description = "Импортировано из YouTube",
            coverUrl = tracks.firstOrNull()?.thumbnail,
            tracks = tracks,
        )
    }

    private fun deezer(url: String): ImportedPlaylist {
        val id = Regex("playlist/(\\d+)").find(url)?.groupValues?.getOrNull(1)
            ?: error("Не удалось найти ID плейлиста Deezer")
        val request = Request.Builder()
            .url("https://api.deezer.com/playlist/$id")
            .header("User-Agent", Http.UA)
            .build()
        val body = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Deezer вернул HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val root = JSONObject(body)
        val data = root.optJSONObject("tracks")?.optJSONArray("data") ?: error("В плейлисте Deezer нет треков")
        val tracks = mutableListOf<ImportedTrack>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            tracks += ImportedTrack(
                title = item.optString("title"),
                artist = item.optJSONObject("artist")?.optString("name").orEmpty(),
                album = item.optJSONObject("album")?.optString("title").orEmpty(),
                durationMs = item.optLong("duration", 0L) * 1000L,
            )
        }
        return ImportedPlaylist(
            name = root.optString("title").ifBlank { "Deezer playlist" },
            description = "Импортировано из Deezer",
            coverUrl = root.optString("picture_xl").takeIf { it.isNotBlank() },
            tracks = tracks,
        )
    }

    private fun apple(url: String): ImportedPlaylist {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        val html = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Apple Music вернул HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val names = Regex("\"name\":\"([^\"]{1,120})\"").findAll(html)
            .map { it.groupValues[1] }
            .map { it.replace("\\/", "/").replace("\\u0026", "&") }
            .filter { it.isNotBlank() && !it.contains("Apple Music", true) }
            .distinct()
            .take(100)
            .toList()
        if (names.isEmpty()) error("Apple Music не удалось разобрать. Экспортируй плейлист в M3U/CSV")
        return ImportedPlaylist(
            name = "Apple Music playlist",
            description = "Импортировано из Apple Music (best-effort)",
            tracks = names.map { ImportedTrack(title = it, artist = "") },
        )
    }

    private fun m3u(text: String): ImportedPlaylist {
        val tracks = mutableListOf<ImportedTrack>()
        var pending: String? = null
        for (line in text.lineSequence()) {
            val value = line.trim()
            if (value.startsWith("#EXTINF:", ignoreCase = true)) {
                val meta = value.substringAfter(',', "")
                val title = meta.substringAfter(" - ", meta).trim()
                val artist = meta.substringBefore(" - ", "").trim()
                pending = if (title.isBlank()) null else "$artist\u0000$title"
            } else if (value.isNotBlank() && !value.startsWith("#")) {
                val meta = pending?.split('\u0000', limit = 2)
                tracks += ImportedTrack(
                    title = meta?.getOrNull(1) ?: value.substringAfterLast('/'),
                    artist = meta?.getOrNull(0).orEmpty(),
                )
                pending = null
            }
        }
        if (tracks.isEmpty()) error("В M3U нет треков")
        return ImportedPlaylist(name = "M3U playlist", description = "Импортировано из M3U", tracks = tracks)
    }

    private fun csv(text: String): ImportedPlaylist {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) error("Пустой файл")
        val start = if (lines.first().lowercase().startsWith("title")) 1 else 0
        val tracks = lines.drop(start).mapNotNull { line ->
            val cells = line.split(',', ';', '\t').map { it.trim() }
            if (cells.isEmpty() || cells[0].isBlank()) return@mapNotNull null
            ImportedTrack(
                title = cells.getOrNull(0).orEmpty(),
                artist = cells.getOrNull(1).orEmpty(),
                album = cells.getOrNull(2).orEmpty(),
                durationMs = cells.getOrNull(3)?.toLongOrNull()?.times(1000L) ?: 0L,
            )
        }
        if (tracks.isEmpty()) error("В CSV нет треков")
        return ImportedPlaylist(name = "CSV playlist", description = "Импортировано из CSV", tracks = tracks)
    }

    private fun norm(value: String): String = value.lowercase(Locale.ROOT)
        .replace('\u00A0', ' ')
        .replace(Regex("[\\p{Punct}\\s]+"), "")
}

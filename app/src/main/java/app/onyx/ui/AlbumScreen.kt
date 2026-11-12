package app.onyx.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.music.LocalTrack
import app.onyx.ui.components.AsyncArt
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxBlack
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite
import app.onyx.youtube.YtAlbum
import app.onyx.youtube.YtSearchResult
import app.onyx.youtube.YouTubeMusic
import java.io.File

/** Where the user wants to go when long-pressing a cover. */
data class AlbumSelection(
    val albumId: Long = 0L,
    val album: String = "",
    val artist: String = "",
    val title: String = "",
    val artPath: String? = null,
)

/** Provided once at the app root so every track row can open an album. */
val LocalOpenAlbum = compositionLocalOf<(AlbumSelection) -> Unit> { {} }

fun albumSelectionOf(track: LocalTrack): AlbumSelection = AlbumSelection(
    albumId = track.albumId,
    album = track.album,
    artist = track.artist,
    title = track.title,
    artPath = track.artPath,
)

@Composable
fun AlbumScreen(
    selection: AlbumSelection,
    controller: AppController,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val library by controller.localLibrary.state.collectAsStateWithLifecycle()
    val playback by controller.localPlayer.state.collectAsStateWithLifecycle()

    val localTracks = remember(library.tracks, selection) {
        val list = when {
            selection.albumId > 0L -> library.tracks.filter { it.albumId == selection.albumId }
            selection.album.isNotBlank() -> library.tracks.filter {
                it.album.equals(selection.album, ignoreCase = true) &&
                    (selection.artist.isBlank() ||
                        it.artist.equals(selection.artist, ignoreCase = true))
            }
            else -> emptyList()
        }
        list.sortedWith(
            compareBy<LocalTrack> { it.trackNo == 0 }
                .thenBy { it.trackNo }
                .thenBy { it.title.lowercase() },
        )
    }

    var ytAlbum by remember(selection) { mutableStateOf<YtAlbum?>(null) }
    var ytTracks by remember(selection) { mutableStateOf<List<YtSearchResult>>(emptyList()) }
    var loading by remember(selection) { mutableStateOf(false) }
    var error by remember(selection) { mutableStateOf("") }

    LaunchedEffect(selection, localTracks.isEmpty()) {
        if (localTracks.isNotEmpty()) return@LaunchedEffect
        val query = selection.album.ifBlank { selection.title }.trim()
        if (query.isBlank()) {
            error = tr("Альбом не указан", "No album")
            return@LaunchedEffect
        }
        loading = true
        error = ""
        runCatching {
            val album = YouTubeMusic.searchAlbum(query, selection.artist)
                ?: throw IllegalStateException(tr("Альбом не найден", "Album not found"))
            val tracks = YouTubeMusic.albumTracks(album)
            if (tracks.isEmpty()) throw IllegalStateException(tr("В альбоме нет треков", "Album is empty"))
            album to tracks
        }.onSuccess { (album, tracks) ->
            ytAlbum = album
            ytTracks = tracks
        }.onFailure {
            error = it.message ?: tr("Альбом не найден", "Album not found")
        }
        loading = false
    }

    val title = selection.album.ifBlank {
        ytAlbum?.title ?: selection.title.ifBlank { tr("Альбом", "Album") }
    }
    val artist = selection.artist.ifBlank { ytAlbum?.subtitle.orEmpty() }
    val artModel = localTracks.firstOrNull()?.artPath?.let { File(it) }
        ?: ytAlbum?.thumbnailUrl
        ?: selection.artPath?.let { File(it) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OnyxBlack)
            .padding(horizontal = 18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = tr("Назад", "Back"),
                size = 40.dp,
                iconSize = 18.dp,
                onClick = onBack,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                SectionLabel(tr("Альбом", "Album"))
                Text(
                    title,
                    color = OnyxWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artist.isNotBlank()) {
                    Text(
                        artist,
                        color = OnyxFaint,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncArt(
                model = artModel,
                modifier = Modifier
                    .size(108.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    tr("${localTracks.size.coerceAtLeast(ytTracks.size)} треков", "${localTracks.size.coerceAtLeast(ytTracks.size)} tracks"),
                    color = OnyxDim,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(
                        text = tr("Играть", "Play"),
                        selected = true,
                        icon = Icons.Rounded.PlayArrow,
                        onClick = {
                            if (localTracks.isNotEmpty()) {
                                controller.localPlayer.play(localTracks.first(), localTracks)
                            } else if (ytTracks.isNotEmpty()) {
                                controller.youtubeRadio.playQueue(ytTracks, artist)
                            }
                        },
                    )
                    GlassPill(
                        text = tr("Перемешать", "Shuffle"),
                        selected = false,
                        icon = Icons.Rounded.Shuffle,
                        onClick = {
                            if (localTracks.isNotEmpty()) {
                                controller.localPlayer.play(
                                    localTracks.shuffled().first(),
                                    localTracks.shuffled(),
                                )
                            } else if (ytTracks.isNotEmpty()) {
                                controller.youtubeRadio.playQueue(ytTracks.shuffled(), artist)
                            }
                        },
                    )
                }
            }
        }

        when {
            localTracks.isNotEmpty() -> {
                LazyColumn(Modifier.weight(1f)) {
                    items(localTracks, key = { it.id }) { track ->
                        val active = playback.current?.id == track.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) OnyxWhite.copy(alpha = 0.07f) else OnyxBlack)
                                .clickable {
                                    controller.localPlayer.play(track, localTracks)
                                }
                                .padding(vertical = 7.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (track.trackNo > 0) track.trackNo.toString() else "•",
                                color = OnyxFaint,
                                fontSize = 10.sp,
                                modifier = Modifier.width(24.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    color = OnyxWhite,
                                    fontSize = 14.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    track.artist.ifBlank { track.album },
                                    color = OnyxFaint,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                fmtAlbumDuration(track.durationMs),
                                color = OnyxFaint,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
            loading -> {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(tr("Ищу альбом…", "Searching album…"), color = OnyxFaint, fontSize = 12.sp)
                }
            }
            error.isNotBlank() -> {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(error, color = OnyxFaint, fontSize = 12.sp)
                }
            }
            else -> {
                LazyColumn(Modifier.weight(1f)) {
                    items(ytTracks, key = { it.id }) { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    controller.youtubeRadio.playQueue(listOf(track), artist)
                                }
                                .padding(vertical = 7.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncArt(
                                model = track.thumbnailUrl,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(9.dp)),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    color = OnyxWhite,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    track.channel,
                                    color = OnyxFaint,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                fmtAlbumDuration(track.durationMs),
                                color = OnyxFaint,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fmtAlbumDuration(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

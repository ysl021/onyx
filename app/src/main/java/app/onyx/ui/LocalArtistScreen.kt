package app.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.music.LocalTrack
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite
import coil.compose.AsyncImage
import java.io.File

/** Opens the local artist page from anywhere inside the library. */
val LocalOpenArtist = compositionLocalOf<(String) -> Unit> { {} }

/** Opens a local playlist from the global search. */
val LocalOpenPlaylist = compositionLocalOf<(String) -> Unit> { {} }

/**
 * The local counterpart of the YouTube artist page: play/shuffle, the artist's
 * albums and every track, without leaving the library.
 */
@Composable
fun LocalArtistScreen(
    controller: AppController,
    name: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val library by controller.localLibrary.state.collectAsStateWithLifecycle()
    val playback by controller.localPlayer.state.collectAsStateWithLifecycle()
    val openAlbum = LocalOpenAlbum.current
    val tracks = remember(library.tracks, name) {
        library.tracks.filter { it.artist.equals(name, ignoreCase = true) }
    }
    val albums = remember(tracks) {
        tracks.filter { it.album.isNotBlank() }
            .groupBy { it.album.lowercase() }
            .values
            .sortedBy { it.first().album.lowercase() }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = tr("Назад", "Back"),
                    size = 42.dp,
                    iconSize = 20.dp,
                    onClick = onBack,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = name,
                        color = OnyxWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = tr(
                            "${tracks.size} треков · ${albums.size} альбомов",
                            "${tracks.size} tracks · ${albums.size} albums",
                        ),
                        color = OnyxFaint,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = tr("Играть", "Play"),
                    selected = true,
                    enabled = tracks.isNotEmpty(),
                    onClick = {
                        tracks.firstOrNull()?.let { controller.localPlayer.play(it, tracks) }
                    },
                )
                GlassPill(
                    text = tr("Перемешать", "Shuffle"),
                    selected = false,
                    enabled = tracks.isNotEmpty(),
                    onClick = {
                        val shuffled = tracks.shuffled()
                        shuffled.firstOrNull()?.let { controller.localPlayer.play(it, shuffled) }
                    },
                )
            }
        }

        if (albums.isNotEmpty()) {
            item {
                SectionLabel(tr("Альбомы", "Albums"), Modifier.padding(top = 8.dp))
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    albums.take(12).forEach { group ->
                        val first = group.first()
                        Column(
                            modifier = Modifier
                                .width(108.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { openAlbum(albumSelectionOf(first)) },
                        ) {
                            ArtistAlbumArt(first)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = first.album,
                                color = OnyxWhite,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionLabel(tr("Треки", "Tracks"), Modifier.padding(top = 10.dp))
        }
        items(tracks, key = { it.id }) { track ->
            LocalSearchTrackRow(
                track = track,
                playing = playback.current?.id == track.id && playback.isPlaying,
                onClick = { controller.localPlayer.play(track, tracks) },
            )
        }
    }
}

@Composable
private fun ArtistAlbumArt(track: LocalTrack) {
    Box(
        modifier = Modifier
            .size(108.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(OnyxLine.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        val path = track.artPath
        val model = path?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http") || it.startsWith("content://")) it else File(it)
        }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = track.album,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(108.dp),
            )
        } else {
            Text(
                text = track.album.take(1).uppercase(),
                color = OnyxFaint,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

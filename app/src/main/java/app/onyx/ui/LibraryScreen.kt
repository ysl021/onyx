package app.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.music.LocalTrack
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite
import coil.compose.AsyncImage
import java.io.File

private enum class LibrarySection { Tracks, Albums, Artists, Playlists }

/**
 * One place for "my music": local tracks, albums and artists next to the
 * playlists that used to live in their own bottom-tab. The source switch
 * (local / Spotify) lives inside the playlist section, not at the app root.
 */
@Composable
fun LibraryScreen(
    controller: AppController,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val library by controller.localLibrary.state.collectAsStateWithLifecycle()
    val openAlbum = LocalOpenAlbum.current
    var section by rememberSaveable { mutableStateOf(LibrarySection.Tracks) }
    var artistFilter by rememberSaveable { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassPill(
                text = tr("Треки", "Tracks"),
                selected = section == LibrarySection.Tracks,
                onClick = {
                    artistFilter = ""
                    section = LibrarySection.Tracks
                },
            )
            GlassPill(
                text = tr("Альбомы", "Albums"),
                selected = section == LibrarySection.Albums,
                onClick = { section = LibrarySection.Albums },
            )
            GlassPill(
                text = tr("Артисты", "Artists"),
                selected = section == LibrarySection.Artists,
                onClick = { section = LibrarySection.Artists },
            )
            GlassPill(
                text = tr("Плейлисты", "Playlists"),
                selected = section == LibrarySection.Playlists,
                onClick = { section = LibrarySection.Playlists },
            )
        }

        Box(Modifier.weight(1f)) {
            when (section) {
                LibrarySection.Tracks -> MusicScreen(
                    controller = controller,
                    seedQuery = artistFilter,
                )

                LibrarySection.Albums -> AlbumGrid(
                    tracks = library.tracks,
                    onOpen = { openAlbum(albumSelectionOf(it)) },
                )

                LibrarySection.Artists -> ArtistList(
                    tracks = library.tracks,
                    onOpen = { name ->
                        artistFilter = name
                        section = LibrarySection.Tracks
                    },
                )

                LibrarySection.Playlists -> PlaylistsScreen(
                    controller = controller,
                    onBack = {},
                    onOpenSettings = onOpenSettings,
                    embedded = true,
                )
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    tracks: List<LocalTrack>,
    onOpen: (LocalTrack) -> Unit,
) {
    val groups = remember(tracks) {
        tracks.filter { it.album.isNotBlank() }
            .groupBy { it.album.lowercase() to it.artist.lowercase() }
            .values
            .filter { it.isNotEmpty() }
            .sortedBy { it.first().album.lowercase() }
    }
    if (groups.isEmpty()) {
        EmptyHint(tr("Альбомов пока нет", "No albums yet"))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(groups, key = { "${it.first().album.lowercase()}|${it.first().artist.lowercase()}" }) { list ->
            val first = list.first()
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onOpen(first) },
            ) {
                AlbumArt(first.artPath, first.album)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = first.album,
                    color = OnyxWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        if (first.artist.isNotBlank()) append(first.artist)
                        if (first.artist.isNotBlank()) append(" · ")
                        append(list.size).append(' ').append(tr("треков", "tracks"))
                    },
                    color = OnyxFaint,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AlbumArt(artPath: String?, album: String) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(OnyxLine.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        val model = artPath?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http") || it.startsWith("content://")) it else File(it)
        }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = album,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(OnyxWhite.copy(alpha = 0.10f), Color.Transparent),
                        ),
                    ),
            )
            Text(
                text = album.take(1).uppercase(),
                color = OnyxDim,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun ArtistList(
    tracks: List<LocalTrack>,
    onOpen: (String) -> Unit,
) {
    val artists = remember(tracks) {
        tracks.filter { it.artist.isNotBlank() }
            .groupBy { it.artist.lowercase() }
            .values
            .sortedByDescending { it.size }
    }
    if (artists.isEmpty()) {
        EmptyHint(tr("Артистов пока нет", "No artists yet"))
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        lazyItems(artists, key = { it.first().artist.lowercase() }) { list ->
            val first = list.first()
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(first.artist) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = first.artist,
                            color = OnyxWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${list.size} ${tr("треков", "tracks")}",
                            color = OnyxFaint,
                            fontSize = 9.sp,
                        )
                    }
                    Text(
                        text = "→",
                        color = OnyxFaint,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = OnyxFaint, fontSize = 12.sp)
    }
}

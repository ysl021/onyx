package app.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onyx.music.LocalSearch
import app.onyx.music.LocalTrack
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite
import coil.compose.AsyncImage
import java.io.File
import java.util.Locale

/**
 * Artists and albums found by the local search, drawn as the same kind of cards
 * a music service would show above the track list. Tapping an artist opens the
 * local artist page, tapping an album opens the album screen.
 */
@Composable
fun LocalSearchSections(
    grouped: LocalSearch.Grouped,
    modifier: Modifier = Modifier,
) {
    if (grouped.artists.isEmpty() && grouped.albums.isEmpty()) return
    val openArtist = LocalOpenArtist.current
    val openAlbum = LocalOpenAlbum.current

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (grouped.artists.isNotEmpty()) {
            SectionLabel(
                tr("Артисты", "Artists"),
                Modifier.padding(horizontal = 18.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                grouped.artists.forEach { hit ->
                    Column(
                        modifier = Modifier
                            .width(82.dp)
                            .clickable { openArtist(hit.name) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Artwork(
                            path = hit.artPath,
                            fallback = hit.name,
                            circular = true,
                            size = 62.dp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = hit.name,
                            color = OnyxWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${hit.count} ${tr("треков", "tracks")}",
                            color = OnyxFaint,
                            fontSize = 8.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        if (grouped.albums.isNotEmpty()) {
            SectionLabel(
                tr("Альбомы", "Albums"),
                Modifier.padding(horizontal = 18.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                grouped.albums.forEach { hit ->
                    Column(
                        modifier = Modifier
                            .width(118.dp)
                            .clickable { openAlbum(albumSelectionOf(hit.track)) },
                    ) {
                        Artwork(
                            path = hit.artPath,
                            fallback = hit.album,
                            circular = false,
                            size = 118.dp,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = hit.album,
                            color = OnyxWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = hit.artist.ifBlank { "${hit.count} ${tr("треков", "tracks")}" },
                            color = OnyxFaint,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Compact track row used by the Search tab's local section. */
@Composable
fun LocalSearchTrackRow(
    track: LocalTrack,
    playing: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            path = track.artPath,
            fallback = track.title,
            circular = false,
            size = 44.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = OnyxWhite,
                fontSize = 13.sp,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                color = OnyxFaint,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatTime(track.durationMs),
            color = OnyxFaint,
            fontSize = 9.sp,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = if (playing) OnyxWhite else OnyxDim,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun Artwork(
    path: String?,
    fallback: String,
    circular: Boolean,
    size: androidx.compose.ui.unit.Dp,
) {
    val shape = if (circular) CircleShape else RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(OnyxLine.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        val model = path?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http") || it.startsWith("content://")) it else File(it)
        }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = fallback,
                modifier = Modifier.size(size),
            )
        } else {
            Box(
                Modifier
                    .size(size)
                    .background(
                        Brush.linearGradient(
                            listOf(OnyxWhite.copy(alpha = 0.10f), Color.Transparent),
                        ),
                    ),
            )
            Text(
                text = fallback.take(1).uppercase(Locale.ROOT),
                color = OnyxDim,
                fontSize = (size.value / 3f).sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val minutes = total / 60
    val seconds = total % 60
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

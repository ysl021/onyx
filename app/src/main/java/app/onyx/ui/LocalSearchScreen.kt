package app.onyx.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.music.LocalSearch
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite

/**
 * Local half of the global search: the same grouped result card as in the
 * library (artists, albums) plus a compact track list.
 */
@Composable
fun LocalSearchScreen(
    controller: AppController,
    modifier: Modifier = Modifier,
) {
    val library by controller.localLibrary.state.collectAsStateWithLifecycle()
    val playback by controller.localPlayer.state.collectAsStateWithLifecycle()
    val playlistsState by controller.localPlaylists.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val openPlaylist = LocalOpenPlaylist.current
    val grouped = remember(library.tracks, query) {
        if (query.trim().length < 2) {
            LocalSearch.Grouped()
        } else {
            LocalSearch.searchGrouped(library.tracks, query)
        }
    }
    val playlistHits = remember(playlistsState.playlists, query) {
        val q = query.trim()
        if (q.length < 2) {
            emptyList()
        } else {
            playlistsState.playlists
                .map { it to LocalSearch.nameScore(it.name, q) }
                .filter { it.second > 0.0 }
                .sortedByDescending { it.second }
                .take(8)
        }
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            singleLine = true,
            placeholder = {
                Text(
                    tr("Артист, альбом или трек", "Artist, album or track"),
                    color = OnyxFaint,
                    fontSize = 12.sp,
                )
            },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = OnyxFaint)
            },
            textStyle = androidx.compose.ui.text.TextStyle(color = OnyxWhite, fontSize = 13.sp),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OnyxLine,
                unfocusedBorderColor = OnyxLine,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = OnyxWhite,
            ),
        )

        if (query.trim().length < 2) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    tr(
                        "Ищи по всей библиотеке: артисты, альбомы, треки.\nПрощает опечатки, транслит и неверную раскладку.",
                        "Search the whole library: artists, albums, tracks.\nTypos, transliteration and the wrong keyboard layout are fine.",
                    ),
                    color = OnyxFaint,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            return@Column
        }

        if (grouped.via != null) {
            Text(
                text = tr("Искал как «${grouped.via}»", "Searched as «${grouped.via}»"),
                color = OnyxFaint,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
            )
        }

        if (grouped.tracks.isEmpty() && grouped.artists.isEmpty() &&
            grouped.albums.isEmpty() && playlistHits.isEmpty()
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tr("Ничего не нашлось", "Nothing found"), color = OnyxFaint, fontSize = 12.sp)
            }
            return@Column
        }

        if (playlistHits.isNotEmpty()) {
            SectionLabel(
                tr("Плейлисты · ${playlistHits.size}", "Playlists · ${playlistHits.size}"),
                Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                playlistHits.forEach { (playlist, _) ->
                    GlassCard(
                        modifier = Modifier
                            .width(150.dp)
                            .clickable { openPlaylist(playlist.id) },
                    ) {
                        Text(
                            text = playlist.name,
                            color = OnyxWhite,
                            fontSize = 12.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            text = tr(
                                "${playlist.trackIds.size} треков",
                                "${playlist.trackIds.size} tracks",
                            ),
                            color = OnyxFaint,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
        }

        LocalSearchSections(
            grouped = grouped,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (grouped.tracks.isNotEmpty()) {
            SectionLabel(
                tr("Треки · ${grouped.tracks.size}", "Tracks · ${grouped.tracks.size}"),
                Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 2.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(grouped.tracks, key = { it.id }) { track ->
                    LocalSearchTrackRow(
                        track = track,
                        playing = playback.current?.id == track.id && playback.isPlaying,
                        onClick = { controller.localPlayer.play(track, grouped.tracks) },
                    )
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

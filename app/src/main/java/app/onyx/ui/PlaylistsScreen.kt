package app.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.onyx.spotify.SpotifyPlaylist
import app.onyx.spotify.SpotifyTrack
import app.onyx.ui.components.AsyncArt
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxWhite
import kotlinx.coroutines.launch

@Composable
fun PlaylistsScreen(
    controller: AppController,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = app.onyx.core.PrefsData())
    val media by controller.media.state.collectAsStateWithLifecycle()
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<SpotifyPlaylist?>(null) }
    var tracks by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var detailLoading by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }

    LaunchedEffect(prefs.language, version) {
        loading = true
        playlists = controller.spotify.playlists().orEmpty()
        loading = false
    }

    LaunchedEffect(selected, version) {
        val p = selected ?: return@LaunchedEffect
        detailLoading = true
        tracks = controller.spotify.playlistTracks(p.id).orEmpty()
        detailLoading = false
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = tr("Назад", "Back"),
                size = 40.dp,
                iconSize = 18.dp,
                onClick = { if (selected != null) selected = null else onBack() },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    selected?.name ?: tr("Плейлисты", "Playlists"),
                    color = OnyxWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    selected?.let { "${it.tracks.total} ${tr("треков", "tracks")}" }
                        ?: tr("Spotify · управление", "Spotify · manage"),
                    color = OnyxFaint,
                    fontSize = 10.sp,
                )
            }
            CircleButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = tr("Обновить", "Refresh"),
                size = 38.dp,
                iconSize = 17.dp,
                onClick = { version++ },
            )
        }

        if (selected == null) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 132.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { selected = playlist }
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncArt(
                            model = playlist.images.firstOrNull()?.url,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                playlist.name,
                                color = OnyxWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${playlist.tracks.total} ${tr("треков", "tracks")}",
                                color = OnyxDim,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                if (!loading && playlists.isEmpty()) {
                    item {
                        GlassCard {
                            SectionLabel(tr("Не удалось загрузить", "Could not load"))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                tr(
                                    "Spotify API недоступен в этом регионе или нужен перелогин. Плейлисты и запуск треков могут не работать.",
                                    "Spotify API is unavailable in this region or a re-login is required. Playlists and track playback may not work.",
                                ),
                                color = OnyxDim,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            GlassPill(
                                text = tr("Открыть настройки", "Open settings"),
                                selected = true,
                                onClick = onOpenSettings,
                            )
                        }
                    }
                }
                if (loading) {
                    item { Text(tr("Загружаем…", "Loading…"), color = OnyxFaint, fontSize = 11.sp) }
                }
            }
        } else {
            val playlist = selected ?: return@Column
            val current = media.now.spotifyTrackId
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 132.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    GlassPill(
                        text = tr("Добавить текущий трек", "Add current track"),
                        selected = false,
                        icon = Icons.Rounded.Add,
                        enabled = current != null,
                        onClick = {
                            val uri = current?.let { "spotify:track:$it" }
                            if (uri != null) {
                                scope.launch {
                                    val ok = controller.spotify.addToPlaylist(playlist.id, uri)
                                    controller.toast(
                                        if (ok) tr("Трек добавлен", "Track added")
                                        else tr("Не удалось добавить — нужен Premium", "Could not add — Premium required"),
                                    )
                                    if (ok) version++
                                }
                            }
                        },
                    )
                }
                items(tracks, key = { it.uri.ifBlank { it.id } }) { track ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncArt(
                            model = track.album.images.firstOrNull()?.url,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(9.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.name,
                                color = OnyxWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                track.artists.joinToString(", ") { it.name },
                                color = OnyxDim,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = tr("Играть", "Play"),
                            tint = OnyxFaint,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    scope.launch {
                                        val ok = controller.spotify.playTrack(track.uri)
                                        if (!ok) controller.toast(
                                            tr(
                                                tr("Spotify не разрешил запуск — проверь Premium", "Spotify refused playback — check Premium"),
                                                "Spotify refused playback — check Premium",
                                            ),
                                        )
                                    }
                                },
                        )
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = tr("Удалить", "Remove"),
                            tint = OnyxFaint,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    scope.launch {
                                        val ok = controller.spotify.removeFromPlaylist(playlist.id, track.uri)
                                        controller.toast(
                                            if (ok) tr("Трек удалён", "Track removed")
                                            else tr("Не удалось удалить", "Could not remove"),
                                        )
                                        if (ok) version++
                                    }
                                },
                        )
                    }
                }
                if (detailLoading) {
                    item { Text(tr("Загружаем…", "Loading…"), color = OnyxFaint, fontSize = 11.sp) }
                }
                if (!detailLoading && tracks.isEmpty()) {
                    item {
                        Text(
                            tr("В плейлисте нет треков или нет доступа.", "This playlist is empty or access is missing."),
                            color = OnyxFaint,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

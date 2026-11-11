package app.onyx.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.onyx.core.PrefsData
import app.onyx.media.NowPlaying
import app.onyx.spotify.QueueData
import app.onyx.spotify.TrackUi
import app.onyx.ui.components.BlurredBackdrop
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxWhite
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun QueueScreen(
    controller: AppController,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val media by controller.media.state.collectAsStateWithLifecycle()
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = PrefsData())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val connected = prefs.spotifyRefresh.isNotBlank() || prefs.spotifyAccess.isNotBlank()
    var queue by remember { mutableStateOf<QueueData?>(null) }
    var version by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }

    if (showPlaylists) {
        PlaylistsScreen(
            controller = controller,
            onBack = { showPlaylists = false },
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(media.now.mediaId, media.now.title, media.now.artist, connected, version) {
        if (!connected) {
            queue = null
            return@LaunchedEffect
        }
        loading = true
        queue = runCatching { controller.spotify.queue() }.getOrNull()
        loading = false
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp, end = 18.dp, top = 16.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            tr("Очередь", "Queue"),
                            color = OnyxWhite,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.6).sp,
                        )
                        Text(
                            queue?.deviceName?.takeIf { it.isNotBlank() } ?: "Spotify",
                            color = OnyxFaint,
                            fontSize = 10.sp,
                            letterSpacing = 1.4.sp,
                        )
                    }
                    GlassPill(
                        text = tr("Плейлисты", "Playlists"),
                        selected = false,
                        onClick = { showPlaylists = true },
                    )
                    Spacer(Modifier.width(8.dp))
                    CircleButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = tr("Обновить", "Refresh"),
                        size = 38.dp,
                        iconSize = 17.dp,
                        onClick = { version++ },
                    )
                }
            }

            if (!connected) {
                item {
                    GlassCard {
                        SectionLabel("Spotify")
                        Spacer(Modifier.height(10.dp))
                        Text(
                            tr("Подключи Spotify, чтобы видеть очередь.", "Connect Spotify to see the queue."),
                            color = OnyxDim,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        GlassPill(
                            text = tr("Подключить Spotify", "Connect Spotify"),
                            selected = true,
                            onClick = onOpenSettings,
                        )
                    }
                }
                return@LazyColumn
            }

            item {
                val nowTitle = media.now.title.ifBlank { queue?.current?.title.orEmpty() }
                val nowArtist = media.now.artist.ifBlank { queue?.current?.artist.orEmpty() }
                val nowAlbum = media.now.album.ifBlank { queue?.current?.album.orEmpty() }
                NowCard(
                    title = nowPlayingTitle(nowTitle, nowArtist, nowAlbum),
                    artist = nowPlayingArtist(nowArtist, nowAlbum),
                    art = media.now.art ?: media.now.artUri ?: queue?.current?.art,
                )
            }

            item {
                GlassCard {
                    SectionLabel(tr("Повтор", "Repeat"))
                    Spacer(Modifier.height(10.dp))
                    val state = queue?.repeatState ?: "off"
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RepeatPill(tr("Выкл", "Off"), state == "off", Modifier.weight(1f)) {
                            scope.launch {
                                if (controller.spotify.setRepeat("off")) version++
                                else controller.toast(tr("Spotify не разрешил повтор — проверь Premium", "Spotify refused repeat — check Premium"))
                            }
                        }
                        RepeatPill(tr("Плейлист", "Playlist"), state == "context", Modifier.weight(1f)) {
                            scope.launch {
                                if (controller.spotify.setRepeat("context")) version++
                                else controller.toast(tr("Spotify не разрешил повтор — проверь Premium", "Spotify refused repeat — check Premium"))
                            }
                        }
                        RepeatPill(tr("Трек", "Track"), state == "track", Modifier.weight(1f)) {
                            scope.launch {
                                if (controller.spotify.setRepeat("track")) version++
                                else controller.toast(tr("Spotify не разрешил повтор — проверь Premium", "Spotify refused repeat — check Premium"))
                            }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(tr("Дальше", "Next"), Modifier.weight(1f))
                    Text(
                        text = if (loading) tr("обновляем…", "refreshing…") else "${queue?.next?.size ?: 0} ${tr("треков", "tracks")}",
                        color = OnyxFaint,
                        fontSize = 10.sp,
                    )
                }
            }

            itemsIndexed(queue?.next.orEmpty(), key = { i, t -> "$i-${t.uri}-${t.title}" }) { index, track ->
                QueueRow(
                    index = index + 1,
                    track = track,
                    onPlay = {
                        scope.launch {
                            val ok = controller.spotify.playTrack(track.uri)
                            if (!ok) controller.toast(
                                tr(
                                    tr("Spotify не разрешил запуск — проверь Premium и активное устройство", "Spotify refused playback — check Premium and the active device"),
                                    "Spotify refused playback — check Premium and the active device",
                                ),
                            )
                        }
                    },
                )
            }

            if (queue?.next.isNullOrEmpty() && !loading) {
                item {
                    Text(
                        tr("Очередь пуста. Spotify отдаёт максимум 50 следующих треков.", "Queue is empty. Spotify returns up to 50 upcoming tracks."),
                        color = OnyxFaint,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun RepeatPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    GlassPill(text = label, selected = selected, modifier = modifier, onClick = onClick)
}

@Composable
private fun NowCard(title: String, artist: String, art: Any?) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(OnyxDim.copy(alpha = 0.12f)),
            ) {
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title.ifBlank { tr("Ничего не играет", "Nothing playing") },
                    color = OnyxWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    artist,
                    color = OnyxDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(tr("СЕЙЧАС", "NOW"), color = OnyxFaint, fontSize = 9.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun QueueRow(index: Int, track: TrackUi, onPlay: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = index.toString().padStart(2, '0'),
            color = OnyxFaint,
            fontSize = 10.sp,
            modifier = Modifier.width(26.dp),
        )
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(OnyxDim.copy(alpha = 0.10f)),
        ) {
            if (track.art != null) {
                AsyncImage(
                    model = track.art,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = OnyxWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                color = OnyxDim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = tr("Играть", "Play"),
            tint = OnyxFaint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            fmtQueue(track.durationMs),
            color = OnyxFaint,
            fontSize = 10.sp,
        )
    }
}

private fun fmtQueue(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

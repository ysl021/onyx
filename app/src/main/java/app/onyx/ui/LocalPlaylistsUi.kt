package app.onyx.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.core.PrefsData
import app.onyx.music.LocalPlaylist
import app.onyx.music.LocalTrack
import app.onyx.youtube.YouTubeClient
import app.onyx.youtube.YouTubeStreamCache
import app.onyx.youtube.YtVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.onyx.music.PlaylistStreamTrack
import app.onyx.ui.components.artModel
import app.onyx.music.PlaylistFolder
import app.onyx.ui.components.AsyncArt
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.ReorderableRow
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.components.rememberReorderState
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxBlack
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val detailDate = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

/** mm:ss / h:mm:ss for playlist rows and totals. */
private fun fmtMs(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "5 плейлистов" with the right Russian ending. */
private fun ruCount(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
    return "$count $word"
}

private fun enCount(count: Int, one: String, many: String): String =
    "$count " + if (count == 1) one else many

/** "1 ч 12 мин" style total duration for a playlist header. */
private fun humanDuration(ms: Long): String {
    val totalMin = ms / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> tr("$h ч $m мин", "$h h $m min")
        h > 0 -> tr("$h ч", "$h h")
        else -> tr("$m мин", "$m min")
    }
}

/**
 * "My playlists": user-built playlists with folders, pinned section and a
 * detail screen (cover, description, filters, drag reorder, creation date
 * at the bottom). Rendered by PlaylistsScreen next to the Spotify section.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MyPlaylistsContent(
    controller: AppController,
    onBack: () -> Unit = {},
    embedded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val playlistsState by controller.localPlaylists.state.collectAsStateWithLifecycle()
    var openedId by rememberSaveable { mutableStateOf("") }

    val opened = playlistsState.playlists.firstOrNull { it.id == openedId }
    if (opened != null) {
        LocalPlaylistDetail(
            controller = controller,
            playlistId = opened.id,
            onBack = { openedId = "" },
        )
        return
    }

    var showCreate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var openedFolder by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    val pendingCover = remember { mutableStateOf<String?>(null) }
    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { pendingCover.value = it.toString() }
    }

    Column(modifier.fillMaxSize()) {
        val currentFolder = playlistsState.folders.firstOrNull { it.id == openedFolder }
        val visible = if (openedFolder.isBlank()) {
            playlistsState.playlists
        } else {
            playlistsState.playlists.filter { it.folderId == openedFolder }
        }
        val playlistsLabel = tr(
            ruCount(visible.size, "плейлист", "плейлиста", "плейлистов"),
            enCount(visible.size, "playlist", "playlists"),
        )
        val trackCount = visible.sumOf { it.trackIds.size }
        val tracksLabel = tr(
            ruCount(trackCount, "трек", "трека", "треков"),
            enCount(trackCount, "track", "tracks"),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!embedded) {
                CircleButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = tr("Назад", "Back"),
                    size = 40.dp,
                    iconSize = 18.dp,
                    onClick = { if (openedFolder.isNotBlank()) openedFolder = "" else onBack() },
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    currentFolder?.name ?: tr("Мои плейлисты", "My playlists"),
                    color = OnyxWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (currentFolder == null) "$playlistsLabel · $tracksLabel" else playlistsLabel,
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CircleButton(
                icon = Icons.Rounded.CreateNewFolder,
                contentDescription = tr("Создать папку", "Create folder"),
                size = 38.dp,
                iconSize = 17.dp,
                onClick = { showCreateFolder = true },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassPill(
                text = tr("Новый плейлист", "New playlist"),
                selected = true,
                icon = Icons.Rounded.Add,
                onClick = {
                    pendingCover.value = null
                    showCreate = true
                },
            )
            GlassPill(
                text = tr("Импорт", "Import"),
                selected = false,
                icon = Icons.Rounded.Download,
                onClick = { showImport = true },
            )
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (openedFolder.isBlank()) {
                val folders = playlistsState.folders
                if (folders.isNotEmpty()) {
                    item { SectionLabel(tr("Папки", "Folders")) }
                    items(folders.size) { index ->
                        val folder = folders[index]
                        val count = playlistsState.playlists.count { it.folderId == folder.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .combinedClickable(
                                    onClick = { openedFolder = folder.id },
                                    onLongClick = {
                                        deleteTarget = DeleteTarget.Folder(folder.id, folder.name)
                                    },
                                )
                                .padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncArt(
                                model = playlistsState
                                    .playlists
                                    .firstOrNull { it.folderId == folder.id }
                                    ?.coverPath,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    folder.name,
                                    color = OnyxWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "$count " + tr("плейлистов", "playlists"),
                                    color = OnyxDim,
                                    fontSize = 11.sp,
                                )
                            }
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = tr("Удалить папку", "Delete folder"),
                                tint = OnyxFaint,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { controller.localPlaylists.deleteFolder(folder.id) },
                            )
                        }
                    }
                }

                val pinned = visible.filter { it.pinned }
                if (pinned.isNotEmpty()) {
                    item { SectionLabel(tr("Закреплённые", "Pinned")) }
                    items(pinned.size, key = { pinned[it].id }) { index ->
                        PlaylistRow(
                            controller = controller,
                            playlist = pinned[index],
                            onOpen = { openedId = pinned[index].id },
                            onLongClick = {
                                deleteTarget = DeleteTarget.Playlist(pinned[index].id, pinned[index].name)
                            },
                        )
                    }
                }

                val rest = visible.filterNot { it.pinned }
                if (rest.isNotEmpty()) {
                    if (visible.size > pinned.size) {
                        item { Spacer(Modifier.height(4.dp)) }
                    }
                    items(rest.size, key = { rest[it].id }) { index ->
                        PlaylistRow(
                            controller = controller,
                            playlist = rest[index],
                            onOpen = { openedId = rest[index].id },
                            onLongClick = {
                                deleteTarget = DeleteTarget.Playlist(rest[index].id, rest[index].name)
                            },
                        )
                    }
                }
                if (visible.isEmpty()) {
                    item {
                        Text(
                            tr(
                                "Собери плейлист из треков во вкладке «Музыка» или из папки целиком.",
                                "Build playlists from tracks in the Music tab or from a whole folder.",
                            ),
                            color = OnyxFaint,
                            fontSize = 11.sp,
                        )
                    }
                }
            } else {
                items(visible.size, key = { visible[it].id }) { index ->
                    PlaylistRow(
                        controller = controller,
                        playlist = visible[index],
                        onOpen = { openedId = visible[index].id },
                        onLongClick = {
                            deleteTarget = DeleteTarget.Playlist(visible[index].id, visible[index].name)
                        },
                    )
                }
                if (visible.isEmpty()) {
                    item {
                        Text(
                            tr("В папке пока нет плейлистов.", "No playlists in this folder yet."),
                            color = OnyxFaint,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }

    if (showImport) {
        ImportPlaylistDialog(controller) { showImport = false }
    }

    if (showCreate) {
        PlaylistEditDialog(
            title = tr("Новый плейлист", "New playlist"),
            initialName = "",
            initialDescription = "",
            hasCover = false,
            confirm = tr("Создать", "Create"),
            onPickCover = {
                pendingCover.value = null
                coverLauncher.launch("image/*")
            },
            onDismiss = { showCreate = false },
            onConfirm = { name, description ->
                val cover = pendingCover.value
                controller.localPlaylists.create(name, description) { id ->
                    if (cover != null) {
                        runCatching { controller.localPlaylists.setCover(id, android.net.Uri.parse(cover)) }
                    }
                }
                pendingCover.value = null
                showCreate = false
            },
        )
    }

    if (showCreateFolder) {
        FolderEditDialog(
            title = tr("Новая папка", "New folder"),
            initialName = "",
            confirm = tr("Создать", "Create"),
            onDismiss = { showCreateFolder = false },
            onConfirm = { name ->
                controller.localPlaylists.createFolder(name)
                showCreateFolder = false
            },
        )
    }

    val target = deleteTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(tr("Удалить?", "Delete?"), color = OnyxWhite) },
            text = {
                Text(
                    when (target) {
                        is DeleteTarget.Playlist ->
                            tr(
                                "Плейлист «${target.name}» будет удалён. Треки останутся на телефоне.",
                                "The playlist «${target.name}» will be deleted. Tracks stay on the phone.",
                            )
                        is DeleteTarget.Folder ->
                            tr(
                                "Папка «${target.name}» будет удалена. Плейлисты внутри останутся.",
                                "The folder «${target.name}» will be deleted. Playlists inside stay.",
                            )
                    },
                    color = OnyxDim,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (target) {
                            is DeleteTarget.Playlist -> controller.localPlaylists.delete(target.id)
                            is DeleteTarget.Folder -> controller.localPlaylists.deleteFolder(target.id)
                        }
                        deleteTarget = null
                    },
                ) {
                    Text(tr("Удалить", "Delete"), color = OnyxWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(tr("Отмена", "Cancel"), color = OnyxFaint)
                }
            },
            containerColor = OnyxBlack,
        )
    }
}

private sealed interface DeleteTarget {
    data class Playlist(val id: String, val name: String) : DeleteTarget
    data class Folder(val id: String, val name: String) : DeleteTarget
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    controller: AppController,
    playlist: LocalPlaylist,
    onOpen: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncArt(
            model = playlist.coverPath,
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
            val desc = playlist.description.ifBlank {
                playlist.trackIds.size.toString() + " " + tr("треков", "tracks")
            }
            Text(
                desc,
                color = OnyxDim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Rounded.PushPin,
            contentDescription = tr("Закрепить", "Pin"),
            tint = if (playlist.pinned) OnyxWhite else OnyxFaint,
            modifier = Modifier
                .size(16.dp)
                .clickable {
                    controller.localPlaylists.setPinned(playlist.id, !playlist.pinned)
                },
        )
    }
}

@Composable
private fun LocalPlaylistDetail(
    controller: AppController,
    playlistId: String,
    onBack: () -> Unit,
) {
    val playlistsState by controller.localPlaylists.state.collectAsStateWithLifecycle()
    val library by controller.localLibrary.state.collectAsStateWithLifecycle()
    val playlist = playlistsState.playlists.firstOrNull { it.id == playlistId } ?: run {
        onBack()
        return
    }

    val byId = remember(library.tracks) { library.tracks.associateBy { it.id } }
    val streamById = remember(playlist.streamTracks) {
        playlist.streamTracks.associateBy { it.id }
    }
    val fullTracks = remember(playlist.trackIds, byId, streamById) {
        playlist.trackIds.mapNotNull { id ->
            byId[id] ?: streamById[id]?.toLocalTrack()
        }
    }

    var query by remember { mutableStateOf("") }
    var sortKey by rememberSaveable { mutableStateOf("added") }
    var sortAsc by rememberSaveable { mutableStateOf(true) }
    var showEdit by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = PrefsData())
    val openAlbum = LocalOpenAlbum.current

    // Warm the first streams while the playlist is open.
    LaunchedEffect(playlist.id) {
        playlist.streamTracks.take(3).forEach { YouTubeStreamCache.prefetch(it.videoId) }
    }

    fun downloadStreamTrack(track: LocalTrack) {
        if (!track.id.startsWith("yt:")) return
        val folder = prefs.youtubeFolderUri
        if (folder.isBlank()) {
            controller.toast(tr("Сначала выбери папку для сохранения", "Pick a folder to save into first"))
            return
        }
        val videoId = track.id.removePrefix("yt:")
        scope.launch(Dispatchers.IO) {
            val video = runCatching {
                YouTubeClient.resolve("https://www.youtube.com/watch?v=$videoId")
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (video != null) {
                    controller.startPlaylistDownload(
                        listOf(video),
                        folder,
                        playlist.id,
                        playlist.name,
                    )
                } else {
                    controller.toast(tr("Не удалось получить трек", "Could not fetch the track"))
                }
            }
        }
    }

    fun downloadAll() {
        val folder = prefs.youtubeFolderUri
        if (folder.isBlank()) {
            controller.toast(tr("Сначала выбери папку для сохранения", "Pick a folder to save into first"))
            return
        }
        val entries = playlist.streamTracks.distinctBy { it.videoId }
        if (entries.isEmpty()) return
        controller.toast(
            tr(
                "Скачиваю ${entries.size} треков",
                "Downloading ${entries.size} tracks",
            ),
        )
        scope.launch(Dispatchers.IO) {
            val videos = mutableListOf<YtVideo>()
            val failed = mutableListOf<String>()
            entries.chunked(3).forEach { chunk ->
                val resolved = coroutineScope {
                    chunk.map { entry ->
                        async {
                            entry to runCatching {
                                YouTubeClient.resolve(
                                    "https://www.youtube.com/watch?v=${entry.videoId}",
                                )
                            }.getOrNull()
                        }
                    }.awaitAll()
                }
                resolved.forEach { (entry, video) ->
                    if (video != null) videos += video else failed += entry.title
                }
            }
            withContext(Dispatchers.Main) {
                if (videos.isNotEmpty()) {
                    controller.startPlaylistDownload(
                        videos,
                        folder,
                        playlist.id,
                        playlist.name,
                    )
                }
                if (failed.isNotEmpty()) {
                    controller.toast(
                        tr(
                            "Не удалось получить: ${failed.size}",
                            "Could not fetch: ${failed.size}",
                        ),
                    )
                }
                if (videos.isEmpty()) {
                    controller.toast(tr("Не удалось получить треки", "Could not fetch the tracks"))
                }
            }
        }
    }

    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { controller.localPlaylists.setCover(playlist.id, it) }
    }

    // Filter + sort over the raw trackIds positions; each entry keeps its
    // original index so reordering maps back to the playlist order.
    val sorted: List<Pair<LocalTrack, Int>> = remember(fullTracks, query, sortKey, sortAsc) {
        val q = query.trim().lowercase()
        val indexed = fullTracks.mapIndexed { trackIdsIndex, track -> track to trackIdsIndex }
        val filtered = if (q.isEmpty()) {
            indexed
        } else {
            indexed.filter { (t, _) ->
                t.title.lowercase().contains(q) || t.artist.lowercase().contains(q) ||
                    t.album.lowercase().contains(q)
            }
        }
        when (sortKey) {
            "title" -> if (sortAsc) {
                filtered.sortedBy { (t, _) -> t.title.lowercase() }
            } else {
                filtered.sortedByDescending { (t, _) -> t.title.lowercase() }
            }
            "duration" -> if (sortAsc) {
                filtered.sortedBy { (t, _) -> t.durationMs }
            } else {
                filtered.sortedByDescending { (t, _) -> t.durationMs }
            }
            "artist" -> if (sortAsc) {
                filtered.sortedBy { (t, _) -> t.artist.lowercase() }
            } else {
                filtered.sortedByDescending { (t, _) -> t.artist.lowercase() }
            }
            else -> filtered
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
                onClick = onBack,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    color = OnyxWhite,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val total = fullTracks.sumOf { it.durationMs }
                val countText = tr(
                    ruCount(fullTracks.size, "трек", "трека", "треков"),
                    enCount(fullTracks.size, "track", "tracks"),
                )
                val totalLabel = if (total > 0) {
                    "$countText · ${humanDuration(total)}"
                } else {
                    countText
                }
                Text(totalLabel, color = OnyxFaint, fontSize = 10.sp)
            }
            if (playlist.streamTracks.isNotEmpty()) {
                CircleButton(
                    icon = Icons.Rounded.Download,
                    contentDescription = tr("Скачать плейлист", "Download playlist"),
                    size = 38.dp,
                    iconSize = 16.dp,
                    onClick = { downloadAll() },
                )
                Spacer(Modifier.width(8.dp))
            }
            CircleButton(
                icon = Icons.Rounded.PushPin,
                contentDescription = tr("Закрепить", "Pin"),
                size = 38.dp,
                iconSize = 16.dp,
                onClick = { controller.localPlaylists.setPinned(playlist.id, !playlist.pinned) },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(14.dp))
            AsyncArt(
                model = playlist.coverPath,
                modifier = Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )
            if (playlist.description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    playlist.description,
                    color = OnyxDim,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row {
                GlassPill(
                    text = tr("Играть", "Play"),
                    selected = true,
                    icon = Icons.Rounded.PlayArrow,
                    enabled = fullTracks.isNotEmpty(),
                    onClick = {
                        fullTracks.firstOrNull()?.let { controller.localPlayer.play(it, fullTracks) }
                    },
                )
                Spacer(Modifier.width(8.dp))
                GlassPill(
                    text = tr("Собрать", "Add tracks"),
                    selected = false,
                    onClick = { showPicker = true },
                )
                Spacer(Modifier.width(8.dp))
                GlassPill(
                    text = tr("Правка", "Edit"),
                    selected = false,
                    onClick = { showEdit = true },
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassPill(
                text = tr("Как добавлял", "Added order"),
                selected = sortKey == "added",
                onClick = { sortKey = "added" },
            )
            GlassPill(
                text = if (sortKey == "title") {
                    tr(if (sortAsc) "Название A-Z" else "Название Z-A", if (sortAsc) "Title A-Z" else "Title Z-A")
                } else {
                    tr("Название", "Title")
                },
                selected = sortKey == "title",
                onClick = {
                    if (sortKey == "title") sortAsc = !sortAsc
                    sortKey = "title"
                },
            )
            GlassPill(
                text = if (sortKey == "duration") {
                    tr(if (sortAsc) "Длина ↑" else "Длина ↓", if (sortAsc) "Length ↑" else "Length ↓")
                } else {
                    tr("Длительность", "Duration")
                },
                selected = sortKey == "duration",
                onClick = {
                    if (sortKey == "duration") sortAsc = !sortAsc
                    sortKey = "duration"
                },
            )
            GlassPill(
                text = if (sortKey == "artist") {
                    tr(if (sortAsc) "Артист A-Z" else "Артист Z-A", if (sortAsc) "Artist A-Z" else "Artist Z-A")
                } else {
                    tr("Исполнитель", "Artist")
                },
                selected = sortKey == "artist",
                onClick = {
                    if (sortKey == "artist") sortAsc = !sortAsc
                    sortKey = "artist"
                },
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            singleLine = true,
            placeholder = { Text(tr("Фильтр треков", "Filter tracks"), color = OnyxFaint, fontSize = 12.sp) },
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

        val rowHeight = 50.dp
        val rowHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { rowHeight.toPx() }
        // Drag reordering only makes sense in the natural "added" order.
        val canReorder = query.isBlank() && sortKey == "added"
        val reorder = rememberReorderState(sorted.size, rowHeightPx)

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sorted.size, key = { sorted[it].second }) { index ->
                val (track, trackIdsIndex) = sorted[index]
                ReorderableRow(
                    index = index,
                    state = reorder,
                    rowHeight = rowHeight,
                    enabled = canReorder,
                    onMove = { from, to ->
                        if (canReorder) {
                            controller.localPlaylists.moveTrack(
                                playlist.id,
                                sorted[from].second,
                                sorted[to].second,
                            )
                        }
                    },
                    onTap = { controller.localPlayer.play(track, fullTracks) },
                ) {
                    PlaylistTrackRow(
                        track = track,
                        onDownload = { downloadStreamTrack(track) },
                        onOpenAlbum = { openAlbum(albumSelectionOf(track)) },
                        onRemove = {
                            controller.localPlaylists.removeTrack(playlist.id, track.id)
                        },
                    )
                }
            }
            if (sorted.isEmpty() && fullTracks.isEmpty()) {
                item {
                    Text(
                        tr("Пусто — нажми «Собрать», чтобы добавить треки.", "Empty — tap Add tracks to fill it."),
                        color = OnyxFaint,
                        fontSize = 11.sp,
                    )
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "${tr("Создан", "Created")} ${detailDate.format(Date(playlist.createdAt))}",
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    letterSpacing = 0.6.sp,
                )
            }
        }
    }

    if (showEdit) {
        PlaylistEditDialog(
            title = tr("Правка плейлиста", "Edit playlist"),
            initialName = playlist.name,
            initialDescription = playlist.description,
            hasCover = playlist.coverPath != null,
            confirm = tr("Сохранить", "Save"),
            onPickCover = { coverLauncher.launch("image/*") },
            onDismiss = { showEdit = false },
            onConfirm = { name, description ->
                controller.localPlaylists.rename(playlist.id, name)
                controller.localPlaylists.setDescription(playlist.id, description)
                showEdit = false
            },
            onDelete = {
                controller.localPlaylists.delete(playlist.id)
                showEdit = false
                onBack()
            },
        )
    }

    if (showPicker) {
        TrackPickerDialog(
            controller = controller,
            onDismiss = { showPicker = false },
            onConfirm = { ids ->
                controller.localPlaylists.addTracks(playlist.id, ids)
                showPicker = false
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistTrackRow(
    track: LocalTrack,
    onDownload: () -> Unit,
    onOpenAlbum: () -> Unit,
    onRemove: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenAlbum()
                    },
                ),
        ) {
            AsyncArt(
                model = artModel(track.artPath),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = OnyxWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
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
        Text(text = fmtMs(track.durationMs), color = OnyxFaint, fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        if (track.id.startsWith("yt:")) {
            IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = tr("Скачать трек", "Download track"),
                    tint = OnyxFaint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Icon(
            Icons.Rounded.Delete,
            contentDescription = tr("Удалить из плейлиста", "Remove from playlist"),
            tint = OnyxFaint,
            modifier = Modifier
                .size(16.dp)
                .clickable { onRemove() },
        )
    }
}

/** Search + checkbox picker over the whole local library. */
@Composable
fun TrackPickerDialog(
    controller: AppController,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val library by controller.localLibrary.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val filtered = remember(library.tracks, query) {
        if (query.isBlank()) library.tracks
        else {
            val q = query.trim().lowercase()
            library.tracks.filter {
                it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Собрать плейлист", "Add tracks"), color = OnyxWhite, fontSize = 16.sp) },
        text = {
            Column {
                if (library.folders.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        library.folders.forEach { folder ->
                            GlassPill(
                                text = tr("Всё из", "All from") + " «${folder.name}»",
                                selected = false,
                                onClick = {
                                    selected += library.tracks
                                        .filter { it.folderUri == folder.uri }
                                        .map { it.id }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(tr("Поиск", "Search"), color = OnyxFaint, fontSize = 12.sp)
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
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filtered.size, key = { filtered[it].id }) { index ->
                        val track = filtered[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (track.id in selected) {
                                        selected - track.id
                                    } else {
                                        selected + track.id
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        if (track.id in selected) OnyxWhite else OnyxWhite.copy(alpha = 0.08f),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (track.id in selected) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = Color(0xFF08080A),
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    color = OnyxWhite,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    track.artist.ifBlank { track.album },
                                    color = OnyxFaint,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text(tr("Ничего не найдено.", "Nothing found."), color = OnyxFaint, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(selected.toList()) },
            ) {
                Text(
                    tr("Добавить", "Add") + " (${selected.size})",
                    color = if (selected.isEmpty()) OnyxFaint else OnyxWhite,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel"), color = OnyxWhite) }
        },
    )
}

@Composable
private fun PlaylistEditDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    hasCover: Boolean,
    confirm: String,
    onPickCover: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = OnyxWhite, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(tr("Название", "Name"), color = OnyxFaint, fontSize = 12.sp)
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = OnyxWhite,
                        fontSize = 13.sp,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OnyxLine,
                        unfocusedBorderColor = OnyxLine,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = OnyxWhite,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(tr("Описание", "Description"), color = OnyxFaint, fontSize = 12.sp)
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = OnyxWhite,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OnyxLine,
                        unfocusedBorderColor = OnyxLine,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = OnyxWhite,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                GlassPill(
                    text = when {
                        hasCover -> tr("Заменить фото", "Replace photo")
                        else -> tr("Фото обложки", "Cover photo")
                    },
                    selected = false,
                    icon = Icons.Rounded.PhotoCamera,
                    onClick = onPickCover,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), description.trim()) },
            ) {
                Text(confirm, color = if (name.isBlank()) OnyxFaint else OnyxWhite)
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(tr("Удалить", "Delete"), color = OnyxWhite)
                    }
                }
                TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel"), color = OnyxWhite) }
            }
        },
    )
}

@Composable
private fun FolderEditDialog(
    title: String,
    initialName: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = OnyxWhite, fontSize = 16.sp) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(tr("Название папки", "Folder name"), color = OnyxFaint, fontSize = 12.sp) },
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
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            ) {
                Text(confirm, color = if (name.isBlank()) OnyxFaint else OnyxWhite)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel"), color = OnyxWhite) }
        },
    )
}

private fun PlaylistStreamTrack.toLocalTrack(): LocalTrack = LocalTrack(
    id = id,
    uri = "yt://$videoId",
    title = title,
    artist = artist,
    album = "YouTube",
    durationMs = durationMs,
    artPath = thumbnailUrl,
    folderName = "YouTube",
)

@Composable
private fun ImportPlaylistDialog(controller: AppController, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Импорт плейлиста", "Import playlist"), color = OnyxWhite) },
        text = {
            Column {
                Text(
                    tr(
                        "Вставь ссылку Spotify, YouTube, Deezer или Apple Music. Можно вставить M3U/CSV.",
                        "Paste a Spotify, YouTube, Deezer or Apple Music link, or M3U/CSV text.",
                    ),
                    color = OnyxDim,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text(tr("Ссылка на плейлист", "Playlist link"), color = OnyxFaint) },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OnyxLine,
                        unfocusedBorderColor = OnyxLine,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = OnyxWhite,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (url.isNotBlank()) {
                        controller.importPlaylist(url.trim())
                        onDismiss()
                    }
                },
                enabled = url.isNotBlank(),
            ) {
                Text(tr("Импортировать", "Import"), color = OnyxWhite)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Отмена", "Cancel"), color = OnyxFaint)
            }
        },
        containerColor = OnyxBlack,
    )
}

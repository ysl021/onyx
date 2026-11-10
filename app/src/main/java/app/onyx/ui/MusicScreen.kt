package app.onyx.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.core.LikedArtist
import app.onyx.core.LikedTrack
import app.onyx.core.PrefsData
import app.onyx.music.LocalTrack
import app.onyx.music.TagWriter
import app.onyx.ui.components.AsyncArt
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.artModel
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.GlassSurface
import app.onyx.ui.components.OnyxSwitch
import app.onyx.ui.components.SectionLabel
import dev.chrisbanes.haze.HazeState
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxBlack
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxSurface
import app.onyx.ui.theme.OnyxWhite
import app.onyx.youtube.YtSearchResult
import app.onyx.youtube.YouTubeMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class MusicView { TRACKS, FAVORITES }

@Composable
fun MusicScreen(
    controller: AppController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by controller.localLibrary.state.collectAsStateWithLifecycle()
    val playback by controller.localPlayer.state.collectAsStateWithLifecycle()
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = PrefsData())
    var view by remember { mutableStateOf(MusicView.TRACKS) }
    var query by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var editingTrack by remember { mutableStateOf<LocalTrack?>(null) }
    // Multi-select: long-press a track then drag across rows to select several.
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val rowRects = remember { mutableStateMapOf<String, Rect>() }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val folders = library.folders
    val filtered = remember(library.tracks, query, selectedFolder) {
        val base = library.tracks.filter { track ->
            selectedFolder == null || track.folderUri == selectedFolder
        }
        if (query.isBlank()) base
        else base.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true)
        }
    }
    androidx.compose.runtime.LaunchedEffect(folders) {
        if (selectedFolder != null && folders.none { it.uri == selectedFolder }) selectedFolder = null
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
                .getOrNull()
                ?: uri.lastPathSegment
                ?: tr("Папка", "Folder")
            controller.localLibrary.addFolder(uri, name)
        }
    }

    val deleteConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        // The user either confirmed the system delete dialog or cancelled it;
        // rescanning is safe in both cases and drops the removed files.
        controller.localLibrary.rescan()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        controller.localLibrary.deleteConsent = { sender ->
            deleteConsent.launch(
                androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
            )
        }
        onDispose { controller.localLibrary.deleteConsent = null }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Музыка", "Music"),
                    color = OnyxWhite,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.6).sp,
                )
                Text(
                    if (selectedFolder == null) {
                        when (folders.size) {
                            0 -> tr("Локальные файлы", "Local files")
                            1 -> folders.first().name
                            else -> "${folders.size} ${tr("папки", "folders")}"
                        }
                    } else {
                        folders.firstOrNull { it.uri == selectedFolder }?.name ?: tr("Папка", "Folder")
                    },
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CircleButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = tr("Пересканировать", "Rescan"),
                size = 38.dp,
                iconSize = 17.dp,
                enabled = folders.isNotEmpty() && !library.scanning,
                onClick = { controller.localLibrary.rescan() },
            )
        }

        if (folders.isEmpty()) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
            ) {
                SectionLabel(tr("Папки", "Folders"))
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Добавь одну или несколько папок с музыкой. Доступ будет только к ним, без разрешения на всё хранилище.", "Add one or more music folders. Access is limited to them."),
                    color = OnyxDim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                GlassPill(
                    text = tr("Добавить папку", "Add folder"),
                    selected = true,
                    icon = Icons.Rounded.FolderOpen,
                    onClick = { picker.launch(null) },
                )
            }
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassPill(
                text = tr("Все", "All"),
                selected = selectedFolder == null,
                onClick = { selectedFolder = null },
            )
            folders.forEach { folder ->
                GlassPill(
                    text = folder.name.ifBlank { tr("Папка", "Folder") },
                    selected = selectedFolder == folder.uri,
                    onClick = { selectedFolder = folder.uri },
                )
            }
            GlassPill(
                text = tr("+ Добавить", "+ Add"),
                selected = false,
                onClick = { picker.launch(null) },
            )
        }

        if (library.scanning) {
            Text(
                tr("Сканируем", "Scanning") + "… ${library.progress}%",
                color = OnyxFaint,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            )
        } else if (library.error != null) {
            Text(
                library.error.orEmpty(),
                color = OnyxFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text(tr("Поиск", "Search"), color = OnyxFaint, fontSize = 12.sp) },
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

        if (selectMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("Выбрано: ${selectedIds.size}", "Selected: ${selectedIds.size}"),
                    color = OnyxWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                GlassPill(
                    text = tr("В плейлист", "To playlist"),
                    selected = true,
                    enabled = selectedIds.isNotEmpty(),
                    onClick = { showAddToPlaylist = true },
                )
                Spacer(Modifier.width(8.dp))
                GlassPill(
                    text = tr("Удалить", "Delete"),
                    selected = false,
                    enabled = selectedIds.isNotEmpty(),
                    onClick = { showDeleteConfirm = true },
                )
                Spacer(Modifier.width(8.dp))
                GlassPill(
                    text = tr("Готово", "Done"),
                    selected = false,
                    onClick = {
                        selectMode = false
                        selectedIds = emptySet()
                    },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassPill(
                text = tr("Треки", "Tracks"),
                selected = view == MusicView.TRACKS,
                onClick = { view = MusicView.TRACKS },
            )
            GlassPill(
                text = tr("Любимые", "Liked"),
                selected = view == MusicView.FAVORITES,
                onClick = { view = MusicView.FAVORITES },
            )
        }

        if (view == MusicView.TRACKS) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.id }) { track ->
                val isSelected = track.id in selectedIds
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { rowRects[track.id] = it.boundsInRoot() }
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) OnyxWhite.copy(alpha = 0.10f) else Color.Transparent,
                        )
                        .pointerInput(filtered) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    selectMode = true
                                    selectedIds = setOf(track.id)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    if (!selectMode) return@detectDragGesturesAfterLongPress
                                    val bounds = rowRects[track.id]
                                        ?: return@detectDragGesturesAfterLongPress
                                    val globalY = bounds.top + change.position.y
                                    val hit = filtered.mapNotNull { t ->
                                        val r = rowRects[t.id] ?: return@mapNotNull null
                                        if (globalY >= r.top && globalY <= r.bottom) t.id else null
                                    }
                                    if (hit.isNotEmpty()) selectedIds = selectedIds.union(hit)
                                },
                                onDragEnd = {},
                                onDragCancel = {},
                            )
                        },
                ) {
                    TrackRow(
                        track = track,
                        active = playback.current?.id == track.id,
                        playing = playback.current?.id == track.id && playback.isPlaying,
                        selectMode = selectMode,
                        selected = isSelected,
                        onClick = {
                            if (selectMode) {
                                selectedIds =
                                    if (isSelected) selectedIds - track.id else selectedIds + track.id
                            } else {
                                controller.localPlayer.play(track, filtered)
                            }
                        },
                        onEdit = { editingTrack = track },
                        liked = prefs.likedTracks.any { it.id == track.id },
                        onLike = {
                            scope.launch {
                                controller.prefs.toggleLikedTrack(track.toLiked())
                            }
                        },
                    )
                }
            }
            if (!library.scanning && filtered.isEmpty()) {
                item {
                    Text(
                        if (query.isBlank()) tr("В папке пока нет треков.", "No tracks in this folder.")
                        else tr("Ничего не найдено.", "Nothing found."),
                        color = OnyxFaint,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                GlassPill(
                    text = tr("Добавить папку", "Add folder"),
                    selected = false,
                    onClick = { picker.launch(null) },
                )
            }
        }
        } else {
            FavoritesView(
                artists = prefs.likedArtists,
                tracks = prefs.likedTracks,
                onPlayArtist = { artist ->
                    scope.launch(Dispatchers.IO) {
                        val found = YouTubeMusic.searchArtist(artist.name)
                        val page = found?.let { YouTubeMusic.artistPage(it) }
                        if (page != null && page.songs.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                controller.youtubeRadio.playQueue(page.songs, artist.name)
                            }
                        }
                    }
                },
                onPlayTrack = { liked ->
                    if (liked.isYouTube) {
                        val all = prefs.likedTracks.filter { it.videoId != null }
                        val ordered = all.dropWhile { it.id != liked.id } +
                            all.takeWhile { it.id != liked.id }
                        controller.youtubeRadio.playQueue(
                            ordered.mapNotNull { it.toSearchResult() },
                            liked.artist,
                        )
                    } else {
                        library.tracks.firstOrNull { it.id == liked.id }?.let { track ->
                            controller.localPlayer.play(track, library.tracks)
                        }
                    }
                },
                onRemoveArtist = { artist ->
                    scope.launch {
                        controller.prefs.toggleLikedArtist(LikedArtist(artist.name))
                    }
                },
                onRemoveTrack = { liked ->
                    scope.launch { controller.prefs.removeLikedTrack(liked.id) }
                },
                modifier = Modifier.weight(1f),
            )
        }

        editingTrack?.let { track ->
            TrackEditDialog(
                track = track,
                onDismiss = { editingTrack = null },
                onSave = { title, artist, album, artUri, writeTags ->
                    controller.localLibrary.updateTrack(track.id, title, artist, album, artUri)
                    editingTrack = null
                    if (writeTags) {
                        if (playback.current?.id == track.id) {
                            controller.localPlayer.stop()
                        }
                        controller.appScope.launch {
                            val name = runCatching {
                                DocumentFile.fromSingleUri(context, android.net.Uri.parse(track.uri))?.name
                            }.getOrNull() ?: track.uri.substringAfterLast('/')
                            val result = TagWriter.write(
                                context = context,
                                uri = android.net.Uri.parse(track.uri),
                                displayName = name,
                                title = title,
                                artist = artist,
                                album = album,
                                artUri = artUri,
                            )
                            controller.toast(
                                if (result.ok) {
                                    tr("Теги записаны в файл", "Tags written to the file")
                                } else {
                                    tr(
                                        "Сохранено в ONYX, файл не изменён",
                                        "Saved in ONYX, file unchanged",
                                    )
                                },
                            )
                        }
                    }
                },
                onReset = {
                    controller.localLibrary.resetTrack(track.id)
                    editingTrack = null
                },
            )
        }

        if (showAddToPlaylist) {
            AddToPlaylistDialog(
                controller = controller,
                trackIds = selectedIds.toList(),
                onDismiss = {
                    showAddToPlaylist = false
                    selectMode = false
                    selectedIds = emptySet()
                },
            )
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(tr("Удалить треки?", "Delete tracks?"), color = OnyxWhite) },
                text = {
                    Text(
                        tr(
                            "Будет удалено файлов: ${selectedIds.size}. Они исчезнут с телефона.",
                            "Files to delete: ${selectedIds.size}. They will be removed from the phone.",
                        ),
                        color = OnyxDim,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            controller.localLibrary.deleteTracks(selectedIds)
                            showDeleteConfirm = false
                            selectMode = false
                            selectedIds = emptySet()
                        },
                    ) {
                        Text(tr("Удалить", "Delete"), color = OnyxWhite)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(tr("Отмена", "Cancel"), color = OnyxFaint)
                    }
                },
                containerColor = OnyxBlack,
            )
        }
    }
}

@Composable
private fun FavoritesView(
    artists: List<LikedArtist>,
    tracks: List<LikedTrack>,
    onPlayArtist: (LikedArtist) -> Unit,
    onPlayTrack: (LikedTrack) -> Unit,
    onRemoveArtist: (LikedArtist) -> Unit,
    onRemoveTrack: (LikedTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 132.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            SectionLabel(
                tr("Любимые артисты · ${artists.size}", "Liked artists · ${artists.size}"),
            )
        }
        items(artists, key = { "artist:${it.name}" }) { liked ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onPlayArtist(liked) }
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncArt(
                    model = artModel(liked.thumbnailUrl),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        liked.name,
                        color = OnyxWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        tr("Нажми, чтобы слушать", "Tap to listen"),
                        color = OnyxFaint,
                        fontSize = 10.sp,
                    )
                }
                IconButton(
                    onClick = { onRemoveArtist(liked) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = tr("Убрать из любимых", "Unlike"),
                        tint = OnyxWhite,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
        if (artists.isEmpty()) {
            item {
                Text(
                    tr(
                        "Лайкни артиста на его странице в YouTube — он появится здесь.",
                        "Like an artist on their YouTube page and they will appear here.",
                    ),
                    color = OnyxFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        item {
            Spacer(Modifier.height(10.dp))
            SectionLabel(
                tr("Любимые треки · ${tracks.size}", "Liked tracks · ${tracks.size}"),
            )
        }
        items(tracks, key = { "track:${it.id}" }) { liked ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onPlayTrack(liked) }
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncArt(
                    model = artModel(liked.thumbnailUrl),
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        liked.title,
                        color = OnyxWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        liked.artist.ifBlank { liked.album },
                        color = OnyxDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (liked.durationMs > 0) fmtLocal(liked.durationMs) else "",
                    color = OnyxFaint,
                    fontSize = 10.sp,
                )
                IconButton(
                    onClick = { onRemoveTrack(liked) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = tr("Убрать из любимых", "Unlike"),
                        tint = OnyxWhite,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
        if (tracks.isEmpty()) {
            item {
                Text(
                    tr(
                        "Лайкни трек в поиске YouTube — он появится здесь.",
                        "Like a track in YouTube search and it will appear here.",
                    ),
                    color = OnyxFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

private fun LocalTrack.toLiked(): LikedTrack = LikedTrack(
    id = id,
    uri = uri,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    thumbnailUrl = artPath,
)

private fun LikedTrack.toSearchResult(): YtSearchResult? {
    val video = videoId ?: return null
    return YtSearchResult(
        id = video,
        url = "https://www.youtube.com/watch?v=$video",
        title = title,
        channel = artist,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
    )
}

@Composable
private fun TrackRow(
    track: LocalTrack,
    active: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    liked: Boolean = false,
    onLike: () -> Unit = {},
    selectMode: Boolean = false,
    selected: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) OnyxWhite.copy(alpha = 0.07f) else Color.Transparent)
            .clickable { onClick() }
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (selected) OnyxWhite else OnyxWhite.copy(alpha = 0.08f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color(0xFF08080A),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        AsyncArt(
            model = track.artPath?.let { File(it) },
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (active) OnyxWhite else OnyxWhite.copy(alpha = 0.9f),
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist.ifBlank { track.album },
                color = OnyxDim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            fmtLocal(track.durationMs),
            color = OnyxFaint,
            fontSize = 10.sp,
        )
        if (active) {
            Spacer(Modifier.width(8.dp))
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = OnyxWhite,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(
            onClick = onLike,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = tr("В любимые", "Like"),
                tint = if (liked) OnyxWhite else OnyxFaint,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(
            onClick = onEdit,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Rounded.Edit,
                contentDescription = tr("Изменить трек", "Edit track"),
                tint = if (track.custom) OnyxWhite.copy(alpha = 0.85f) else OnyxFaint,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * Mini-player for local playback, rendered by OnyxApp above the dock and outside
 * the haze source so the panel stays sharp while everything behind it blurs.
 */
@Composable
internal fun MusicMiniPlayer(
    controller: AppController,
    track: LocalTrack,
    playing: Boolean,
    hazeState: HazeState? = null,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(22.dp),
        strong = true,
        art = track.artPath?.let { File(it) },
        artBlur = 28.dp,
        hazeState = hazeState,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncArt(
                model = track.artPath?.let { File(it) },
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(9.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    color = OnyxWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist,
                    color = OnyxDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CircleButton(
                icon = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = tr("Играть/пауза", "Play/pause"),
                size = 38.dp,
                iconSize = 18.dp,
                filled = true,
                onClick = { controller.localPlayer.toggle() },
            )
            Spacer(Modifier.width(8.dp))
            CircleButton(
                icon = Icons.Rounded.SkipNext,
                contentDescription = tr("Следующий", "Next"),
                size = 34.dp,
                iconSize = 16.dp,
                onClick = { controller.localPlayer.next() },
            )
        }
    }
}

@Composable
private fun TrackEditDialog(
    track: LocalTrack,
    onDismiss: () -> Unit,
    onSave: (String, String, String, android.net.Uri?, Boolean) -> Unit,
    onReset: () -> Unit,
) {
    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artist) }
    var album by remember(track.id) { mutableStateOf(track.album) }
    var pickedArt by remember(track.id) { mutableStateOf<android.net.Uri?>(null) }
    var writeTags by remember(track.id) { mutableStateOf(true) }
    val artPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pickedArt = uri }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OnyxSurface,
        title = {
            Text(
                tr("Изменить трек", "Edit track"),
                color = OnyxWhite,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(OnyxWhite.copy(alpha = 0.06f)),
                    ) {
                        AsyncArt(
                            model = pickedArt ?: track.artPath?.let { File(it) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    GlassPill(
                        text = tr("Обложка", "Cover"),
                        selected = pickedArt != null,
                        onClick = { artPicker.launch(arrayOf("image/*")) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                EditField(tr("Название", "Title"), title) { title = it }
                EditField(tr("Исполнитель", "Artist"), artist) { artist = it }
                EditField(tr("Альбом", "Album"), album) { album = it }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            tr("Записать в файл", "Write to the file"),
                            color = OnyxWhite,
                            fontSize = 13.sp,
                        )
                        Text(
                            tr(
                                "Изменит теги и обложку самого аудиофайла",
                                "Changes tags and cover inside the audio file",
                            ),
                            color = OnyxFaint,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    }
                    OnyxSwitch(checked = writeTags, onCheckedChange = { writeTags = it })
                }
                if (track.custom) {
                    TextButton(onClick = onReset) {
                        Text(tr("Сбросить изменения", "Reset changes"), color = OnyxDim, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, artist, album, pickedArt, writeTags) },
                enabled = title.isNotBlank(),
            ) {
                Text(tr("Сохранить", "Save"), color = OnyxWhite, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Отмена", "Cancel"), color = OnyxDim)
            }
        },
    )
}

@Composable
private fun EditField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = OnyxFaint, fontSize = 11.sp) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        textStyle = androidx.compose.ui.text.TextStyle(color = OnyxWhite, fontSize = 13.sp),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = OnyxLine,
            unfocusedBorderColor = OnyxLine,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            cursorColor = OnyxWhite,
            focusedLabelColor = OnyxFaint,
            unfocusedLabelColor = OnyxFaint,
        ),
    )
}

private fun fmtLocal(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Picker that adds the given track ids to an existing local playlist or creates
 * a new one. Used by the Music tab multi-select flow.
 */
@Composable
private fun AddToPlaylistDialog(
    controller: AppController,
    trackIds: List<String>,
    onDismiss: () -> Unit,
) {
    val playlistsState by controller.localPlaylists.state.collectAsStateWithLifecycle()
    var newMode by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                tr("Добавить в плейлист", "Add to playlist") + " (${trackIds.size})",
                color = OnyxWhite,
                fontSize = 16.sp,
            )
        },
        text = {
            Column {
                if (playlistsState.playlists.isEmpty() && !newMode) {
                    Text(
                        tr("Плейлистов пока нет — создай первый.", "No playlists yet — create the first one."),
                        color = OnyxFaint,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            playlistsState.playlists.size,
                            key = { playlistsState.playlists[it].id },
                        ) { index ->
                            val playlist = playlistsState.playlists[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        controller.localPlaylists.addTracks(playlist.id, trackIds)
                                        controller.toast(
                                            tr("Добавлено в «${playlist.name}»", "Added to «${playlist.name}»"),
                                        )
                                        onDismiss()
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AsyncArt(
                                    model = playlist.coverPath,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(9.dp)),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        playlist.name,
                                        color = OnyxWhite,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${playlist.trackIds.size} " + tr("треков", "tracks"),
                                        color = OnyxFaint,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (newMode) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = {
                            Text(tr("Название плейлиста", "Playlist name"), color = OnyxFaint, fontSize = 12.sp)
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
                    GlassPill(
                        text = tr("Создать и добавить", "Create and add"),
                        selected = true,
                        enabled = newName.isNotBlank(),
                        onClick = {
                            controller.localPlaylists.create(newName.trim(), trackIds = trackIds) { id ->
                                controller.toast(
                                    tr("Плейлист «${newName.trim()}» создан", "Playlist «${newName.trim()}» created"),
                                )
                            }
                            onDismiss()
                        },
                    )
                } else {
                    GlassPill(
                        text = tr("+ Новый плейлист", "+ New playlist"),
                        selected = false,
                        onClick = { newMode = true },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Закрыть", "Close"), color = OnyxWhite)
            }
        },
    )
}

package app.onyx.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.music.LocalTrack
import app.onyx.ui.components.AsyncArt
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxSurface
import app.onyx.ui.theme.OnyxWhite
import java.io.File

@Composable
fun MusicScreen(
    controller: AppController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val library by controller.localLibrary.state.collectAsStateWithLifecycle()
    val playback by controller.localPlayer.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var editingTrack by remember { mutableStateOf<LocalTrack?>(null) }

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
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
                .getOrNull()
                ?: uri.lastPathSegment
                ?: tr("Папка", "Folder")
            controller.localLibrary.addFolder(uri, name)
        }
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

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    active = playback.current?.id == track.id,
                    playing = playback.current?.id == track.id && playback.isPlaying,
                    onClick = { controller.localPlayer.play(track, filtered) },
                    onEdit = { editingTrack = track },
                )
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

        playback.current?.let { current ->
            MiniPlayer(
                track = current,
                playing = playback.isPlaying,
                onToggle = { controller.localPlayer.toggle() },
                onNext = { controller.localPlayer.next() },
            )
        }
    }

    editingTrack?.let { track ->
        TrackEditDialog(
            track = track,
            onDismiss = { editingTrack = null },
            onSave = { title, artist, album, artUri ->
                controller.localLibrary.updateTrack(track.id, title, artist, album, artUri)
                editingTrack = null
            },
            onReset = {
                controller.localLibrary.resetTrack(track.id)
                editingTrack = null
            },
        )
    }
}

@Composable
private fun TrackRow(
    track: LocalTrack,
    active: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
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

@Composable
private fun MiniPlayer(
    track: LocalTrack,
    playing: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        padding = 10.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                onClick = onToggle,
            )
            Spacer(Modifier.width(8.dp))
            CircleButton(
                icon = Icons.Rounded.SkipNext,
                contentDescription = tr("Следующий", "Next"),
                size = 34.dp,
                iconSize = 16.dp,
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun TrackEditDialog(
    track: LocalTrack,
    onDismiss: () -> Unit,
    onSave: (String, String, String, android.net.Uri?) -> Unit,
    onReset: () -> Unit,
) {
    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artist) }
    var album by remember(track.id) { mutableStateOf(track.album) }
    var pickedArt by remember(track.id) { mutableStateOf<android.net.Uri?>(null) }
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
                if (track.custom) {
                    TextButton(onClick = onReset) {
                        Text(tr("Сбросить изменения", "Reset changes"), color = OnyxDim, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, artist, album, pickedArt) },
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

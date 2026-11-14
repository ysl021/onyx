package app.onyx.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.core.PrefsData
import app.onyx.music.LocalTrack
import app.onyx.open.OpenDownload
import app.onyx.open.OpenResult
import app.onyx.open.OpenSources
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite
import app.onyx.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Search in catalogues that publish audio under open licences.
 *
 * Unlike the YouTube tab this needs no stream extraction: a result is already
 * a direct file, so it can be streamed by the local player or saved into the
 * music folder as-is. The licence and the author's page are always shown.
 */
@Composable
fun OpenSourcesScreen(
    controller: AppController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = PrefsData())
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OpenResult>>(emptyList()) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    val progress = remember { mutableStateMapOf<String, Float>() }
    var downloading by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(OpenFolderTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            scope.launch {
                val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
                    ?.takeIf { it.isNotBlank() }
                    ?: tr("Папка", "Folder")
                controller.prefs.setYouTubeFolder(uri.toString(), name)
                controller.localLibrary.addFolder(uri, name)
                controller.toast(tr("Сохраняю в «$name»", "Saving into «$name»"))
            }
        }
    }

    fun search() {
        val q = query.trim()
        if (q.length < 2 || loading) return
        loading = true
        message = null
        scope.launch {
            val found = withContext(Dispatchers.IO) { OpenSources.search(q) }
            results = found.results
            errors = found.errors
            searched = found.tried.ifBlank { q }
            if (found.results.isEmpty()) {
                message = tr("Ничего не нашлось", "Nothing found")
            }
            loading = false
        }
    }

    fun play(item: OpenResult) {
        val queue = results.filter { it.fileUrl.startsWith("https://") }.map { it.toLocalTrack() }
        val track = item.toLocalTrack()
        controller.localPlayer.play(track, queue.ifEmpty { listOf(track) })
    }

    fun download(item: OpenResult) {
        val folder = prefs.youtubeFolderUri
        if (folder.isBlank()) {
            folderPicker.launch(musicFolderUri())
            message = tr("Сначала выбери папку для сохранения", "Pick a folder first")
            return
        }
        if (downloading != null) return
        downloading = item.id
        progress[item.id] = 0f
        scope.launch {
            val result = runCatching {
                OpenDownload.download(context, item, folder) { p ->
                    progress[item.id] = if (p.total > 0) {
                        (p.received.toDouble() / p.total).toFloat().coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
            result.onSuccess { name ->
                controller.toast(tr("Скачано: $name", "Downloaded: $name"))
                controller.localLibrary.rescan()
            }.onFailure { e ->
                controller.toast(e.message ?: tr("Не удалось скачать", "Download failed"))
            }
            downloading = null
            progress.remove(item.id)
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr("Открытые источники", "Open sources"),
                color = OnyxWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            GlassPill(
                text = if (prefs.youtubeFolderName.isBlank()) {
                    tr("Папка", "Folder")
                } else {
                    prefs.youtubeFolderName
                },
                selected = prefs.youtubeFolderUri.isNotBlank(),
                onClick = { folderPicker.launch(musicFolderUri()) },
            )
        }
        Text(
            text = tr(
                "Openverse, Internet Archive и ccMixter: только аудио с открытой лицензией.",
                "Openverse, Internet Archive and ccMixter: audio under open licences only.",
            ),
            color = OnyxFaint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text(tr("Найти музыку", "Find music"), color = OnyxFaint, fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = OnyxFaint) },
            trailingIcon = {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(12.dp).size(18.dp),
                        color = OnyxWhite,
                        strokeWidth = 1.5.dp,
                    )
                } else {
                    CircleButton(
                        icon = Icons.Rounded.Search,
                        contentDescription = tr("Искать", "Search"),
                        size = 34.dp,
                        iconSize = 16.dp,
                        onClick = { search() },
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            textStyle = androidx.compose.ui.text.TextStyle(color = OnyxWhite, fontSize = 13.sp),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OnyxLine,
                unfocusedBorderColor = OnyxLine,
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                cursorColor = OnyxWhite,
            ),
        )

        if (searched.isNotBlank() && searched != query.trim()) {
            Text(
                text = tr("Искал как «$searched»", "Searched as «$searched»"),
                color = OnyxFaint,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
        }
        message?.let { text ->
            Text(
                text = text,
                color = OnyxFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }
        if (errors.isNotEmpty()) {
            Text(
                text = tr(
                    "Не ответили: ${errors.joinToString()}",
                    "No answer from: ${errors.joinToString()}",
                ),
                color = OnyxFaint,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results, key = { it.id }) { item ->
                OpenRow(
                    item = item,
                    progress = progress[item.id],
                    downloading = downloading == item.id,
                    onPlay = { play(item) },
                    onDownload = { download(item) },
                    onOpenPage = {
                        if (item.pageUrl.startsWith("http")) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.pageUrl)))
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun OpenRow(
    item: OpenResult,
    progress: Float?,
    downloading: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onOpenPage: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(item.providerLabel)
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.title,
            color = OnyxWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
        if (item.artist.isNotBlank()) {
            Text(item.artist, color = OnyxDim, fontSize = 11.sp, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val meta = buildList {
                if (item.format.isNotBlank()) add(item.format)
                if (item.durationMs > 0) add(formatDuration(item.durationMs))
                if (item.license.isNotBlank()) add(item.license)
            }
            meta.forEach { badge ->
                Text(
                    text = badge,
                    color = OnyxFaint,
                    fontSize = 8.sp,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier
                        .padding(end = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            CircleButton(
                icon = Icons.Rounded.PlayArrow,
                contentDescription = tr("Слушать", "Listen"),
                size = 34.dp,
                iconSize = 17.dp,
                onClick = onPlay,
            )
            if (item.pageUrl.startsWith("http")) {
                CircleButton(
                    icon = Icons.Rounded.OpenInNew,
                    contentDescription = tr("Страница источника", "Source page"),
                    size = 34.dp,
                    iconSize = 15.dp,
                    onClick = onOpenPage,
                )
            }
            if (progress != null || downloading) {
                Text(
                    text = "${((progress ?: 0f) * 100).toInt()}%",
                    color = OnyxWhite,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            } else {
                CircleButton(
                    icon = Icons.Rounded.Download,
                    contentDescription = tr("Скачать", "Download"),
                    size = 34.dp,
                    iconSize = 16.dp,
                    onClick = onDownload,
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val minutes = total / 60
    val seconds = total % 60
    return if (minutes >= 60) {
        String.format(Locale.ROOT, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

private fun OpenResult.toLocalTrack(): LocalTrack = LocalTrack(
    id = "open:$id",
    uri = fileUrl,
    title = title,
    artist = artist,
    album = providerLabel,
    durationMs = durationMs,
    artPath = coverUrl.ifBlank { null },
    folderName = providerLabel,
)

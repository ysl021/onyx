package app.onyx.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import app.onyx.AppController
import app.onyx.core.PrefsData
import app.onyx.ui.components.AsyncArt
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite
import app.onyx.youtube.DownloadJob
import app.onyx.youtube.JobStatus
import app.onyx.youtube.TimecodeParser
import app.onyx.youtube.YtSearchResult
import app.onyx.youtube.YtSection
import app.onyx.youtube.YtVideo
import app.onyx.youtube.YouTubeClient
import app.onyx.youtube.YouTubeDownloads
import app.onyx.youtube.YouTubeSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MODE_SEARCH = "search"
private const val MODE_LINK = "link"

/**
 * YouTube tab: search the catalogue, paste a link, split a long mix into
 * chapters taken from the description and download the selected parts.
 */
@Composable
fun YouTubeScreen(
    controller: AppController,
    onBack: () -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = PrefsData())
    val downloads by YouTubeDownloads.state.collectAsStateWithLifecycle()
    val library by controller.localLibrary.state.collectAsStateWithLifecycle()

    var mode by YouTubeSession.mode
    var query by YouTubeSession.query
    var link by YouTubeSession.link

    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<YtSearchResult>>(emptyList()) }
    var nextPage by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }

    var preview by YouTubeSession.preview
    var resolving by YouTubeSession.resolving
    var previewError by YouTubeSession.error
    var sections by YouTubeSession.sections
    var selectedSections by YouTubeSession.selected
    var manualMode by YouTubeSession.manualMode
    var manualText by YouTubeSession.manualText
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }

    fun deleteSaved(entry: app.onyx.core.YouTubeDownloadEntry) {
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    DocumentFile.fromSingleUri(context, android.net.Uri.parse(entry.uri))?.delete()
                }
                controller.prefs.removeYouTubeDownload(entry.id)
            }
            controller.localLibrary.rescan()
            controller.toast(tr("Удалено", "Deleted"))
        }
    }

    // System back closes the queue or the preview first; it must not silently
    // throw away a resolved mix and jump to the player.
    BackHandler {
        when {
            historyOpen -> historyOpen = false
            queueOpen -> queueOpen = false
            preview != null -> YouTubeSession.closePreview()
            else -> onBack()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            scope.launch {
                val name = DocumentFile.fromTreeUri(context, uri)?.name
                    ?.takeIf { it.isNotBlank() }
                    ?: tr("Папка", "Folder")
                controller.prefs.setYouTubeFolder(uri.toString(), name)
                // Also register it as a music folder so the library picks up new files.
                controller.localLibrary.addFolder(uri, name)
                controller.toast(tr("Сохраняю в «$name»", "Saving into «$name»"))
            }
        }
    }

    fun openVideo(url: String) {
        if (url.isBlank()) return
        scope.launch {
            resolving = true
            previewError = null
            sections = emptyList()
            selectedSections = emptySet()
            try {
                val video = YouTubeClient.resolve(url)
                preview = video
                link = video.url
                // Chapters from YouTube itself first: a video can show clean
                // timestamps on the progress bar with an empty description.
                val parsed = if (video.chapters.size >= 2) {
                    video.chapters
                } else {
                    TimecodeParser.parse(video.description, video.durationMs)
                }
                sections = parsed
                selectedSections = parsed.indices.toSet()
                manualMode = parsed.isEmpty()
            } catch (t: Throwable) {
                previewError = YouTubeClient.describe(t)
            } finally {
                resolving = false
            }
        }
    }

    LaunchedEffect(query, mode) {
        if (mode != MODE_SEARCH) return@LaunchedEffect
        val text = query.trim()
        if (text.length < 2) {
            results = emptyList()
            nextPage = null
            searchError = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(420)
        try {
            val page = YouTubeClient.search(text)
            results = page.items
            nextPage = page.next
            searchError = if (page.items.isEmpty()) {
                tr("Ничего не найдено", "Nothing found")
            } else {
                null
            }
            controller.prefs.addYouTubeQuery(text)
        } catch (t: Throwable) {
            results = emptyList()
            nextPage = null
            searchError = YouTubeClient.describe(t)
        } finally {
            searching = false
        }
    }

    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
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
                    text = "YouTube",
                    color = OnyxWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = if (library.scanning) {
                        tr("Сканирую библиотеку…", "Scanning library…")
                    } else {
                        tr(
                            "Поиск треков и нарезка миксов по таймкодам",
                            "Search tracks and split mixes by timestamps",
                        )
                    },
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CircleButton(
                icon = Icons.Rounded.History,
                contentDescription = tr("История скачивания", "Download history"),
                size = 40.dp,
                iconSize = 18.dp,
                onClick = { historyOpen = true },
            )
            Spacer(Modifier.width(8.dp))
            CircleButton(
                icon = Icons.Rounded.FormatListBulleted,
                contentDescription = tr("Очередь загрузок", "Download queue"),
                size = 40.dp,
                iconSize = 18.dp,
                onClick = { queueOpen = true },
            )
            Spacer(Modifier.width(8.dp))
            CircleButton(
                icon = Icons.Rounded.Folder,
                contentDescription = tr("Папка сохранения", "Save folder"),
                size = 40.dp,
                iconSize = 18.dp,
                onClick = { folderPicker.launch(null) },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassPill(
                text = tr("Поиск", "Search"),
                selected = mode == MODE_SEARCH,
                icon = Icons.Rounded.Search,
                onClick = {
                    if (mode != MODE_SEARCH) {
                        // Leaving the link flow must actually show the search UI:
                        // a resolved preview used to keep the screen unchanged.
                        YouTubeSession.closePreview()
                    }
                    mode = MODE_SEARCH
                },
            )
            GlassPill(
                text = tr("Микс по ссылке", "Mix by link"),
                selected = mode == MODE_LINK,
                icon = Icons.Rounded.Link,
                onClick = { mode = MODE_LINK },
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = prefs.youtubeFolderName.ifBlank { tr("папка не выбрана", "no folder yet") },
                color = if (prefs.youtubeFolderUri.isBlank()) OnyxFaint else OnyxWhite,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { folderPicker.launch(null) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (downloads.jobs.isNotEmpty()) {
            DownloadsCard(
                jobs = downloads.jobs,
                busy = downloads.busy,
                hazeState = hazeState,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                onClear = { YouTubeDownloads.clearFinished() },
            )
        }

        val currentPreview = preview
        if (currentPreview != null) {            PreviewPanel(
                video = currentPreview,
                sections = sections,
                selected = selectedSections,
                resolving = resolving,
                error = previewError,
                manualMode = manualMode,
                manualText = manualText,
                folderReady = prefs.youtubeFolderUri.isNotBlank(),
                busy = downloads.busy,
                onManualText = { manualText = it },
                onToggleManual = { manualMode = !manualMode },
                onParseManual = {
                    val parsed = TimecodeParser.parse(manualText, currentPreview.durationMs)
                    sections = parsed
                    selectedSections = parsed.indices.toSet()
                    previewError = if (parsed.isEmpty()) {
                        tr("Не нашёл таймкоды в тексте", "No timestamps found in the text")
                    } else {
                        null
                    }
                },
                onToggle = { index ->
                    selectedSections = if (index in selectedSections) {
                        selectedSections - index
                    } else {
                        selectedSections + index
                    }
                },
                onSelectAll = { selectedSections = sections.indices.toSet() },
                onSelectNone = { selectedSections = emptySet() },
                onClose = {
                    YouTubeSession.closePreview()
                },
                onDownload = {
                    val chosen = sections.filterIndexed { index, _ -> index in selectedSections }
                    controller.startYouTubeImport(
                        video = currentPreview,
                        sections = chosen,
                        folderUri = prefs.youtubeFolderUri,
                        album = currentPreview.title,
                    )
                },
                onDownloadWhole = {
                    controller.startYouTubeWhole(currentPreview, prefs.youtubeFolderUri)
                },
                onPickFolder = { folderPicker.launch(null) },
                hazeState = hazeState,
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 18.dp),
            ) {
                OutlinedTextField(
                    value = if (mode == MODE_SEARCH) query else link,
                    onValueChange = {
                        if (mode == MODE_SEARCH) query = it else link = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(
                            if (mode == MODE_SEARCH) {
                                tr("Название трека, микса или канала", "Track, mix or channel name")
                            } else {
                                tr("Вставь ссылку на видео", "Paste a video link")
                            },
                            color = OnyxFaint,
                            fontSize = 12.sp,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (mode == MODE_SEARCH) Icons.Rounded.Search else Icons.Rounded.Link,
                            contentDescription = null,
                            tint = OnyxFaint,
                        )
                    },
                    trailingIcon = {
                        val value = if (mode == MODE_SEARCH) query else link
                        if (value.isNotEmpty()) {
                            Icon(
                                Icons.Rounded.Clear,
                                contentDescription = tr("Очистить", "Clear"),
                                tint = OnyxFaint,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        if (mode == MODE_SEARCH) query = "" else link = ""
                                    }
                                    .size(20.dp),
                            )
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = OnyxWhite, fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OnyxLine,
                        unfocusedBorderColor = OnyxLine,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = OnyxWhite,
                    ),
                )

                if (mode == MODE_LINK) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill(
                            text = tr("Проверить", "Check"),
                            selected = true,
                            enabled = link.isNotBlank() && !resolving,
                            onClick = { openVideo(link) },
                        )
                        GlassPill(
                            text = tr("Вставить", "Paste"),
                            selected = false,
                            onClick = {
                                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                val text = clipboard?.primaryClip
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    .orEmpty()
                                if (text.isNotBlank()) link = text.trim()
                            },
                        )
                    }
                }

                if (resolving) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(50)),
                        color = OnyxWhite,
                        trackColor = OnyxWhite.copy(alpha = 0.08f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Разбираю видео…", "Reading the video…"),
                        color = OnyxFaint,
                        fontSize = 11.sp,
                    )
                }

                if (previewError != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(previewError.orEmpty(), color = OnyxFaint, fontSize = 11.sp)
                }

                if (mode == MODE_SEARCH) {
                    if (searching) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            tr("Ищу на YouTube…", "Searching YouTube…"),
                            color = OnyxFaint,
                            fontSize = 11.sp,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 132.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (query.trim().length < 2 && prefs.youtubeHistory.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SectionLabel(
                                        tr("Недавние запросы", "Recent searches"),
                                        Modifier.weight(1f),
                                    )
                                    GlassPill(
                                        text = tr("Очистить", "Clear"),
                                        selected = false,
                                        onClick = {
                                            scope.launch { controller.prefs.clearYouTubeHistory() }
                                        },
                                    )
                                }
                            }
                            items(prefs.youtubeHistory, key = { it }) { entry ->
                                HistoryRow(
                                    text = entry,
                                    onDelete = {
                                        scope.launch { controller.prefs.removeYouTubeQuery(entry) }
                                    },
                                    onClick = { query = entry },
                                )
                            }
                            item { Spacer(Modifier.height(6.dp)) }
                        }
                        items(results) { result ->
                            SearchResultRow(
                                result = result,
                                onClick = {
                                    mode = MODE_LINK
                                    link = result.url
                                    openVideo(result.url)
                                },
                            )
                        }
                        if (searchError != null && results.isEmpty()) {
                            item {
                                Text(searchError.orEmpty(), color = OnyxFaint, fontSize = 11.sp)
                            }
                        }
                        if (nextPage != null) {
                            item {
                                GlassPill(
                                    text = if (loadingMore) {
                                        tr("Загружаю…", "Loading…")
                                    } else {
                                        tr("Ещё", "More")
                                    },
                                    selected = false,
                                    enabled = !loadingMore,
                                    onClick = {
                                        val page = nextPage ?: return@GlassPill
                                        loadingMore = true
                                        scope.launch {
                                            try {
                                                val more = YouTubeClient.search(query.trim(), page)
                                                results = results + more.items
                                                nextPage = more.next
                                            } catch (t: Throwable) {
                                                controller.toast(YouTubeClient.describe(t))
                                            } finally {
                                                loadingMore = false
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        if (results.isEmpty() && !searching && query.trim().length >= 2 && nextPage == null) {
                            item {
                                Text(
                                    tr(
                                        "Проверь запрос или попробуй позже — YouTube иногда ограничивает поиск.",
                                        "Check the query or try later — YouTube sometimes throttles search.",
                                    ),
                                    color = OnyxFaint,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(16.dp))
                    EmptyLinkHint(hazeState = hazeState)
                }
            }
        }
    }

    if (historyOpen) {
        HistoryPanel(
            entries = prefs.youtubeDownloads,
            onClose = { historyOpen = false },
            onDelete = { deleteSaved(it) },
        )
    }

    if (queueOpen) {
        QueuePanel(
            jobs = downloads.jobs,
            busy = downloads.busy,
            onClose = { queueOpen = false },
            onMove = { id, delta -> YouTubeDownloads.moveJob(id, delta) },
            onClear = { YouTubeDownloads.clearFinished() },
        )
    }
    }
}

@Composable
private fun EmptyLinkHint(hazeState: dev.chrisbanes.haze.HazeState? = null) {
    GlassCard(modifier = Modifier.fillMaxWidth(), hazeState = hazeState) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.VideoLibrary,
                contentDescription = null,
                tint = OnyxFaint,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                tr("Как нарезать микс", "How to split a mix"),
                color = OnyxWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(10.dp))
        StepRow(1, tr("Вставь ссылку на видео или микс", "Paste a video or mix link"))
        StepRow(2, tr("ONYX покажет таймкоды из глав видео; лишние можно снять", "ONYX reads chapters; uncheck the ones you skip"))
        StepRow(3, tr("Выбери папку кнопкой сверху — туда лягут отдельные m4a", "Pick a folder above — separate m4a files go there"))
        StepRow(4, tr("Нажми «Скачать»: аудио скачается один раз и нарежется", "Tap Download: audio is fetched once, then cut"))
        Spacer(Modifier.height(6.dp))
        Text(
            tr(
                "Если таймкодов нет — вставь их вручную в формате 0:00 Название.",
                "No timestamps? Type them manually as 0:00 Title.",
            ),
            color = OnyxFaint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(50))
                .background(OnyxWhite.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = OnyxWhite, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            color = OnyxWhite.copy(alpha = 0.82f),
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRow(text: String, onDelete: () -> Unit, onClick: () -> Unit) {
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A1013)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = tr("Удалить запрос", "Delete query"),
                    tint = OnyxWhite.copy(alpha = 0.8f),
                    modifier = Modifier
                        .padding(end = 20.dp)
                        .size(18.dp),
                )
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF508080A))
                .clickable { onClick() }
                .padding(horizontal = 6.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.History,
                contentDescription = null,
                tint = OnyxFaint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                color = OnyxWhite.copy(alpha = 0.85f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    result: YtSearchResult,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncArt(
            model = result.thumbnailUrl,
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = result.title,
                color = OnyxWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(result.channel, fmtTime(result.durationMs))
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                color = OnyxFaint,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Rounded.Download,
            contentDescription = null,
            tint = OnyxFaint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PreviewPanel(
    video: YtVideo,
    sections: List<YtSection>,
    selected: Set<Int>,
    resolving: Boolean,
    error: String?,
    manualMode: Boolean,
    manualText: String,
    folderReady: Boolean,
    busy: Boolean,
    onManualText: (String) -> Unit,
    onToggleManual: () -> Unit,
    onParseManual: () -> Unit,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    onDownloadWhole: () -> Unit,
    onPickFolder: () -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            hazeState = hazeState,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncArt(
                    model = video.thumbnailUrl,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        video.title,
                        color = OnyxWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(video.channel, fmtTime(video.durationMs))
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        color = OnyxFaint,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CircleButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = tr("Закрыть", "Close"),
                    size = 34.dp,
                    iconSize = 16.dp,
                    onClick = onClose,
                )
            }

            if (resolving) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(50)),
                    color = OnyxWhite,
                    trackColor = OnyxWhite.copy(alpha = 0.08f),
                )
            }

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = OnyxFaint, fontSize = 11.sp)
            }

            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    tr("Шаг 2 · Таймкоды", "Step 2 · Timestamps"),
                    color = OnyxWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                GlassPill(
                    text = tr("Все", "All"),
                    selected = false,
                    enabled = sections.isNotEmpty(),
                    onClick = onSelectAll,
                )
                GlassPill(
                    text = tr("Ничего", "None"),
                    selected = false,
                    enabled = sections.isNotEmpty(),
                    onClick = onSelectNone,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    sections.isEmpty() -> tr(
                        "В описании не нашлось таймкодов.",
                        "No timestamps were found in the description.",
                    )
                    else -> tr(
                        "Найдено ${sections.size} отрезков. Выбери нужные.",
                        "Found ${sections.size} sections. Pick the ones you need.",
                    )
                },
                color = OnyxFaint,
                fontSize = 10.sp,
            )

            if (manualMode || sections.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = manualText,
                    onValueChange = onManualText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(118.dp),
                    placeholder = {
                        Text(
                            "0:00 Intro\n3:21 Track name\n7:40 Another one",
                            color = OnyxFaint,
                            fontSize = 11.sp,
                        )
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(color = OnyxWhite, fontSize = 11.sp),
                    shape = RoundedCornerShape(12.dp),
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
                    text = tr("Разобрать текст", "Parse text"),
                    selected = false,
                    enabled = manualText.isNotBlank(),
                    onClick = onParseManual,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                GlassPill(
                    text = tr("Ввести вручную", "Enter manually"),
                    selected = false,
                    onClick = onToggleManual,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(sections.size) { index ->
                val section = sections[index]
                val checked = index in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (checked) OnyxWhite.copy(alpha = 0.07f) else Color.Transparent,
                        )
                        .clickable { onToggle(index) }
                        .padding(horizontal = 6.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (checked) OnyxWhite else OnyxWhite.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (checked) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color(0xFF08080A),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = (index + 1).toString().padStart(2, '0'),
                        color = OnyxFaint,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = section.title,
                        color = OnyxWhite,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${fmtTime(section.startMs)} · ${fmtTime(section.durationMs)}",
                        color = OnyxFaint,
                        fontSize = 10.sp,
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 104.dp),
        ) {
            Text(
                text = tr(
                    "Выбрано: ${selected.size} · " +
                        if (folderReady) "папка выбрана" else "выбери папку",
                    "Selected: ${selected.size} · " +
                        if (folderReady) "folder ready" else "pick a folder",
                ),
                color = OnyxWhite.copy(alpha = 0.9f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassPill(
                    text = tr("Целиком", "Whole"),
                    selected = false,
                    modifier = Modifier.weight(1f),
                    enabled = folderReady && !busy,
                    onClick = onDownloadWhole,
                )
                if (!folderReady) {
                    GlassPill(
                        text = tr("Папка", "Folder"),
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = onPickFolder,
                    )
                }
                GlassPill(
                    text = tr("Скачать", "Download"),
                    selected = true,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Download,
                    enabled = selected.isNotEmpty() && folderReady && !busy,
                    onClick = onDownload,
                )
            }
        }
    }
}

@Composable
private fun DownloadsCard(
    jobs: List<DownloadJob>,
    busy: Boolean,
    modifier: Modifier = Modifier,
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    onClear: () -> Unit,
) {
    val active = jobs.filter { it.status != JobStatus.Done && it.status != JobStatus.Error }
    val done = jobs.count { it.status == JobStatus.Done }
    val failed = jobs.count { it.status == JobStatus.Error }
    val overall = if (jobs.isEmpty()) {
        0f
    } else {
        jobs.sumOf { if (it.status == JobStatus.Done) 1.0 else it.progress.toDouble() }
            .div(jobs.size)
            .toFloat()
    }

    GlassCard(modifier = modifier.fillMaxWidth(), padding = 14.dp, hazeState = hazeState) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(
                if (busy) {
                    tr("Скачивание", "Downloading")
                } else {
                    tr("Загрузки", "Downloads")
                },
                Modifier.weight(1f),
            )
            if (!busy) {
                GlassPill(
                    text = tr("Очистить", "Clear"),
                    selected = false,
                    onClick = onClear,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { overall.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(50)),
            color = OnyxWhite,
            trackColor = OnyxWhite.copy(alpha = 0.08f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = tr(
                "Готово $done из ${jobs.size}" + if (failed > 0) ", ошибок: $failed" else "",
                "Done $done of ${jobs.size}" + if (failed > 0) ", failed: $failed" else "",
            ),
            color = OnyxFaint,
            fontSize = 10.sp,
        )
        active.take(2).forEach { job ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${job.index + 1}/${job.total} · ${job.sectionTitle} · ${statusLabel(job.status)}",
                color = OnyxWhite.copy(alpha = 0.85f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            job.message?.takeIf { job.status == JobStatus.Error }?.let { message ->
                Text(message, color = OnyxFaint, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        jobs.filter { it.status == JobStatus.Error }.take(2).forEach { job ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${job.index + 1}/${job.total} · ${job.sectionTitle} · ${statusLabel(job.status)}",
                color = OnyxWhite.copy(alpha = 0.85f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            job.message?.let { message ->
                Text(message, color = OnyxFaint, fontSize = 9.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun QueuePanel(
    jobs: List<DownloadJob>,
    busy: Boolean,
    onClose: () -> Unit,
    onMove: (String, Int) -> Unit,
    onClear: () -> Unit,
) {
    val done = jobs.count { it.status == JobStatus.Done }
    val failed = jobs.count { it.status == JobStatus.Error }
    val queued = jobs.count { it.status == JobStatus.Queued }
    val finished = jobs.any { it.status == JobStatus.Done || it.status == JobStatus.Error }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF508080A))
            .clickable { },
    ) {
        Column(Modifier.fillMaxSize()) {
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
                    onClick = onClose,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = tr("Очередь загрузок", "Download queue"),
                        color = OnyxWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = tr(
                            "В очереди: $queued · готово: $done" + if (failed > 0) " · ошибок: $failed" else "",
                            "Queued: $queued · done: $done" + if (failed > 0) " · failed: $failed" else "",
                        ),
                        color = OnyxFaint,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (finished && !busy) {
                    GlassPill(
                        text = tr("Очистить", "Clear"),
                        selected = false,
                        onClick = onClear,
                    )
                }
            }

            if (jobs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        tr("Пока ничего не скачивается", "Nothing in the queue yet"),
                        color = OnyxFaint,
                        fontSize = 12.sp,
                    )
                }
                return@Column
            }

            Text(
                tr(
                    "Стрелками меняй приоритет — верхние отрезки скачаются первыми.",
                    "Use the arrows to change priority — the top sections download first.",
                ),
                color = OnyxFaint,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(jobs, key = { _, job -> job.id }) { index, job ->
                    QueueRow(
                        position = index + 1,
                        job = job,
                        canMove = job.status == JobStatus.Queued,
                        onUp = { onMove(job.id, -1) },
                        onDown = { onMove(job.id, 1) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun QueueRow(
    position: Int,
    job: DownloadJob,
    canMove: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    val active = job.status == JobStatus.Downloading ||
        job.status == JobStatus.Splitting ||
        job.status == JobStatus.Tagging ||
        job.status == JobStatus.Saving
    val accent = when {
        job.status == JobStatus.Error -> OnyxFaint
        job.status == JobStatus.Done -> OnyxWhite.copy(alpha = 0.55f)
        active -> OnyxWhite
        else -> OnyxWhite.copy(alpha = 0.8f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OnyxWhite.copy(alpha = if (active) 0.07f else 0.035f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = position.toString().padStart(2, '0'),
                color = OnyxFaint,
                fontSize = 10.sp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = job.videoTitle,
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${job.sectionTitle} · ${fmtTime(job.startMs)}–${fmtTime(job.endMs)} · ${statusLabel(job.status)}",
                    color = OnyxFaint,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canMove) {
                CircleButton(
                    icon = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = tr("Выше", "Up"),
                    size = 30.dp,
                    iconSize = 16.dp,
                    onClick = onUp,
                )
                Spacer(Modifier.width(4.dp))
                CircleButton(
                    icon = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = tr("Ниже", "Down"),
                    size = 30.dp,
                    iconSize = 16.dp,
                    onClick = onDown,
                )
            }
        }
        if (active) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { job.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(50)),
                color = OnyxWhite,
                trackColor = OnyxWhite.copy(alpha = 0.08f),
            )
        }
        job.message?.takeIf { job.status == JobStatus.Error }?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(message, color = OnyxFaint, fontSize = 9.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HistoryPanel(
    entries: List<app.onyx.core.YouTubeDownloadEntry>,
    onClose: () -> Unit,
    onDelete: (app.onyx.core.YouTubeDownloadEntry) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF508080A))
            .clickable { },
    ) {
        Column(Modifier.fillMaxSize()) {
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
                    onClick = onClose,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = tr("История скачивания", "Download history"),
                        color = OnyxWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = tr(
                            "Файлов: ${entries.size} · корзина удаляет файл с телефона",
                            "Files: ${entries.size} · the bin deletes the file from the phone",
                        ),
                        color = OnyxFaint,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        tr("Здесь появятся скачанные треки", "Downloaded tracks will appear here"),
                        color = OnyxFaint,
                        fontSize = 12.sp,
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(OnyxWhite.copy(alpha = 0.035f))
                            .padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = entry.title.ifBlank { entry.name },
                                color = OnyxWhite.copy(alpha = 0.88f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOf(entry.artist, entry.name)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                color = OnyxFaint,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        CircleButton(
                            icon = Icons.Rounded.Delete,
                            contentDescription = tr("Удалить файл", "Delete file"),
                            size = 32.dp,
                            iconSize = 16.dp,
                            onClick = { onDelete(entry) },
                        )
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: JobStatus): String = when (status) {
    JobStatus.Queued -> tr("в очереди", "queued")
    JobStatus.Downloading -> tr("скачиваю", "downloading")
    JobStatus.Splitting -> tr("нарезаю", "splitting")
    JobStatus.Tagging -> tr("теги", "tagging")
    JobStatus.Saving -> tr("сохраняю", "saving")
    JobStatus.Done -> tr("готово", "done")
    JobStatus.Error -> tr("ошибка", "error")
}

/** mm:ss for short clips, h:mm:ss for long mixes. */
internal fun fmtTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

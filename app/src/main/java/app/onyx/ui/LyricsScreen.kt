package app.onyx.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.core.PrefsData
import app.onyx.lyrics.LyricLine
import app.onyx.lyrics.LyricsDoc
import app.onyx.lyrics.LyricsState
import app.onyx.ui.components.BlurredBackdrop
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.GlassSurface
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxSurface
import app.onyx.ui.theme.OnyxWhite
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LyricsScreen(
    controller: AppController,
    onBack: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val media by controller.media.state.collectAsStateWithLifecycle()
    val nowPlaying by controller.nowPlaying.collectAsStateWithLifecycle()
    val manualActive by controller.lyrics.manualActive.collectAsStateWithLifecycle()
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = PrefsData())
    val lyrics by controller.lyrics.state.collectAsStateWithLifecycle()
    val scratch = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val lyricFamily = remember(prefs.lyricFont) { FontCatalog.family(context, prefs.lyricFont) }

    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var showFontPicker by remember { mutableStateOf(false) }
    var manualTitle by remember { mutableStateOf("") }
    var manualArtist by remember { mutableStateOf("") }
    val hazeState = remember { HazeState() }

    val position = media.positionMs + prefs.lyricOffsetMs
    val doc = (lyrics as? LyricsState.Ready)?.doc

    BackHandler(enabled = fullscreen) { fullscreen = false }

    LaunchedEffect(fullscreen) { onFullscreenChanged(fullscreen) }
    DisposableEffect(Unit) {
        onDispose { onFullscreenChanged(false) }
    }

    DisposableEffect(fullscreen) {
        val window = (context as? android.app.Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        if (bars != null) {
            bars.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) {
                bars.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                bars.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .haze(hazeState),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = if (fullscreen) 0.dp else 150.dp),
            ) {
                if (!fullscreen) {
                    TopBar(
                        title = nowPlaying.title,
                        artist = nowPlaying.artist,
                        provider = doc?.provider,
                        karaoke = doc?.karaoke == true,
                        translating = prefs.translate,
                        art = nowPlaying.art ?: nowPlaying.artUri,
                        onToggleTranslate = {
                            val next = !prefs.translate
                            scratch.launch {
                                controller.prefs.setTranslate(next)
                                if (next) controller.lyrics.translateCurrent()
                            }
                        },
                        onFullscreen = { fullscreen = true },
                        onMenu = { showActions = true },
                        onBack = onBack,
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (val s = lyrics) {
                        LyricsState.Idle -> CenterMessage(
                            title = tr("Тишина", "Silence"),
                            text = tr("Запусти трек — текст подтянется сам.", "Play a track and lyrics will load."),
                        )
                        LyricsState.Loading -> Loading()
                        is LyricsState.Error -> CenterMessage(
                            title = tr("Текст не найден", "Lyrics not found"),
                            text = s.message,
                            action = tr("Указать название и исполнителя", "Enter title and artist"),
                            onAction = {
                                manualTitle = nowPlaying.title
                                manualArtist = nowPlaying.artist
                                showSearch = true
                            },
                        )
                        is LyricsState.Ready -> LyricsBody(
                            doc = s.doc,
                            position = position,
                            playing = nowPlaying.isPlaying,
                            fontSize = prefs.lyricFontSize,
                            karaoke = prefs.karaoke,
                            fullscreen = fullscreen,
                            showTranslation = prefs.translate,
                            activeColor = Color(prefs.lyricActiveColor),
                            brightness = prefs.lyricBrightness / 100f,
                            fontFamily = lyricFamily,
                        )
                    }
                }
            }
        }

        if (!fullscreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                BottomBar(
                    art = nowPlaying.art ?: nowPlaying.artUri,
                    fontSize = prefs.lyricFontSize,
                    karaoke = prefs.karaoke,
                    onKaraoke = { scratch.launch { controller.prefs.setKaraoke(!prefs.karaoke) } },
                    onFont = { showFontPicker = true },
                    onFontSize = { size ->
                        scratch.launch { controller.prefs.setFontSize(size.coerceIn(20f, 48f)) }
                    },
                    offsetMs = prefs.lyricOffsetMs,
                    onOffset = { ms ->
                        scratch.launch { controller.prefs.setOffset((ms / 100).toLong() * 100) }
                    },
                    onRetry = { controller.lyrics.retry() },
                    hazeState = hazeState,
                )
            }
        }

        if (fullscreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(190.dp)
                    .hazeChild(
                        state = hazeState,
                        shape = RectangleShape,
                        style = HazeStyle(
                            tint = OnyxSurface.copy(alpha = 0.24f),
                            blurRadius = 34.dp,
                            noiseFactor = 0.03f,
                        ),
                    ),
            )
        }

        if (fullscreen) {
            CircleButton(
                icon = Icons.Rounded.Close,
                contentDescription = tr("Выйти из полноэкранного режима", "Exit fullscreen"),
                size = 38.dp,
                iconSize = 18.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(14.dp),
                onClick = { fullscreen = false },
            )
        }
    }

    if (showSearch) {
        SearchLyricsDialog(
            title = manualTitle,
            artist = manualArtist,
            onTitle = { manualTitle = it },
            onArtist = { manualArtist = it },
            onDismiss = { showSearch = false },
            onSearch = {
                controller.lyrics.searchManual(manualTitle, manualArtist)
                showSearch = false
            },
        )
    }

    if (showActions) {
        AlertDialog(
            onDismissRequest = { showActions = false },
            containerColor = OnyxSurface,
            title = {
                Text(
                    tr("Текст песни", "Lyrics"),
                    color = OnyxWhite,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column {
                    LyricsActionRow(
                        icon = Icons.Rounded.Search,
                        text = tr("Найти вручную", "Search manually"),
                        onClick = {
                            manualTitle = nowPlaying.title
                            manualArtist = nowPlaying.artist
                            showActions = false
                            showSearch = true
                        },
                    )
                    LyricsActionRow(
                        icon = Icons.Rounded.Refresh,
                        text = tr("Искать заново", "Search again"),
                        onClick = {
                            showActions = false
                            controller.lyrics.retry()
                        },
                    )
                    if (manualActive) {
                        LyricsActionRow(
                            icon = Icons.Rounded.RestartAlt,
                            text = tr("Сбросить к авто", "Reset to automatic"),
                            onClick = {
                                showActions = false
                                controller.lyrics.resetToAuto(nowPlaying)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showActions = false }) {
                    Text(tr("Закрыть", "Close"), color = OnyxWhite)
                }
            },
        )
    }

    if (showFontPicker) {
        FontPickerDialog(
            selected = prefs.lyricFont,
            onSelect = { id -> scratch.launch { controller.prefs.setLyricFont(id) } },
            onDismiss = { showFontPicker = false },
        )
    }
}

@Composable
private fun SearchLyricsDialog(
    title: String,
    artist: String,
    onTitle: (String) -> Unit,
    onArtist: (String) -> Unit,
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OnyxSurface,
        title = {
            Text(tr("Поиск текста", "Search lyrics"), color = OnyxWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    tr("Введи название и исполнителя. ONYX поищет в Spicy, Musixmatch, LRCLIB и Genius.", "Enter a title and artist. ONYX will search Spicy, Musixmatch, LRCLIB and Genius."),
                    color = OnyxDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitle,
                    singleLine = true,
                    label = { Text(tr("Название", "Title"), fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = OnyxWhite, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OnyxWhite.copy(alpha = 0.4f),
                        unfocusedBorderColor = OnyxLine,
                        focusedLabelColor = OnyxDim,
                        unfocusedLabelColor = OnyxFaint,
                        cursorColor = OnyxWhite,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = onArtist,
                    singleLine = true,
                    label = { Text(tr("Исполнитель", "Artist"), fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = OnyxWhite, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OnyxWhite.copy(alpha = 0.4f),
                        unfocusedBorderColor = OnyxLine,
                        focusedLabelColor = OnyxDim,
                        unfocusedLabelColor = OnyxFaint,
                        cursorColor = OnyxWhite,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSearch, enabled = title.isNotBlank()) {
                Text(tr("Искать", "Search"), color = OnyxWhite, fontWeight = FontWeight.SemiBold)
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
private fun TopBar(
    title: String,
    artist: String,
    provider: String?,
    karaoke: Boolean,
    translating: Boolean,
    art: Any?,
    onToggleTranslate: () -> Unit,
    onFullscreen: () -> Unit,
    onMenu: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = tr("Назад", "Back"),
            size = 42.dp,
            iconSize = 20.dp,
            onClick = onBack,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .background(OnyxWhite.copy(alpha = 0.06f)),
        ) {
            if (art != null) {
                coil.compose.AsyncImage(
                    model = art,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "ONYX" },
                color = OnyxWhite,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (artist.isNotBlank()) append(artist)
                    if (provider != null) {
                        if (isNotEmpty()) append(" · ")
                        append(provider)
                    }
                    if (karaoke) append(tr(" · караоке", " · karaoke"))
                },
                color = OnyxFaint,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        CircleButton(
            icon = Icons.Rounded.Translate,
            contentDescription = tr("Перевод", "Translation"),
            size = 38.dp,
            iconSize = 18.dp,
            filled = translating,
            onClick = onToggleTranslate,
        )
        Spacer(Modifier.width(6.dp))
        CircleButton(
            icon = Icons.Rounded.Fullscreen,
            contentDescription = tr("Полноэкранный режим", "Fullscreen"),
            size = 38.dp,
            iconSize = 18.dp,
            onClick = onFullscreen,
        )
        Spacer(Modifier.width(6.dp))
        CircleButton(
            icon = Icons.Rounded.MoreVert,
            contentDescription = tr("Действия с текстом", "Lyrics actions"),
            size = 38.dp,
            iconSize = 18.dp,
            onClick = onMenu,
        )
    }
}

@Composable
private fun LyricsActionRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = OnyxDim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = OnyxWhite, fontSize = 14.sp)
    }
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = OnyxWhite,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(tr("Ищем текст…", "Searching lyrics…"), color = OnyxDim, fontSize = 13.sp)
    }
}

@Composable
private fun CenterMessage(
    title: String,
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            color = OnyxWhite,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = text,
            color = OnyxDim,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
        if (action != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            GlassPill(text = action, selected = true, icon = Icons.Rounded.Refresh, onClick = onAction)
        }
    }
}

@Composable
private fun LyricsBody(
    doc: LyricsDoc,
    position: Long,
    playing: Boolean,
    fontSize: Float,
    karaoke: Boolean,
    fullscreen: Boolean,
    showTranslation: Boolean,
    activeColor: Color,
    brightness: Float = 1f,
    fontFamily: FontFamily?,
) {
    val activeIndex = remember(doc, position) { activeLineIndex(doc, position) }

    // MediaSession reports position in ~300 ms steps. Interpolate between those
    // updates so the karaoke fill and word colours move every frame instead of
    // jumping in chunks.
    var smoothPosition by remember { mutableLongStateOf(position) }
    LaunchedEffect(position) {
        // Only re-anchor on seeks/pauses; small differences are just reporting lag.
        if (kotlin.math.abs(position - smoothPosition) > 450) smoothPosition = position
    }
    val lineHasWords = doc.lines.getOrNull(activeIndex)?.words?.isNotEmpty() == true
    LaunchedEffect(playing, activeIndex, lineHasWords) {
        if (!playing || !lineHasWords) return@LaunchedEffect
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last == 0L) {
                    last = now
                } else {
                    smoothPosition += (now - last) / 1_000_000L
                    last = now
                }
            }
        }
    }

    if (!doc.synced) {
        val scroll = androidx.compose.foundation.rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 26.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = doc.lines.joinToString("\n\n") { it.text },
                color = OnyxWhite.copy(alpha = brightness.coerceIn(0.35f, 1f)),
                fontSize = (fontSize * 0.65f).sp,
                lineHeight = (fontSize * 0.95f).sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                fontFamily = fontFamily,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    // Text-first mode: no auto-centering, no word fill. The user can scroll
    // through the whole song like a normal lyrics page.
    if (!karaoke) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = if (fullscreen) 34.dp else 26.dp,
                end = if (fullscreen) 34.dp else 26.dp,
                top = 24.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(doc.lines) { index, line ->
                val active = index == activeIndex
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = line.text,
                        color = if (active) {
                            OnyxWhite.copy(alpha = brightness.coerceIn(0.35f, 1f))
                        } else {
                            OnyxWhite.copy(alpha = 0.68f * brightness.coerceIn(0.35f, 1f))
                        },
                        fontSize = (fontSize * 0.82f).sp,
                        lineHeight = (fontSize * 1.08f).sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = (-0.3).sp,
                        fontFamily = fontFamily,
                    )
                    val translation = line.translation
                    if (showTranslation && translation != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = translation,
                            color = OnyxWhite.copy(
                                alpha = (if (active) 0.56f else 0.36f) *
                                    brightness.coerceIn(0.35f, 1f),
                            ),
                            fontSize = (fontSize * 0.46f).sp,
                            lineHeight = (fontSize * 0.60f).sp,
                        )
                    }
                }
            }
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val verticalPad = if (fullscreen) (maxHeight / 2.1f).coerceAtLeast(90.dp)
        else (maxHeight / 2.2f).coerceAtLeast(80.dp)
        val state = rememberLazyListState()
        LaunchedEffect(activeIndex) {
            if (activeIndex >= 0) {
                // animated scroll is fine now that the offset is zero; the earlier
                // "-padPx" correction was what pushed lines to the bottom edge.
                state.animateScrollToItem(activeIndex)
            }
        }

        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = if (fullscreen) 34.dp else 26.dp,
                vertical = verticalPad,
            ),
            verticalArrangement = Arrangement.spacedBy(if (fullscreen) 22.dp else 18.dp),
        ) {
            itemsIndexed(doc.lines) { index, line ->
                val active = index == activeIndex
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                ) {
                    LyricLineText(
                        line = line,
                        active = active,
                        position = if (active) smoothPosition else position,
                        fontSize = if (fullscreen) fontSize * 1.08f else fontSize,
                        karaoke = karaoke,
                        activeColor = activeColor,
                        fontFamily = fontFamily,
                    )
                    val translation = line.translation
                    if (showTranslation && translation != null) {
                        Spacer(Modifier.height(if (active) 8.dp else 4.dp))
                        Text(
                            text = translation,
                            color = if (active) OnyxWhite.copy(alpha = 0.62f) else OnyxWhite.copy(alpha = 0.26f),
                            fontSize = (fontSize * if (active) 0.48f else 0.42f).sp,
                            lineHeight = (fontSize * if (active) 0.62f else 0.54f).sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Start,
                        )
                    }
                    if (active) {
                        val next = doc.lines.getOrNull(index + 1)
                        val lastEnd = line.words.lastOrNull()?.endMs ?: line.endMs
                        val showDots = next != null &&
                            next.startMs - lastEnd > 2500 &&
                            smoothPosition in lastEnd until next.startMs
                        if (showDots) {
                            Spacer(Modifier.height(6.dp))
                            PauseDots()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricLineText(
    line: LyricLine,
    active: Boolean,
    position: Long,
    fontSize: Float,
    karaoke: Boolean,
    activeColor: Color,
    fontFamily: FontFamily?,
) {
    val size by animateFloatAsState(
        targetValue = if (active) fontSize else fontSize * 0.66f,
        label = "lineSize",
    )
    val baseColor = when {
        active -> activeColor
        line.startMs <= position -> OnyxWhite.copy(alpha = 0.52f)
        else -> OnyxWhite.copy(alpha = 0.30f)
    }

    val text: AnnotatedString = if (karaoke && line.words.isNotEmpty()) {
        buildAnnotatedString {
            line.words.forEach { word ->
                val span = (word.endMs - word.startMs).coerceAtLeast(1L)
                val wordProgress = ((position - word.startMs).toFloat() / span)
                    .coerceIn(0f, 1f)
                withStyle(
                    SpanStyle(
                        color = when {
                            !active -> baseColor
                            position >= word.endMs -> activeColor
                            else -> androidx.compose.ui.graphics.lerp(
                                OnyxWhite.copy(alpha = 0.28f),
                                activeColor,
                                wordProgress,
                            )
                        },
                        fontWeight = FontWeight.Bold,
                    ),
                ) {
                    append(word.text)
                }
            }
        }
    } else {
        buildAnnotatedString {
            withStyle(SpanStyle(color = baseColor, fontWeight = FontWeight.Bold)) {
                append(line.text)
            }
        }
    }

    Text(
        text = text,
        fontSize = size.sp,
        lineHeight = (size * 1.12f).sp,
        letterSpacing = (-0.4).sp,
        fontFamily = fontFamily,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (active) 1f else if (line.startMs <= position) 0.9f else 0.65f),
    )
}

@Composable
private fun PauseDots() {
    val transition = rememberInfiniteTransition(label = "pauseDots")
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(
                        durationMillis = 620,
                        delayMillis = i * 150,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(OnyxWhite.copy(alpha = alpha)),
            )
        }
    }
}

private fun activeLineIndex(doc: LyricsDoc, position: Long): Int {
    if (!doc.synced) return -1
    var index = -1
    for (i in doc.lines.indices) {
        if (doc.lines[i].startMs <= position) index = i else break
    }
    return index
}

@Composable
private fun BottomBar(
    art: Any?,
    fontSize: Float,
    karaoke: Boolean,
    onKaraoke: () -> Unit,
    onFont: () -> Unit,
    onFontSize: (Float) -> Unit,
    offsetMs: Long,
    onOffset: (Long) -> Unit,
    onRetry: () -> Unit,
    hazeState: HazeState? = null,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(top = 4.dp, bottom = 104.dp),
        shape = RoundedCornerShape(26.dp),
        strong = true,
        art = art,
        artBlur = 34.dp,
        hazeState = hazeState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassPill(text = "A−", selected = false, onClick = { onFontSize(fontSize - 2f) })
            GlassPill(text = "A+", selected = false, onClick = { onFontSize(fontSize + 2f) })
            GlassPill(text = tr("Караоке", "Karaoke"), selected = karaoke, onClick = onKaraoke)
            CircleButton(
                icon = Icons.Rounded.TextFields,
                contentDescription = tr("Шрифт", "Font"),
                size = 38.dp,
                iconSize = 18.dp,
                onClick = onFont,
            )
            Text(
                text = "Aa ${fontSize.toInt()}",
                color = OnyxFaint,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            CircleButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = tr("Искать заново", "Search again"),
                size = 38.dp,
                iconSize = 17.dp,
                onClick = onRetry,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = tr("Смещение", "Offset"),
                color = OnyxFaint,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp,
            )
            GlassPill(text = "−0.1", selected = false, onClick = { onOffset(offsetMs - 100) })
            GlassPill(
                text = if (offsetMs == 0L) tr("0 мс", "0 ms") else "$offsetMs ${tr("мс", "ms")}",
                selected = offsetMs != 0L,
                onClick = { onOffset(0) },
            )
            GlassPill(text = "+0.1", selected = false, onClick = { onOffset(offsetMs + 100) })
        }
        }
    }
}

package app.onyx

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.core.PrefsData
import app.onyx.core.ThemeMode
import app.onyx.ui.HomeScreen
import app.onyx.ui.AlbumScreen
import app.onyx.ui.AlbumSelection
import app.onyx.ui.LocalOpenAlbum
import app.onyx.ui.AppLanguageState
import app.onyx.ui.FontCatalog
import app.onyx.ui.FontPickerDialog
import app.onyx.ui.IntroScreen
import app.onyx.ui.OnboardingScreen
import app.onyx.ui.LocalAppLanguage
import app.onyx.ui.tr
import app.onyx.ui.LyricsScreen
import app.onyx.ui.MusicMiniPlayer
import app.onyx.ui.MusicScreen
import app.onyx.ui.PlaylistsScreen
import app.onyx.ui.SettingsScreen
import app.onyx.ui.VisualizerScreen
import app.onyx.ui.YouTubeScreen
import app.onyx.ui.components.AmbientBackdrop
import app.onyx.ui.components.BlurredBackdrop
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.CustomBackdrop
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.GlassSurface
import app.onyx.ui.theme.OnyxBlack
import app.onyx.ui.theme.toOnyxColors
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxTheme
import app.onyx.ui.theme.OnyxWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Tab { Player, Lyrics, Playlists, Music, YouTube, Visualizer, Settings }

@Composable
fun OnyxApp() {
    val context = LocalContext.current
    val controller = remember { AppController.get(context).also { it.start() } }
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = PrefsData())
    val media by controller.media.state.collectAsStateWithLifecycle()
    val nowPlaying by controller.nowPlaying.collectAsStateWithLifecycle()

    // Players briefly drop metadata (art = null) on pause and between tracks.
    // Keeping the last cover avoids the backdrop falling back to a black
    // AmbientBackdrop for a frame — that was the visible flash.
    val rawArt = nowPlaying.art ?: nowPlaying.artUri
    var stickyArt by remember { mutableStateOf<Any?>(rawArt) }
    LaunchedEffect(rawArt) {
        // Copy the bitmap: players recycle their metadata bitmaps on pause and
        // between tracks, which made the blurred background go black for a frame.
        stickyArt = when (val art = rawArt) {
            is android.graphics.Bitmap -> runCatching {
                art.copy(art.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
            }.getOrDefault(art)
            null -> stickyArt
            else -> art
        }
    }
    val localPlayback by controller.localPlayer.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    val panelScope = rememberCoroutineScope()
    var showLyricFontPicker by remember { mutableStateOf(false) }
    val customThemes = remember(prefs.customThemes) {
        val saved = app.onyx.core.CustomThemeCodec.decodeList(prefs.customThemes)
        (saved + app.onyx.core.BuiltInThemes).distinctBy { it.id }
    }
    val customPalette = remember(customThemes, prefs.customThemeId) {
        customThemes.firstOrNull { it.id == prefs.customThemeId }
            ?.toOnyxColors()
    }
    val dark = when (prefs.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    LaunchedEffect(dark, customPalette) {
        val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val effectiveLight = customPalette?.isLight ?: !dark
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = effectiveLight
        controller.isAppearanceLightNavigationBars = effectiveLight
    }
    var tab by remember {
        mutableStateOf(
            runCatching { Tab.valueOf(controller.lastTab) }.getOrDefault(Tab.Player),
        )
    }
    var albumSelection by remember { mutableStateOf<AlbumSelection?>(null) }
    LaunchedEffect(tab) { controller.lastTab = tab.name }
    var showIntro by rememberSaveable { mutableStateOf(true) }
    var showOnboarding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showOnboarding = controller.onboardingDone?.let { !it }
            ?: runCatching { !controller.prefs.snapshot().onboardingDone }
                .getOrDefault(false)
    }
    LaunchedEffect(prefs.onboardingDone) {
        if (prefs.onboardingDone) showOnboarding = false
    }
    var lyricsFullscreen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Horizontal swipe moves one tab left/right (Player <-> Lyrics is the common case).
    fun shiftTab(delta: Int) {
        val order = Tab.entries
        val index = order.indexOf(tab)
        val next = (index + delta).coerceIn(0, order.lastIndex)
        if (next != index) {
            lyricsFullscreen = false
            tab = order[next]
        }
    }
    val spotifyConnected = prefs.spotifyRefresh.isNotBlank() || prefs.spotifyAccess.isNotBlank()
    LaunchedEffect(spotifyConnected) {
        if (!spotifyConnected && tab == Tab.Playlists) tab = Tab.Player
    }

    LaunchedEffect(controller) {
        controller.messages.collect { msg ->
            message = msg
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2600)
            message = null
        }
    }

    BackHandler(enabled = tab != Tab.Player) {
        tab = Tab.Player
    }

    AppLanguageState.current = prefs.language
    val uiFontFamily = FontCatalog.family(context, prefs.uiFont)
    val languageFade = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(prefs.language) {
        languageFade.snapTo(0.86f)
        languageFade.animateTo(1f, tween(180))
    }
    val hazeState = remember { HazeState() }
    OnyxTheme(prefs.themeMode, uiFontFamily, custom = customPalette, textBrightness = prefs.uiTextBrightness) {
        CompositionLocalProvider(LocalAppLanguage provides prefs.language) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(OnyxBlack)
                    .graphicsLayer { alpha = languageFade.value },
            ) {
                val pageKey = when (tab) {
                    Tab.Player -> "player"
                    Tab.Lyrics -> "lyrics"
                    Tab.Playlists -> "playlists"
                    Tab.Music -> "music"
                    Tab.YouTube -> "youtube"
                    Tab.Visualizer -> "visualizer"
                    Tab.Settings -> "settings"
                }
                val pageCover = prefs.coverPages.split(',').contains(pageKey)
                val pageBackgrounds = remember(prefs.pageBackgrounds) {
                    prefs.pageBackgrounds.split('\u001E').mapNotNull { record ->
                        if (record.isBlank()) return@mapNotNull null
                        val parts = record.split('\u001F', limit = 2)
                        val key = parts.getOrNull(0).orEmpty()
                        val path = parts.getOrNull(1).orEmpty()
                        if (key.isBlank() || path.isBlank()) null else key to java.io.File(path)
                    }.toMap()
                }
                val customBg = pageBackgrounds[pageKey]?.takeIf { it.exists() }
                val blurMap = remember(prefs.pageBackgroundBlur) {
                    prefs.pageBackgroundBlur.split('\u001E').mapNotNull { record ->
                        if (record.isBlank()) return@mapNotNull null
                        val parts = record.split('\u001F', limit = 2)
                        val key = parts.getOrNull(0).orEmpty()
                        val value = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        if (key.isBlank()) null else key to value
                    }.toMap()
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .haze(hazeState),
                ) {
                    Box(Modifier.fillMaxSize().background(OnyxBlack))
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.Transparent,
                                        OnyxWhite.copy(alpha = 0.025f),
                                        OnyxWhite.copy(alpha = 0.05f),
                                    ),
                                ),
                            ),
                    )
                    if (customBg != null) {
                        CustomBackdrop(art = customBg, blurPercent = blurMap[pageKey] ?: 0)
                    } else if (prefs.coverBackdrop && pageCover) {
                        val f = (prefs.coverIntensity / 100f).coerceIn(0f, 1f)
                        // Crossfade instead of swapping the image: the old cover
                        // stays visible while the new one decodes, so switching
                        // tracks (and pausing) no longer flashes black.
                        Crossfade(
                            targetState = stickyArt,
                            animationSpec = tween(650),
                            label = "backdrop",
                        ) { art ->
                            if (art != null) {
                                BlurredBackdrop(
                                    art = art,
                                    artAlpha = 0.22f + 0.68f * f,
                                    scrimTop = 0.78f - 0.42f * f,
                                    scrimMid = 0.62f - 0.32f * f,
                                    scrimBottom = 0.42f - 0.28f * f,
                                )
                            } else {
                                AmbientBackdrop()
                            }
                        }
                    } else {
                        AmbientBackdrop()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .tabSwipe(
                                onNext = { shiftTab(1) },
                                onPrev = { shiftTab(-1) },
                            ),
                    ) {
                        CompositionLocalProvider(LocalOpenAlbum provides { albumSelection = it }) {
                            AnimatedContent(
                                targetState = tab,
                                transitionSpec = {
                                    val forward = targetState.ordinal > initialState.ordinal
                                    val dir = if (forward) 1 else -1
                                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 * dir })
                                        .togetherWith(
                                            fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { -it / 16 * dir },
                                        )
                                },
                                label = "tab",
                            ) { current ->
                                when (current) {
                                    Tab.Player -> HomeScreen(
                                        controller = controller,
                                        onOpenLyrics = { tab = Tab.Lyrics },
                                        onOpenSettings = { tab = Tab.Settings },
                                    )
                                    Tab.Lyrics -> LyricsScreen(
                                        controller = controller,
                                        onBack = { tab = Tab.Player },
                                        onFullscreenChanged = { lyricsFullscreen = it },
                                        hazeState = hazeState,
                                    )
                                    Tab.Playlists -> PlaylistsScreen(
                                        controller = controller,
                                        onBack = { tab = Tab.Player },
                                        onOpenSettings = { tab = Tab.Settings },
                                    )
                                    Tab.Music -> MusicScreen(controller = controller)
                                    Tab.YouTube -> YouTubeScreen(
                                        controller = controller,
                                        onBack = { tab = Tab.Player },
                                    )
                                    Tab.Visualizer -> VisualizerScreen(controller = controller)
                                    Tab.Settings -> SettingsScreen(
                                        controller = controller,
                                        onBack = { tab = Tab.Player },
                                    )
                                }
                            }
                        }
                    }
                }

                if (tab != Tab.Lyrics || !lyricsFullscreen) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                    ) {
                        BottomNav(
                            tab = tab,
                            onSelect = {
                                lyricsFullscreen = false
                                tab = it
                            },
                            art = stickyArt,
                            spotifyConnected = spotifyConnected,
                            hazeState = hazeState,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // Mini-player for local music. Drawn outside the haze source (like
                // BottomNav) so its own text stays sharp while the backdrop and the
                // track list behind it are blurred.
                if (tab == Tab.Music) {
                    AnimatedVisibility(
                        visible = localPlayback.current != null,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 }),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 104.dp),
                    ) {
                        localPlayback.current?.let { track ->
                            MusicMiniPlayer(
                                controller = controller,
                                track = track,
                                playing = localPlayback.isPlaying,
                                hazeState = hazeState,
                            )
                        }
                    }
                }

                // Lyrics toolbar. Rendered outside the haze source for the same
                // reason: only the backdrop and the lyrics behind it are blurred.
                if (tab == Tab.Lyrics) {
                    AnimatedVisibility(
                        visible = !lyricsFullscreen && prefs.lyricsToolbarVisible,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 }),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                            .padding(top = 4.dp, bottom = 104.dp),
                    ) {
                        LyricsToolsBar(
                            art = stickyArt,
                            fontSize = prefs.lyricFontSize,
                            karaoke = prefs.karaoke,
                            onKaraoke = { panelScope.launch { controller.prefs.setKaraoke(!prefs.karaoke) } },
                            onFont = { showLyricFontPicker = true },
                            onFontSize = { size ->
                                panelScope.launch { controller.prefs.setFontSize(size.coerceIn(20f, 48f)) }
                            },
                            offsetMs = prefs.lyricOffsetMs,
                            onOffset = { ms ->
                                panelScope.launch { controller.prefs.setOffset((ms / 100).toLong() * 100) }
                            },
                            onRetry = { controller.lyrics.retry() },
                            onHide = { panelScope.launch { controller.prefs.setLyricsToolbarVisible(false) } },
                            hazeState = hazeState,
                        )
                    }

                    // Floating button to bring the toolbar back after hiding it.
                    if (!prefs.lyricsToolbarVisible && !lyricsFullscreen) {
                        CircleButton(
                            icon = Icons.Rounded.Tune,
                            contentDescription = tr("Показать панель текста", "Show lyrics toolbar"),
                            size = 40.dp,
                            iconSize = 20.dp,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .navigationBarsPadding()
                                .padding(end = 18.dp, bottom = 112.dp),
                            onClick = { panelScope.launch { controller.prefs.setLyricsToolbarVisible(true) } },
                        )
                    }
                }

                if (showLyricFontPicker) {
                    FontPickerDialog(
                        selected = prefs.lyricFont,
                        onSelect = { id -> panelScope.launch { controller.prefs.setLyricFont(id) } },
                        onDismiss = { showLyricFontPicker = false },
                    )
                }

                AnimatedVisibility(
                    visible = message != null,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = if (tab == Tab.Lyrics) 84.dp else 104.dp)
                        .padding(horizontal = 22.dp),
                ) {
                    GlassSurface(
                        shape = RoundedCornerShape(16.dp),
                        strong = true,
                    ) {
                        Text(
                            text = message.orEmpty(),
                            color = OnyxWhite,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                        )
                    }
                }

                if (showOnboarding) {
                    OnboardingScreen(
                        controller = controller,
                        onDone = { showOnboarding = false },
                    )
                }

                albumSelection?.let { selection ->
                    AlbumScreen(
                        selection = selection,
                        controller = controller,
                        onBack = { albumSelection = null },
                    )
                }

                if (showIntro) {
                    IntroScreen(onFinish = { showIntro = false })
                }
            }
        }
    }
}

@Composable
private fun BottomNav(
    tab: Tab,
    onSelect: (Tab) -> Unit,
    art: Any? = null,
    spotifyConnected: Boolean = false,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        strong = true,
        art = art,
        artBlur = 36.dp,
        hazeState = hazeState,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(
                icon = Icons.Rounded.GraphicEq,
                label = tr("Плеер", "Player"),
                selected = tab == Tab.Player,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(Tab.Player) },
            )
            NavItem(
                icon = Icons.Rounded.Translate,
                label = tr("Текст", "Lyrics"),
                selected = tab == Tab.Lyrics,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(Tab.Lyrics) },
            )
            NavItem(
                icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                label = tr("Плейлист", "Playlist"),
                selected = tab == Tab.Playlists,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(Tab.Playlists) },
            )
            NavItem(
                icon = Icons.Rounded.LibraryMusic,
                label = tr("Музыка", "Music"),
                selected = tab == Tab.Music,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(Tab.Music) },
            )
            NavItem(
                icon = Icons.Rounded.VideoLibrary,
                label = "YouTube",
                selected = tab == Tab.YouTube,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(Tab.YouTube) },
            )
            NavItem(
                icon = Icons.Rounded.BarChart,
                label = tr("Визуал", "Visual"),
                selected = tab == Tab.Visualizer,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(Tab.Visualizer) },
            )
            NavItem(
                icon = Icons.Rounded.Settings,
                label = tr("Настройки", "Settings"),
                selected = tab == Tab.Settings,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(Tab.Settings) },
            )
        }
    }
}

/**
 * Lyrics toolbar (font size, karaoke, offset, re-search). Drawn above the dock as a
 * sibling of the haze source so its own labels stay sharp and the blur shows the
 * backdrop and the lyrics behind it.
 */
@Composable
private fun LyricsToolsBar(
    art: Any?,
    fontSize: Float,
    karaoke: Boolean,
    onKaraoke: () -> Unit,
    onFont: () -> Unit,
    onFontSize: (Float) -> Unit,
    offsetMs: Long,
    onOffset: (Long) -> Unit,
    onRetry: () -> Unit,
    onHide: () -> Unit = {},
    hazeState: HazeState? = null,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
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
                CircleButton(
                    icon = Icons.Rounded.ExpandMore,
                    contentDescription = tr("Скрыть панель", "Hide toolbar"),
                    size = 38.dp,
                    iconSize = 18.dp,
                    onClick = onHide,
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

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val palette = app.onyx.ui.theme.LocalOnyx.current
    val bg by animateColorAsState(
        targetValue = if (selected) app.onyx.ui.theme.OnyxAccent else Color.Transparent,
        animationSpec = tween(240),
        label = "navBg",
    )
    val fg by animateColorAsState(
        targetValue = when {
            selected && palette.isLight -> Color.White
            selected -> Color(0xFF08080A)
            else -> OnyxDim
        },
        animationSpec = tween(240),
        label = "navFg",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        label = "navPress",
    )
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { onClick() }
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = fg,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            color = fg,
            fontSize = 7.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = 0.sp,
            maxLines = 1,
        )
    }
}

/**
 * Horizontal swipe that flips one tab left/right. Vertical scrolling and inner
 * horizontal lists keep priority: those children consume the gesture first, so
 * this detector only sees a deliberate sideways drag on empty page area.
 */
private fun Modifier.tabSwipe(onNext: () -> Unit, onPrev: () -> Unit): Modifier =
    pointerInput(Unit) {
        val threshold = 110.dp.toPx()
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                when {
                    total <= -threshold -> onNext()
                    total >= threshold -> onPrev()
                }
            },
        ) { change, dragAmount ->
            total += dragAmount
            change.consume()
        }
    }

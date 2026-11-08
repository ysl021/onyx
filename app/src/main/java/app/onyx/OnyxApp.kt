package app.onyx

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.onyx.ui.AppLanguageState
import app.onyx.ui.FontCatalog
import app.onyx.ui.IntroScreen
import app.onyx.ui.LocalAppLanguage
import app.onyx.ui.tr
import app.onyx.ui.LyricsScreen
import app.onyx.ui.MusicScreen
import app.onyx.ui.PlaylistsScreen
import app.onyx.ui.SettingsScreen
import app.onyx.ui.VisualizerScreen
import app.onyx.ui.components.AmbientBackdrop
import app.onyx.ui.components.BlurredBackdrop
import app.onyx.ui.components.CustomBackdrop
import app.onyx.ui.components.GlassSurface
import app.onyx.ui.theme.OnyxBlack
import app.onyx.ui.theme.toOnyxColors
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxTheme
import app.onyx.ui.theme.OnyxWhite
import kotlinx.coroutines.delay

private enum class Tab { Player, Lyrics, Playlists, Music, Visualizer, Settings }

@Composable
fun OnyxApp() {
    val context = LocalContext.current
    val controller = remember { AppController.get(context).also { it.start() } }
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = PrefsData())
    val media by controller.media.state.collectAsStateWithLifecycle()
    val nowPlaying by controller.nowPlaying.collectAsStateWithLifecycle()
    val view = LocalView.current
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
    LaunchedEffect(tab) { controller.lastTab = tab.name }
    var showIntro by rememberSaveable { mutableStateOf(true) }
    var lyricsFullscreen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
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
    OnyxTheme(prefs.themeMode, uiFontFamily, custom = customPalette) {
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
                    } else if (prefs.coverBackdrop && pageCover && (nowPlaying.art ?: nowPlaying.artUri) != null) {
                        val f = (prefs.coverIntensity / 100f).coerceIn(0f, 1f)
                        BlurredBackdrop(
                            art = nowPlaying.art ?: nowPlaying.artUri,
                            artAlpha = 0.22f + 0.68f * f,
                            scrimTop = 0.78f - 0.42f * f,
                            scrimMid = 0.62f - 0.32f * f,
                            scrimBottom = 0.42f - 0.28f * f,
                        )
                    } else {
                        AmbientBackdrop()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                    ) {
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
                                )
                                Tab.Playlists -> PlaylistsScreen(
                                    controller = controller,
                                    onBack = { tab = Tab.Player },
                                    onOpenSettings = { tab = Tab.Settings },
                                )
                                Tab.Music -> MusicScreen(controller = controller)
                                Tab.Visualizer -> VisualizerScreen(controller = controller)
                                Tab.Settings -> SettingsScreen(
                                    controller = controller,
                                    onBack = { tab = Tab.Player },
                                )
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
                            art = nowPlaying.art ?: nowPlaying.artUri,
                            spotifyConnected = spotifyConnected,
                            hazeState = hazeState,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
            if (spotifyConnected) {
                NavItem(
                    icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    label = tr("Плейлист", "Playlist"),
                    selected = tab == Tab.Playlists,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(Tab.Playlists) },
                )
            }
            NavItem(
                icon = Icons.Rounded.LibraryMusic,
                label = tr("Музыка", "Music"),
                selected = tab == Tab.Music,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(Tab.Music) },
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
            fontSize = 7.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = 0.1.sp,
            maxLines = 1,
        )
    }
}

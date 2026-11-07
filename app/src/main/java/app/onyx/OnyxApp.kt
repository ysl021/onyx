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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onyx.ui.HomeScreen
import app.onyx.ui.IntroScreen
import app.onyx.ui.LyricsScreen
import app.onyx.ui.QueueScreen
import app.onyx.ui.SettingsScreen
import app.onyx.ui.components.GlassSurface
import app.onyx.ui.theme.OnyxBlack
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxWhite
import kotlinx.coroutines.delay

private enum class Tab { Player, Lyrics, Queue, Settings }

@Composable
fun OnyxApp() {
    val context = LocalContext.current
    val controller = remember { AppController.get(context).also { it.start() } }
    var tab by rememberSaveable { mutableStateOf(Tab.Player) }
    var showIntro by rememberSaveable { mutableStateOf(true) }
    var lyricsFullscreen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

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

    Box(modifier = Modifier.fillMaxSize().background(OnyxBlack)) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
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
                            onOpenQueue = { tab = Tab.Queue },
                            onOpenSettings = { tab = Tab.Settings },
                        )
                        Tab.Lyrics -> LyricsScreen(
                            controller = controller,
                            onBack = { tab = Tab.Player },
                            onFullscreenChanged = { lyricsFullscreen = it },
                        )
                        Tab.Queue -> QueueScreen(
                            controller = controller,
                            onOpenSettings = { tab = Tab.Settings },
                        )
                        Tab.Settings -> SettingsScreen(
                            controller = controller,
                            onBack = { tab = Tab.Player },
                        )
                    }
                }
            }

            if (tab != Tab.Lyrics || !lyricsFullscreen) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OnyxBlack)
                        .navigationBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    BottomNav(
                        tab = tab,
                        onSelect = {
                            lyricsFullscreen = false
                            tab = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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

@Composable
private fun BottomNav(
    tab: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        strong = true,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(
                icon = Icons.Rounded.GraphicEq,
                label = "Плеер",
                selected = tab == Tab.Player,
                onClick = { onSelect(Tab.Player) },
            )
            NavItem(
                icon = Icons.Rounded.Translate,
                label = "Текст",
                selected = tab == Tab.Lyrics,
                onClick = { onSelect(Tab.Lyrics) },
            )
            NavItem(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                label = "Очередь",
                selected = tab == Tab.Queue,
                onClick = { onSelect(Tab.Queue) },
            )
            NavItem(
                icon = Icons.Rounded.Settings,
                label = "Настройки",
                selected = tab == Tab.Settings,
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
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val bg by animateColorAsState(
        targetValue = if (selected) OnyxWhite else Color.Transparent,
        animationSpec = tween(240),
        label = "navBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) Color(0xFF08080A) else OnyxDim,
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
        modifier = Modifier
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
            .padding(horizontal = 13.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = fg,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            color = fg,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = 0.4.sp,
        )
    }
}

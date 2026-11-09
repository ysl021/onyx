package app.onyx.ui

import android.content.Intent
import android.bluetooth.BluetoothManager
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.buds.BudsService
import app.onyx.buds.BudsState
import app.onyx.core.PrefsData
import app.onyx.media.AudioRoute
import app.onyx.media.MediaState
import app.onyx.media.NowPlaying
import app.onyx.spotify.QueueData
import app.onyx.ui.components.BatteryMeter
import app.onyx.ui.components.BlurredBackdrop
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.GlassSurface
import app.onyx.ui.components.OnyxSlider
import app.onyx.ui.components.ReorderableRow
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.components.rememberReorderState
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite
import coil.compose.AsyncImage
import androidx.media3.common.Player
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    controller: AppController,
    onOpenLyrics: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val media by controller.media.state.collectAsStateWithLifecycle()
    val nowPlaying by controller.nowPlaying.collectAsStateWithLifecycle()
    val buds by BudsService.state.collectAsStateWithLifecycle()
    val volume by controller.volume.state.collectAsStateWithLifecycle()
    val routes by controller.router.routes.collectAsStateWithLifecycle()
    val headphoneBattery by controller.headphones.batteryByName.collectAsStateWithLifecycle()
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = PrefsData())
    val scope = rememberCoroutineScope()

    val connected = prefs.spotifyRefresh.isNotBlank() || prefs.spotifyAccess.isNotBlank()
    val queue by controller.queue.collectAsStateWithLifecycle()
    val localPlayback by controller.localPlayer.state.collectAsStateWithLifecycle()
    val localQueueActive = localPlayback.current != null && localPlayback.queue.isNotEmpty()
    val localName = remember {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            null
        } else {
            runCatching {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.name
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }
    val remoteDevice = queue?.deviceName?.takeIf { name ->
        name.isNotBlank() &&
            localName != null &&
            !name.equals(localName, ignoreCase = true) &&
            !name.equals(Build.MODEL, ignoreCase = true)
    }

    LaunchedEffect(nowPlaying.mediaId, nowPlaying.title, nowPlaying.artist, connected) {
        if (connected) controller.refreshQueue() else controller.clearQueue()
    }

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 16.dp, bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            if (!media.hasAccess) {
                AccessBanner()
            }
            Hero(nowPlaying, onOpenLyrics)
            Controls(controller, media)
            if (remoteDevice != null) {
                RemotePlaybackBanner(remoteDevice)
            }
            OutputCard(
                routes = routes,
                volume = volume,
                onVolume = { controller.volume.set(it, prefs.volumeStep) },
                onSelect = { controller.router.select(it) },
                onOpenBluetooth = { controller.router.openBluetoothSettings() },
            )
            val activeHeadphone = routes.firstOrNull { it.selected && it.bluetooth }?.name
            BudsCard(
                buds = buds,
                activeHeadphone = activeHeadphone,
                activeBattery = activeHeadphone?.let { headphoneBattery[it] } ?: -1,
            )
            SleepTimerCard(controller = controller)
            if (localQueueActive) {
                LocalQueueCard(
                    controller = controller,
                    state = localPlayback,
                )
            } else {
                QueueCard(
                    connected = connected,
                    queue = queue,
                    onOpenSettings = onOpenSettings,
                    onPlay = { uri ->
                        scope.launch {
                            val ok = controller.spotify.playTrack(uri)
                            if (ok) controller.refreshQueue(force = true)
                            if (!ok) {
                                controller.toast(
                                    tr(
                                        "Spotify не разрешил запуск — проверь Premium и активное устройство",
                                        "Spotify refused playback — check Premium and the active device",
                                    ),
                                )
                            }
                        }
                    },
                    onRepeat = { state ->
                        scope.launch {
                            val ok = controller.spotify.setRepeat(state)
                            if (ok) {
                                controller.refreshQueue(force = true)
                            } else {
                                controller.toast(tr("Spotify не разрешил повтор — проверь Premium", "Spotify refused repeat — check Premium"))
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ONYX",
                color = OnyxWhite,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 6.sp,
            )
            Text(
                text = "music control center",
                color = OnyxFaint,
                fontSize = 9.sp,
                letterSpacing = 2.4.sp,
            )
        }
    }
}

@Composable
private fun AccessBanner() {
    val context = LocalContext.current
    GlassCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Text(
            text = tr("Нет доступа к медиа", "No media access"),
            color = OnyxWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = tr("Разреши ONYX доступ к уведомлениям — так приложение видит, что играет.", "Allow notification access so ONYX can see what is playing."),
            color = OnyxDim,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(12.dp))
        GlassPill(
            text = tr("Открыть доступ", "Open access"),
            selected = true,
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}

@Composable
private fun Hero(now: NowPlaying, onOpenLyrics: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(26.dp))
                .background(OnyxWhite.copy(alpha = 0.04f))
                .clickable { onOpenLyrics() },
            contentAlignment = Alignment.Center,
        ) {
            val model = now.art ?: now.artUri
            val drift = rememberInfiniteTransition(label = "cover").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(16_000),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "drift",
            )
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val d = drift.value
                            val s = 1.04f + 0.05f * d
                            scaleX = s
                            scaleY = s
                            translationX = (d - 0.5f) * 10f
                            translationY = (d - 0.5f) * 16f
                        },
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = OnyxFaint,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = now.title.ifBlank { tr("Ничего не играет", "Nothing playing") },
            color = OnyxWhite,
            fontSize = 25.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.92f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (now.artist.isBlank()) tr("Запусти музыку на телефоне", "Start music on your phone") else now.artist,
            color = OnyxDim,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.9f),
        )
        if (now.album.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = now.album,
                color = OnyxFaint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (now.hasTrack) {
            Spacer(Modifier.height(12.dp))
            GlassPill(
                text = tr("Текст песни", "Lyrics"),
                selected = false,
                icon = Icons.Rounded.Translate,
                onClick = onOpenLyrics,
            )
        }
    }
}

@Composable
private fun Controls(controller: AppController, media: MediaState) {
    val now = media.now
    val context = androidx.compose.ui.platform.LocalContext.current
    val localPlayback by controller.localPlayer.state.collectAsStateWithLifecycle()
    val localQueueActive = localPlayback.current != null && localPlayback.queue.isNotEmpty()
    var drag by remember { mutableStateOf<Float?>(null) }
    var lastSeek by remember { mutableLongStateOf(0L) }

    val duration = now.durationMs.coerceAtLeast(1L)
    val shown = drag?.let { (it * duration).toLong() } ?: media.positionMs.coerceIn(0L, duration)
    val fraction = (shown.toFloat() / duration).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth()) {
        OnyxSlider(
            value = drag ?: fraction,
            onValueChange = { drag = it },
            enabled = now.hasTrack && now.canSeek,
            modifier = Modifier.fillMaxWidth(),
            onValueChangeFinished = {
                val target = ((drag ?: fraction) * duration).toLong()
                drag = null
                lastSeek = target
                controller.media.seekTo(target)
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = fmt(if (drag != null) shown else media.positionMs),
                color = OnyxFaint,
                fontSize = 11.sp,
                letterSpacing = 0.6.sp,
            )
            Text(
                text = "-" + fmt((duration - shown).coerceAtLeast(0L)),
                color = OnyxFaint,
                fontSize = 11.sp,
                letterSpacing = 0.6.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleButton(
                icon = Icons.Rounded.SkipPrevious,
                contentDescription = tr("Предыдущий трек", "Previous track"),
                size = 54.dp,
                enabled = now.hasTrack,
                onClick = {
                    if (localQueueActive && now.packageName == context.packageName) {
                        controller.localPlayer.previous()
                    } else {
                        controller.media.previous()
                    }
                },
            )
            Spacer(Modifier.width(22.dp))
            CircleButton(
                icon = if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (now.isPlaying) tr("Пауза", "Pause") else tr("Играть", "Play"),
                size = 74.dp,
                filled = true,
                iconSize = 32.dp,
                enabled = now.hasTrack,
                onClick = {
                    if (localQueueActive && now.packageName == context.packageName) {
                        controller.localPlayer.toggle()
                    } else {
                        controller.media.playPause()
                    }
                },
            )
            Spacer(Modifier.width(22.dp))
            CircleButton(
                icon = Icons.Rounded.SkipNext,
                contentDescription = tr("Следующий трек", "Next track"),
                size = 54.dp,
                enabled = now.hasTrack,
                onClick = {
                    if (localQueueActive && now.packageName == context.packageName) {
                        controller.localPlayer.next()
                    } else {
                        controller.media.next()
                    }
                },
            )
        }
    }
}

@Composable
private fun OutputCard(
    routes: List<AudioRoute>,
    volume: app.onyx.audio.VolumeState,
    onVolume: (Float) -> Unit,
    onSelect: (String) -> Unit,
    onOpenBluetooth: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(tr("Вывод звука", "Audio output"), Modifier.weight(1f))
            if (routes.isEmpty()) {
                Text(
                    text = tr("открыть Bluetooth", "open Bluetooth"),
                    color = OnyxDim,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { onOpenBluetooth() },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (routes.isEmpty()) {
            Text(
                text = tr("Устройства не найдены. Проверь, что наушники подключены.", "No devices found. Check that your headphones are connected."),
                color = OnyxDim,
                fontSize = 13.sp,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                routes.forEach { route ->
                    GlassPill(
                        text = if (route.phone) tr("Телефон", "Phone") else route.name,
                        selected = route.selected,
                        icon = if (route.phone) Icons.Rounded.Smartphone else Icons.Rounded.Bluetooth,
                        onClick = { onSelect(route.id) },
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Громкость", "Volume"),
                color = OnyxDim,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${volume.percent}%",
                color = OnyxWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        OnyxSlider(
            value = volume.fraction,
            onValueChange = onVolume,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RemotePlaybackBanner(device: String) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Bluetooth,
                contentDescription = null,
                tint = OnyxWhite,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = tr("Играет на другом устройстве", "Playing on another device"),
                    color = OnyxWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = tr("$device · управление с этого телефона может быть недоступно", "$device · controls from this phone may be unavailable"),
                    color = OnyxFaint,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun SleepTimerCard(controller: AppController) {
    val remainingMs by controller.sleepRemainingMs.collectAsStateWithLifecycle()
    val active = remainingMs > 0L
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(tr("Таймер сна", "Sleep timer"), Modifier.weight(1f))
            Icon(
                Icons.Rounded.Bedtime,
                contentDescription = null,
                tint = if (active) OnyxWhite else OnyxFaint,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        if (active) {
            Text(
                formatRemaining(remainingMs),
                color = OnyxWhite,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                tr("Музыка встанет на паузу по окончании", "Music pauses when it ends"),
                color = OnyxFaint,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))
            GlassPill(
                text = tr("Отключить", "Turn off"),
                selected = false,
                onClick = { controller.cancelSleepTimer() },
            )
        } else {
            Text(
                tr("Минуты до автоматической паузы:", "Minutes until playback pauses:"),
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(15, 30, 45, 60).forEach { mins ->
                    GlassPill(
                        text = "$mins",
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = { controller.startSleepTimer(mins) },
                    )
                }
            }
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val total = (ms + 999) / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun BudsCard(
    buds: BudsState,
    activeHeadphone: String? = null,
    activeBattery: Int = -1,
) {
    val context = LocalContext.current
    val oneEarAllowed = buds.wearingL != buds.wearingR
    LaunchedEffect(buds.connected, oneEarAllowed, buds.oneEar) {
        if (buds.connected && !oneEarAllowed && buds.oneEar) {
            BudsService.setOneEar(context, false)
        }
    }
    val otherHeadphone = activeHeadphone?.takeIf { name ->
        name.isNotBlank() && !name.contains("buds", ignoreCase = true)
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(tr("Наушники", "Headphones"), Modifier.weight(1f))
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = tr("Переподключить", "Reconnect"),
                tint = OnyxDim,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { BudsService.reconnect(context) },
            )
        }
        Spacer(Modifier.height(6.dp))

        if (otherHeadphone != null && !buds.connected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Headphones,
                    contentDescription = null,
                    tint = OnyxWhite,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = otherHeadphone,
                        color = OnyxWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(tr("Подключено · базовое управление", "Connected · basic controls"))
                            if (activeBattery >= 0) append(" · $activeBattery%")
                        },
                        color = OnyxFaint,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = tr("Плеер, громкость и переключение вывода работают для любых наушников. " +
                    "ANC и расширенное управление доступны только для Galaxy Buds.", "Player, volume and output switching work with any headphones. ANC and advanced controls are Galaxy Buds only."),
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = tr("Подключить Buds", "Connect Buds"),
                    selected = false,
                    onClick = { BudsService.reconnect(context) },
                )
                GlassPill(
                    text = tr("Настройки BT", "BT settings"),
                    selected = false,
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = tr("Galaxy Buds: нажми ↻, чтобы открыть SPP-канал и включить ANC.", "Galaxy Buds: tap ↻ to open the SPP channel and enable ANC."),
                color = OnyxFaint,
                fontSize = 11.sp,
            )
            return@GlassCard
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Headphones,
                contentDescription = null,
                tint = if (buds.connected) OnyxWhite else OnyxFaint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    buds.connected -> buds.name
                    buds.connecting -> tr("Подключение…", "Connecting…")
                    else -> buds.error ?: tr("Не подключены", "Not connected")
                },
                color = if (buds.connected) OnyxWhite else OnyxDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!buds.connected) {
            Spacer(Modifier.height(12.dp))
            GlassPill(
                text = if (buds.connecting) tr("Подключаемся…", "Connecting…") else tr("Подключить Buds", "Connect Buds"),
                selected = true,
                enabled = !buds.connecting,
                onClick = { BudsService.reconnect(context) },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = tr("Автоподключение отключено: ONYX не будет пытаться сам, " +
                    "пока ты не нажмёшь кнопку.", "Auto-connect is off: ONYX will not retry until you press the button."),
                color = OnyxFaint,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            return@GlassCard
        }

        if (buds.batteryKnown || buds.batteryCase >= 0) {
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BatteryMeter("L", buds.batteryL, buds.wearingL, Modifier.weight(1f))
                BatteryMeter("R", buds.batteryR, buds.wearingR, Modifier.weight(1f))
                BatteryMeter(tr("КЕЙС", "CASE"), buds.batteryCase, false, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel(tr("Шумоподавление", "Noise control"))
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassPill(
                text = tr("Выкл", "Off"),
                selected = buds.mode == BudsState.MODE_OFF,
                onClick = { BudsService.setMode(context, BudsState.MODE_OFF) },
            )
            GlassPill(
                text = "ANC",
                selected = buds.mode == BudsState.MODE_ANC,
                onClick = { BudsService.setMode(context, BudsState.MODE_ANC) },
            )
            GlassPill(
                text = "Ambient",
                selected = buds.mode == BudsState.MODE_AMBIENT,
                onClick = { BudsService.setMode(context, BudsState.MODE_AMBIENT) },
            )
            GlassPill(
                text = "Adaptive",
                selected = buds.mode == BudsState.MODE_ADAPTIVE,
                onClick = { BudsService.setMode(context, BudsState.MODE_ADAPTIVE) },
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill(
                text = tr("ANC на одно ухо", "ANC one ear"),
                selected = buds.oneEar,
                enabled = oneEarAllowed,
                onClick = { BudsService.setOneEar(context, !buds.oneEar) },
            )
        }
        if (buds.connected && !oneEarAllowed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = tr(
                    "Режим одного уха доступен, когда в ухе ровно один наушник.",
                    "One-ear mode is available when exactly one earbud is worn.",
                ),
                color = OnyxFaint,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun LocalQueueCard(
    controller: AppController,
    state: app.onyx.music.LocalPlaybackState,
) {
    val player = controller.localPlayer
    val rowHeight = 50.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(tr("Дальше в очереди", "Up next"), Modifier.weight(1f))
            Text(
                text = "ONYX",
                color = OnyxFaint,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            GlassPill(
                text = tr("Повтор", "Repeat"),
                selected = state.repeatMode != Player.REPEAT_MODE_OFF,
                modifier = Modifier.weight(1f),
                onClick = {
                    player.setRepeatMode(
                        when (state.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        },
                    )
                },
            )
            GlassPill(
                text = tr("Перемешать", "Shuffle"),
                selected = state.shuffle,
                modifier = Modifier.weight(1f),
                onClick = { player.setShuffle(!state.shuffle) },
            )
        }
        Spacer(Modifier.height(12.dp))

        val rows = state.queue.take(50)
        val reorder = rememberReorderState(rows.size, rowHeightPx)

        Column {
            rows.forEachIndexed { index, track ->
                val isCurrent = index == state.currentIndex
                ReorderableRow(
                    index = index,
                    state = reorder,
                    rowHeight = rowHeight,
                    onTap = { player.playAt(index) },
                    onMove = { from, to -> player.move(from, to) },
                ) {
                    val dragged = reorder.active && reorder.from == index
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    dragged || isCurrent -> OnyxWhite.copy(alpha = 0.07f)
                                    else -> Color.Transparent
                                },
                            )
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = (index + 1).toString().padStart(2, '0'),
                            color = OnyxFaint,
                            fontSize = 10.sp,
                            modifier = Modifier.width(24.dp),
                        )
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(OnyxWhite.copy(alpha = 0.05f)),
                        ) {
                            val art = track.artPath?.let { java.io.File(it) }
                            if (art != null) {
                                AsyncImage(
                                    model = art,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = OnyxWhite,
                                fontSize = 13.sp,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = track.artist.ifBlank { track.album },
                                color = OnyxFaint,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(text = fmt(track.durationMs), color = OnyxFaint, fontSize = 10.sp)
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = tr("Удалить из очереди", "Remove from queue"),
                            tint = if (isCurrent || dragged) Color.Transparent else OnyxFaint,
                            modifier = Modifier
                                .size(16.dp)
                                .then(
                                    if (isCurrent || dragged) Modifier
                                    else Modifier.clickable { player.removeAt(index) },
                                ),
                        )
                    }
                }
            }
        }
        if (state.queue.size > rows.size) {
            Spacer(Modifier.height(6.dp))
            Text(
                tr("+${state.queue.size - rows.size} треки дальше…", "+${state.queue.size - rows.size} more tracks…"),
                color = OnyxFaint,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun QueueCard(
    connected: Boolean,
    queue: QueueData?,
    onOpenSettings: () -> Unit,
    onPlay: (String) -> Unit,
    onRepeat: (String) -> Unit,
) {
    if (!connected) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Spotify", Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = OnyxFaint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = tr("Подключи аккаунт, чтобы видеть очередь, повтор и обложки.", "Connect an account to see the queue, repeat and artwork."),
                color = OnyxDim,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            GlassPill(text = tr("Подключить Spotify", "Connect Spotify"), selected = true, onClick = onOpenSettings)
        }
        return
    }

    val data = queue ?: return
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(tr("Дальше в очереди", "Up next"), Modifier.weight(1f))
            Text(
                text = data.deviceName.ifBlank { "Spotify" },
                color = OnyxFaint,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            GlassPill(
                text = tr("Выкл", "Off"),
                selected = data.repeatState == "off",
                modifier = Modifier.weight(1f),
                onClick = { onRepeat("off") },
            )
            GlassPill(
                text = tr("Плейлист", "Playlist"),
                selected = data.repeatState == "context",
                modifier = Modifier.weight(1f),
                onClick = { onRepeat("context") },
            )
            GlassPill(
                text = tr("Трек", "Track"),
                selected = data.repeatState == "track",
                modifier = Modifier.weight(1f),
                onClick = { onRepeat("track") },
            )
        }
        Spacer(Modifier.height(12.dp))
        if (data.next.isEmpty()) {
            Text(tr("Очередь пуста", "Queue is empty"), color = OnyxFaint, fontSize = 12.sp)
            return@GlassCard
        }
        data.next.forEachIndexed { index, track ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPlay(track.uri) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (index + 1).toString().padStart(2, '0'),
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    modifier = Modifier.width(24.dp),
                )
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(OnyxWhite.copy(alpha = 0.05f)),
                ) {
                    if (track.art != null) {
                        AsyncImage(
                            model = track.art,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = OnyxWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        color = OnyxFaint,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(text = fmt(track.durationMs), color = OnyxFaint, fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = OnyxFaint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

internal fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

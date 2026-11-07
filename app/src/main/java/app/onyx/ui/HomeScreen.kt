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
import app.onyx.spotify.QueueData
import app.onyx.ui.components.BatteryMeter
import app.onyx.ui.components.BlurredBackdrop
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.GlassSurface
import app.onyx.ui.components.OnyxSlider
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    controller: AppController,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val media by controller.media.state.collectAsStateWithLifecycle()
    val buds by BudsService.state.collectAsStateWithLifecycle()
    val routes by controller.router.routes.collectAsStateWithLifecycle()
    val headphoneBattery by controller.headphones.batteryByName.collectAsStateWithLifecycle()
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = PrefsData())
    val scope = rememberCoroutineScope()

    val connected = prefs.spotifyRefresh.isNotBlank() || prefs.spotifyAccess.isNotBlank()
    var queue by remember { mutableStateOf<QueueData?>(null) }
    var queueVersion by remember { mutableStateOf(0) }
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

    LaunchedEffect(media.now.mediaId, media.now.title, media.now.artist, connected, queueVersion) {
        queue = if (connected) runCatching { controller.spotify.queue() }.getOrNull() else null
    }

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(onOpenSettings)
            if (!media.hasAccess) {
                AccessBanner()
            }
            Hero(media, onOpenLyrics)
            Controls(controller, media)
            if (remoteDevice != null) {
                RemotePlaybackBanner(remoteDevice)
            }
            OutputCard(
                routes = routes,
                onSelect = { controller.router.select(it) },
                onOpenBluetooth = { controller.router.openBluetoothSettings() },
            )
            val activeHeadphone = routes.firstOrNull { it.selected && it.bluetooth }?.name
            BudsCard(
                buds = buds,
                activeHeadphone = activeHeadphone,
                activeBattery = activeHeadphone?.let { headphoneBattery[it] } ?: -1,
            )
            QueueCard(
                connected = connected,
                queue = queue,
                onOpenSettings = onOpenSettings,
                onOpenQueue = onOpenQueue,
                onRepeat = { state ->
                    scope.launch {
                        val ok = controller.spotify.setRepeat(state)
                        if (ok) {
                            queueVersion++
                        } else {
                            controller.toast("Spotify не разрешил повтор — проверь Premium")
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
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
        CircleButton(
            icon = Icons.Rounded.Settings,
            contentDescription = "Настройки",
            size = 42.dp,
            iconSize = 19.dp,
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun AccessBanner() {
    val context = LocalContext.current
    GlassCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Text(
            text = "Нет доступа к медиа",
            color = OnyxWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Разреши ONYX доступ к уведомлениям — так приложение видит, что играет.",
            color = OnyxDim,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(12.dp))
        GlassPill(
            text = "Открыть доступ",
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
private fun Hero(media: MediaState, onOpenLyrics: () -> Unit) {
    val now = media.now
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
            text = now.title.ifBlank { "Ничего не играет" },
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
            text = if (now.artist.isBlank()) "Запусти музыку на телефоне" else now.artist,
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
                text = "Текст песни",
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
                contentDescription = "Предыдущий трек",
                size = 54.dp,
                enabled = now.hasTrack,
                onClick = { controller.media.previous() },
            )
            Spacer(Modifier.width(22.dp))
            CircleButton(
                icon = if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (now.isPlaying) "Пауза" else "Играть",
                size = 74.dp,
                filled = true,
                iconSize = 32.dp,
                enabled = now.hasTrack,
                onClick = { controller.media.playPause() },
            )
            Spacer(Modifier.width(22.dp))
            CircleButton(
                icon = Icons.Rounded.SkipNext,
                contentDescription = "Следующий трек",
                size = 54.dp,
                enabled = now.hasTrack,
                onClick = { controller.media.next() },
            )
        }
    }
}

@Composable
private fun OutputCard(
    routes: List<AudioRoute>,
    onSelect: (String) -> Unit,
    onOpenBluetooth: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Вывод звука", Modifier.weight(1f))
            if (routes.isEmpty()) {
                Text(
                    text = "открыть Bluetooth",
                    color = OnyxDim,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { onOpenBluetooth() },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (routes.isEmpty()) {
            Text(
                text = "Устройства не найдены. Проверь, что наушники подключены.",
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
                        text = route.name,
                        selected = route.selected,
                        icon = if (route.phone) Icons.Rounded.Smartphone else Icons.Rounded.Bluetooth,
                        onClick = { onSelect(route.id) },
                    )
                }
            }
        }
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
                    text = "Играет на другом устройстве",
                    color = OnyxWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "$device · управление с этого телефона может быть недоступно",
                    color = OnyxFaint,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun BudsCard(
    buds: BudsState,
    activeHeadphone: String? = null,
    activeBattery: Int = -1,
) {
    val context = LocalContext.current
    val otherHeadphone = activeHeadphone?.takeIf { name ->
        name.isNotBlank() && !name.contains("buds", ignoreCase = true)
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Наушники", Modifier.weight(1f))
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = "Переподключить",
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
                            append("Подключено · базовое управление")
                            if (activeBattery >= 0) append(" · $activeBattery%")
                        },
                        color = OnyxFaint,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Плеер, громкость и переключение вывода работают для любых наушников. " +
                    "ANC и расширенное управление доступны только для Galaxy Buds.",
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = "Подключить Buds",
                    selected = false,
                    onClick = { BudsService.reconnect(context) },
                )
                GlassPill(
                    text = "Настройки BT",
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
                text = "Galaxy Buds: нажми ↻, чтобы открыть SPP-канал и включить ANC.",
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
                    buds.connecting -> "Подключение…"
                    else -> buds.error ?: "Не подключены"
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
                text = if (buds.connecting) "Подключаемся…" else "Подключить Buds",
                selected = true,
                enabled = !buds.connecting,
                onClick = { BudsService.reconnect(context) },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Автоподключение отключено: ONYX не будет пытаться сам, " +
                    "пока ты не нажмёшь кнопку.",
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
                BatteryMeter("КЕЙС", buds.batteryCase, false, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("Шумоподавление")
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassPill(
                text = "Выкл",
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
                text = "ANC на одно ухо",
                selected = buds.oneEar,
                onClick = { BudsService.setOneEar(context, !buds.oneEar) },
            )
            GlassPill(
                text = "ANC максимум",
                selected = buds.ancHigh,
                onClick = { BudsService.setAncHigh(context, !buds.ancHigh) },
            )
        }
    }
}

@Composable
private fun QueueCard(
    connected: Boolean,
    queue: QueueData?,
    onOpenSettings: () -> Unit,
    onOpenQueue: () -> Unit,
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
                text = "Подключи аккаунт, чтобы видеть очередь, повтор и обложки.",
                color = OnyxDim,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            GlassPill(text = "Подключить Spotify", selected = true, onClick = onOpenSettings)
        }
        return
    }

    val data = queue ?: return
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Дальше в очереди", Modifier.weight(1f))
            Text(
                text = data.deviceName.ifBlank { "Spotify" },
                color = OnyxFaint,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill(
                text = "Выкл",
                selected = data.repeatState == "off",
                onClick = { onRepeat("off") },
            )
            GlassPill(
                text = "Плейлист",
                selected = data.repeatState == "context",
                onClick = { onRepeat("context") },
            )
            GlassPill(
                text = "Трек",
                selected = data.repeatState == "track",
                onClick = { onRepeat("track") },
            )
        }
        Spacer(Modifier.height(10.dp))
        GlassPill(
            text = "Вся очередь",
            selected = false,
            onClick = onOpenQueue,
        )
        Spacer(Modifier.height(12.dp))
        if (data.next.isEmpty()) {
            Text("Очередь пуста", color = OnyxFaint, fontSize = 12.sp)
            return@GlassCard
        }
        data.next.take(5).forEachIndexed { index, track ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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

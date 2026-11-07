package app.onyx.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.buds.BudsService
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.OnyxSlider
import app.onyx.ui.components.OnyxSwitch
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite
import kotlinx.coroutines.launch

private const val REDIRECT = "http://127.0.0.1:8899/callback"

@Composable
fun SettingsScreen(
    controller: AppController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = app.onyx.core.PrefsData())
    val scope = rememberCoroutineScope()

    var clientId by remember { mutableStateOf(prefs.spotifyClientId) }
    var fontSize by remember { mutableFloatStateOf(prefs.lyricFontSize) }
    var section by rememberSaveable { mutableStateOf(0) }
    var showEq by rememberSaveable { mutableStateOf(false) }
    var offset by remember { mutableFloatStateOf(prefs.lyricOffsetMs.toFloat()) }
    var listenerOn by remember { mutableStateOf(isListenerEnabled(context)) }
    var batteryFree by remember { mutableStateOf(isBatteryFree(context)) }
    val loginState by controller.spotify.loginState.collectAsStateWithLifecycle()
    val busy = loginState is app.onyx.spotify.SpotifyLoginState.Waiting
    val loginError = (loginState as? app.onyx.spotify.SpotifyLoginState.Failed)?.message

    LaunchedEffect(loginState) {
        when (val s = loginState) {
            is app.onyx.spotify.SpotifyLoginState.Connected -> controller.toast("Spotify: ${s.name}")
            is app.onyx.spotify.SpotifyLoginState.Failed -> controller.toast(s.message)
            else -> Unit
        }
    }

    LaunchedEffect(prefs.spotifyClientId) {
        if (clientId.isBlank()) clientId = prefs.spotifyClientId
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        listenerOn = isListenerEnabled(context)
        batteryFree = isBatteryFree(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            controller.toast("Разрешения выданы")
        } else {
            controller.toast("Не все разрешения выданы")
        }
    }

    val musicFolderLauncher = rememberLauncherForActivityResult(
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
                .getOrNull() ?: uri.lastPathSegment ?: "Папка"
            controller.localLibrary.setFolder(uri, name)
            controller.toast("Папка сохранена")
        }
    }

    val connected = prefs.spotifyRefresh.isNotBlank() || prefs.spotifyAccess.isNotBlank()

    if (showEq) {
        EqualizerScreen(
            controller = controller,
            onBack = { showEq = false },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Назад",
                size = 42.dp,
                iconSize = 20.dp,
                onClick = onBack,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Настройки",
                    color = OnyxWhite,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                )
                Text(
                    text = "ONYX · v0.3",
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    letterSpacing = 1.4.sp,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            GlassPill(
                text = "Основные",
                selected = section == 0,
                onClick = { section = 0 },
            )
            GlassPill(
                text = "Внешний вид",
                selected = section == 1,
                onClick = { section = 1 },
            )
        }

        if (section == 0) {
        // ------------------------------------------------------------- Spotify
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Spotify")
            Spacer(Modifier.height(10.dp))
            if (connected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = OnyxWhite,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = prefs.spotifyUser.ifBlank { "Аккаунт подключён" },
                            color = OnyxWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Очередь и повтор доступны",
                            color = OnyxFaint,
                            fontSize = 11.sp,
                        )
                        Text(
                            text = "Управление воспроизведением требует активной Premium-подписки.",
                            color = OnyxFaint,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = "Отключить",
                        tint = OnyxDim,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable {
                                scope.launch {
                                    controller.prefs.clearSpotify()
                                    controller.toast("Spotify отключён")
                                }
                            },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Client ID: ${prefs.spotifyClientId}",
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = "Приложение работает с твоим личным Spotify-приложением. " +
                        "Создай его в Spotify Dashboard и вставь Client ID.",
                    color = OnyxDim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Client ID", fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = OnyxWhite,
                        fontSize = 14.sp,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OnyxWhite.copy(alpha = 0.45f),
                        unfocusedBorderColor = OnyxLine,
                        focusedLabelColor = OnyxDim,
                        unfocusedLabelColor = OnyxFaint,
                        cursorColor = OnyxWhite,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                RedirectBox { controller.toast("Redirect URI скопирован") }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(
                        text = "Сохранить",
                        selected = false,
                        enabled = clientId.isNotBlank(),
                        onClick = {
                            scope.launch {
                                controller.prefs.setClientId(clientId)
                                controller.toast("Client ID сохранён")
                            }
                        },
                    )
                    GlassPill(
                        text = if (busy) "Ждём браузер…" else "Войти",
                        selected = true,
                        enabled = !busy && clientId.isNotBlank(),
                        onClick = {
                            scope.launch {
                                controller.prefs.setClientId(clientId)
                                controller.spotify.loginAsync(context)
                            }
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (loginError != null) {
                    Text(
                        text = loginError,
                        color = Color(0xFFFF8A5C),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = "Redirect URI в Dashboard: $REDIRECT\n" +
                        "Scopes: user-read-playback-state, user-modify-playback-state, " +
                        "user-read-currently-playing",
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
            }
        }

        // -------------------------------------------------------------- Lyrics
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Текст и караоке")
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Spicy Lyrics · общинная база TTML. Отдаёт текст и тайминги " +
                    "даже для ремиксов и slowed-версий, которых нет в Musixmatch.",
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Ключ Spicy Lyrics встроен в сборку. Если источник недоступен, " +
                    "ONYX автоматически перейдёт к Musixmatch, Genius и LRCLIB.",
                color = OnyxFaint,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(18.dp))
            ToggleRow(
                title = "Караоке по словам",
                subtitle = "Плавная подсветка слов, если провайдер отдал тайминги",
                checked = prefs.karaoke,
                onChange = { scope.launch { controller.prefs.setKaraoke(it) } },
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = "Русский перевод",
                subtitle = "Spicy/Musixmatch, затем Google",
                checked = prefs.translate,
                onChange = { scope.launch { controller.prefs.setTranslate(it) } },
            )
            Spacer(Modifier.height(18.dp))
            SliderRow(
                title = "Размер текста",
                value = "${fontSize.toInt()}",
                sliderValue = (fontSize - 20f) / 28f,
                onChange = { fontSize = 20f + it * 28f },
                onFinish = { scope.launch { controller.prefs.setFontSize(fontSize) } },
            )
            Spacer(Modifier.height(10.dp))
            SliderRow(
                title = "Смещение текста",
                value = "${(offset / 100).toInt() * 100} мс",
                sliderValue = (offset + 3000f) / 6000f,
                onChange = { offset = -3000f + it * 6000f },
                onFinish = {
                    val snapped = (offset / 100).toInt() * 100L
                    scope.launch { controller.prefs.setOffset(snapped) }
                },
            )
        }

        // ---------------------------------------------------------------- Buds
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Наушники")
            Spacer(Modifier.height(12.dp))
            Text(
                text = "ONYX управляет Galaxy Buds по служебному SPP-каналу Samsung. " +
                    "Другие Bluetooth-наушники работают как обычный аудиовыход: " +
                    "плеер, громкость и переключение маршрута. Автоподключения к Buds нет — " +
                    "только по кнопке.",
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = "Переподключить",
                    selected = false,
                    onClick = { BudsService.reconnect(context) },
                )
                GlassPill(
                    text = "Открыть Bluetooth",
                    selected = false,
                    onClick = { controller.router.openBluetoothSettings() },
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Для стабильного SPP на Samsung отключи менеджер (один раз, через adb):\n" +
                    "adb shell pm disable-user --user 0 com.samsung.accessory.paranmgr",
                color = OnyxFaint,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
        }

        // ------------------------------------------------------------- System
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Доступы")
            Spacer(Modifier.height(12.dp))
            StatusRow(
                title = "Доступ к уведомлениям",
                ok = listenerOn,
                action = "Открыть",
                onAction = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            StatusRow(
                title = "Bluetooth-разрешения",
                ok = hasBluetooth(context),
                action = "Выдать",
                onAction = {
                    val perms = buildList {
                        add(android.Manifest.permission.BLUETOOTH_CONNECT)
                        add(android.Manifest.permission.BLUETOOTH_SCAN)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            add(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    permissionLauncher.launch(perms.toTypedArray())
                },
            )
            Spacer(Modifier.height(12.dp))
            StatusRow(
                title = "Без оптимизации батареи",
                ok = batteryFree,
                action = "Открыть",
                onAction = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Локальная музыка")
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (prefs.localFolderName.isBlank()) "Папка не выбрана"
                else "Папка: ${prefs.localFolderName}",
                color = if (prefs.localFolderName.isBlank()) OnyxFaint else OnyxWhite,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = if (prefs.localFolderName.isBlank()) "Выбрать папку" else "Сменить папку",
                    selected = true,
                    onClick = { musicFolderLauncher.launch(null) },
                )
                if (prefs.localFolderUri.isNotBlank()) {
                    GlassPill(
                        text = "Очистить",
                        selected = false,
                        onClick = { controller.localLibrary.clearFolder() },
                    )
                }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Звук")
            Spacer(Modifier.height(10.dp))
            Text(
                "Эквалайзер с пресетами и ручной настройкой полос. " +
                    "Работает через общий аудиовыход; на части Bluetooth-устройств эффект может быть недоступен.",
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            GlassPill(
                text = "Открыть эквалайзер",
                selected = true,
                onClick = { showEq = true },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Двойное прослушивание: публичный Android API не даёт приложению два независимых " +
                    "медиавыхода. На Samsung используй «Dual audio» в Sound Assistant; ONYX управляет " +
                    "активным маршрутом и общим эквалайзером.",
                color = OnyxFaint,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("О приложении")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "ONYX — личный пульт управления музыкой. " +
                    "Медиа, Bluetooth-маршруты, Galaxy Buds и другие наушники, Spotify и тексты " +
                    "Spicy Lyrics в одном месте. Протокол Buds портирован из GalaxyBudsClient (GPLv3).",
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        } else {
            AppearanceSection(controller, prefs, scope)
        }
    }
}

@Composable
private fun AppearanceSection(
    controller: AppController,
    prefs: app.onyx.core.PrefsData,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("Тема")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill(
                text = "Тёмная",
                selected = prefs.themeMode == app.onyx.core.ThemeMode.DARK,
                onClick = { scope.launch { controller.prefs.setThemeMode(app.onyx.core.ThemeMode.DARK) } },
            )
            GlassPill(
                text = "Светлая",
                selected = prefs.themeMode == app.onyx.core.ThemeMode.LIGHT,
                onClick = { scope.launch { controller.prefs.setThemeMode(app.onyx.core.ThemeMode.LIGHT) } },
            )
            GlassPill(
                text = "Система",
                selected = prefs.themeMode == app.onyx.core.ThemeMode.SYSTEM,
                onClick = { scope.launch { controller.prefs.setThemeMode(app.onyx.core.ThemeMode.SYSTEM) } },
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Светлая тема меняет фон, стекло и цвет текста. Переключение анимировано.",
            color = OnyxFaint,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("Фон")
        Spacer(Modifier.height(12.dp))
        ToggleRow(
            title = "Фон по обложке",
            subtitle = "Размытая обложка и цветовой оттенок на всех экранах",
            checked = prefs.coverBackdrop,
            onChange = { scope.launch { controller.prefs.setCoverBackdrop(it) } },
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = OnyxWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = OnyxFaint, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(10.dp))
        OnyxSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: String,
    sliderValue: Float,
    onChange: (Float) -> Unit,
    onFinish: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = OnyxDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(value, color = OnyxWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        OnyxSlider(
            value = sliderValue.coerceIn(0f, 1f),
            onValueChange = onChange,
            onValueChangeFinished = onFinish,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatusRow(
    title: String,
    ok: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(if (ok) OnyxWhite else Color(0xFFFF8A5C)),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = title,
            color = if (ok) OnyxWhite else OnyxDim,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (!ok) {
            Text(
                text = action,
                color = OnyxWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(OnyxWhite.copy(alpha = 0.08f))
                    .clickable { onAction() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        } else {
            Text("готово", color = OnyxFaint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RedirectBox(onCopied: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OnyxWhite.copy(alpha = 0.05f))
            .clickable {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("redirect", REDIRECT))
                onCopied()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = null,
            tint = OnyxDim,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = REDIRECT,
            color = OnyxDim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun isListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun hasBluetooth(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun isBatteryFree(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

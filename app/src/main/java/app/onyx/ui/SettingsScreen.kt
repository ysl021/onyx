package app.onyx.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.painterResource
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
import app.onyx.R
import app.onyx.buds.BudsService
import app.onyx.core.CustomTheme
import app.onyx.core.CustomThemeCodec
import app.onyx.core.DiscordConfig
import app.onyx.core.Http
import app.onyx.core.IconSwitcher
import app.onyx.core.OnyxBackup
import app.onyx.core.buildBackup
import app.onyx.core.readBackup
import app.onyx.core.writeBackup
import app.onyx.music.PlaylistsState
import app.onyx.sync.SyncResult
import app.onyx.update.UpdateState
import app.onyx.ui.components.CircleButton
import app.onyx.ui.components.CustomBackdrop
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.OnyxSlider
import app.onyx.ui.components.OnyxSwitch
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.LocalOnyx
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxSurface
import app.onyx.ui.theme.OnyxWhite
import app.onyx.ui.theme.toOnyxColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private const val REDIRECT = "http://127.0.0.1:8899/callback"

@Composable
fun SettingsScreen(
    controller: AppController,
    onBack: () -> Unit,
    onOpenHealth: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = app.onyx.core.PrefsData())
    val scope = rememberCoroutineScope()

    var clientId by remember { mutableStateOf(prefs.spotifyClientId) }
    var fontSize by remember { mutableFloatStateOf(prefs.lyricFontSize) }
    var section by rememberSaveable { mutableStateOf(0) }
    var bridgeHost by remember(prefs.discordBridgeHost) { mutableStateOf(prefs.discordBridgeHost) }
    var bridgePort by remember(prefs.discordBridgePort) {
        mutableStateOf(prefs.discordBridgePort.toString())
    }
    var bridgeStatus by remember { mutableStateOf("") }
    var transferStatus by remember { mutableStateOf("") }
    var discovered by remember { mutableStateOf<List<app.onyx.core.DiscoveredPc>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf(false) }
    var pairTarget by remember { mutableStateOf<app.onyx.core.DiscoveredPc?>(null) }
    var pairCodeInput by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var showEq by rememberSaveable { mutableStateOf(false) }
    var pendingBgPage by remember { mutableStateOf<String?>(null) }
    var previewBgPage by remember { mutableStateOf<String?>(null) }
    var previewBgUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var previewAll by remember { mutableStateOf(false) }
    var offset by remember { mutableFloatStateOf(prefs.lyricOffsetMs.toFloat()) }
    var listenerOn by remember { mutableStateOf(isListenerEnabled(context)) }
    var batteryFree by remember { mutableStateOf(isBatteryFree(context)) }
    var installAllowed by remember { mutableStateOf(controller.updates.canInstall()) }
    var pendingImport by remember { mutableStateOf<OnyxBackup?>(null) }
    val updateState by controller.updates.state.collectAsStateWithLifecycle()
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
        installAllowed = controller.updates.canInstall()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            controller.toast(tr("Разрешения выданы", "Permissions granted"))
        } else {
            controller.toast(tr("Не все разрешения выданы", "Not all permissions granted"))
        }
    }

    val musicFolderLauncher = rememberLauncherForActivityResult(
        OpenFolderTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
                .getOrNull() ?: uri.lastPathSegment ?: tr("Папка", "Folder")
            controller.localLibrary.addFolder(uri, name)
            controller.toast(tr("Папка сохранена", "Folder saved"))
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val snapshot = controller.prefs.snapshot()
                    val playlists = controller.localPlaylists.snapshot()
                    val backup = buildBackup(
                        snapshot,
                        playlists,
                        app.onyx.BuildConfig.VERSION_NAME,
                        app.onyx.BuildConfig.VERSION_CODE,
                    )
                    writeBackup(context, uri, backup)
                }.onSuccess {
                    controller.toast(tr("Данные экспортированы", "Data exported"))
                }.onFailure {
                    controller.toast(
                        tr("Ошибка экспорта: ${it.message}", "Export failed: ${it.message}"),
                    )
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { readBackup(context, uri) }
                    .onSuccess { pendingImport = it }
                    .onFailure {
                        controller.toast(
                            tr("Ошибка импорта: ${it.message}", "Import failed: ${it.message}"),
                        )
                    }
            }
        }
    }

    val backgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val page = pendingBgPage
        pendingBgPage = null
        if (uri != null && page != null) {
            previewBgPage = page
            previewBgUri = uri
            previewAll = false
        }
    }

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val detectMusic: () -> Unit = {
        scope.launch {
            val found = controller.localLibrary.detectMediaFolders()
            if (found.isEmpty()) {
                controller.toast(tr("Музыка не найдена", "No music found"))
            } else {
                controller.localLibrary.addFolders(found)
                controller.toast(
                    tr("Найдено папок: ${found.size}", "Found ${found.size} folders"),
                )
            }
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            detectMusic()
        } else {
            controller.toast(tr("Нужен доступ к аудио", "Audio access is required"))
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

    pendingImport?.let { backup ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(tr("Заменить данные?", "Replace data?"), color = OnyxWhite) },
            text = {
                Text(
                    text = tr(
                        "Плейлисты, избранное и настройки будут заменены данными из файла" +
                            (if (backup.versionName.isNotBlank()) " (версия ${backup.versionName})" else "") + ".",
                        "Playlists, favorites and settings will be replaced by the file" +
                            (if (backup.versionName.isNotBlank()) " (version ${backup.versionName})" else "") + ".",
                    ),
                    color = OnyxDim,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        scope.launch {
                            runCatching {
                                controller.prefs.restore(backup.prefs)
                                controller.localPlaylists.replaceAll(
                                    PlaylistsState(backup.playlists, backup.playlistFolders),
                                )
                                controller.localLibrary.reload()
                                runCatching {
                                    IconSwitcher.apply(context, backup.prefs.appIcon)
                                }
                            }.onSuccess {
                                controller.toast(tr("Данные восстановлены", "Data restored"))
                            }.onFailure {
                                controller.toast(
                                    tr(
                                        "Ошибка восстановления: ${it.message}",
                                        "Restore failed: ${it.message}",
                                    ),
                                )
                            }
                        }
                    },
                ) {
                    Text(tr("Заменить", "Replace"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(tr("Отмена", "Cancel"))
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 132.dp),
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
                contentDescription = tr("Назад", "Back"),
                size = 42.dp,
                iconSize = 20.dp,
                onClick = onBack,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = tr("Настройки", "Settings"),
                    color = OnyxWhite,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                )
                Text(
                    text = "ONYX · v" + app.onyx.BuildConfig.VERSION_NAME,
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    letterSpacing = 1.4.sp,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            GlassPill(
                text = tr("Звук", "Sound"),
                selected = section == 0,
                onClick = { section = 0 },
            )
            GlassPill(
                text = tr("Внешний вид", "Appearance"),
                selected = section == 1,
                onClick = { section = 1 },
            )
            GlassPill(
                text = tr("Подключения", "Connections"),
                selected = section == 2,
                onClick = { section = 2 },
            )
            GlassPill(
                text = tr("Библиотека", "Library"),
                selected = section == 3,
                onClick = { section = 3 },
            )
            GlassPill(
                text = tr("Данные", "Data"),
                selected = section == 4,
                onClick = { section = 4 },
            )
        }

        if (section == 0) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel(tr("Управление", "Controls"))
            Spacer(Modifier.height(10.dp))
            Text(
                tr("Шаг ползунка громкости", "Volume slider step"),
                color = OnyxWhite,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10).forEach { step ->
                    GlassPill(
                        text = "$step%",
                        selected = prefs.volumeStep == step,
                        onClick = { scope.launch { controller.prefs.setVolumeStep(step) } },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                tr(
                    "Насколько сильно меняется громкость при перетаскивании. Система может округлять до аппаратного шага.",
                    "How much the volume changes while dragging. The system may round to a hardware step.",
                ),
                color = OnyxFaint,
                fontSize = 10.sp,
            )
        }
        }

        if (section == 2) {
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
                            text = prefs.spotifyUser.ifBlank { tr("Аккаунт подключён", "Account connected") },
                            color = OnyxWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = tr("Очередь и повтор доступны", "Queue and repeat available"),
                            color = OnyxFaint,
                            fontSize = 11.sp,
                        )
                        Text(
                            text = tr("Управление воспроизведением требует активной Premium-подписки.", "Playback control requires an active Premium subscription."),
                            color = OnyxFaint,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                        Text(
                            text = tr(
                                "В РФ часть Spotify API (плейлисты, поиск) может быть недоступна; плеер и очередь обычно работают.",
                                "Some Spotify API endpoints (playlists, search) may be unavailable in this region; playback and queue usually work.",
                            ),
                            color = OnyxFaint,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = tr("Отключить", "Disconnect"),
                        tint = OnyxDim,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable {
                                scope.launch {
                                    controller.prefs.clearSpotify()
                                    controller.toast(tr("Spotify отключён", "Spotify disconnected"))
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
                    text = tr("Приложение работает с твоим личным Spotify-приложением. " +
                        "Создай его в Spotify Dashboard и вставь Client ID.", "ONYX uses your own Spotify app. Create one in the Spotify Dashboard and paste its Client ID."),
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
                RedirectBox { controller.toast(tr("Redirect URI скопирован", "Redirect URI copied")) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(
                        text = tr("Сохранить", "Save"),
                        selected = false,
                        enabled = clientId.isNotBlank(),
                        onClick = {
                            scope.launch {
                                controller.prefs.setClientId(clientId)
                                controller.toast(tr("Client ID сохранён", "Client ID saved"))
                            }
                        },
                    )
                    GlassPill(
                        text = if (busy) tr("Ждём браузер…", "Waiting for browser…") else tr("Войти", "Sign in"),
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
                    text = tr("Redirect URI в Dashboard", "Redirect URI in the Dashboard") + ": $REDIRECT\n" +
                        "Scopes: user-read-playback-state, user-modify-playback-state, " +
                        "user-read-currently-playing",
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Discord")
            Spacer(Modifier.height(10.dp))
            Text(
                tr(
                    "Хаб ONYX на ПК ставит настоящий Rich Presence: трек, артист, обложка и полоса прогресса. " +
                        "Открой ONYX на компьютере — телефон найдёт его сам.",
                    "The ONYX hub on the PC sets real Rich Presence: track, artist, cover and a progress bar. " +
                        "Open ONYX on the PC and the phone finds it automatically.",
                ),
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = tr("Поделиться треком", "Share track"),
                    selected = false,
                    onClick = {
                        val now = controller.nowPlaying.value
                        val text = buildString {
                            if (now.title.isNotBlank()) {
                                append(now.title)
                                if (now.artist.isNotBlank()) append(" — ").append(now.artist)
                                append("\n")
                            }
                            val trackUrl = now.spotifyTrackId?.let {
                                "https://open.spotify.com/track/$it"
                            } ?: DiscordConfig.GITHUB_URL
                            append("ONYX · $trackUrl")
                        }
                        runCatching {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(
                                Intent.createChooser(send, tr("Поделиться", "Share")),
                            )
                        }
                    },
                )
                GlassPill(
                    text = tr("Developer Portal", "Developer Portal"),
                    selected = false,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://discord.com/developers/applications"),
                                ),
                            )
                        }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                tr(
                    "Сопряжение нужно один раз: найди компьютер и введи код из ONYX на ПК.",
                    "Pair once: find the PC and enter the code shown in ONYX on the PC.",
                ),
                color = OnyxFaint,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(14.dp))
            ToggleRow(
                title = tr("Хаб ONYX на ПК", "ONYX hub on PC"),
                subtitle = tr(
                    "Телефон найдёт компьютер сам по Wi-Fi — IP вводить не нужно.",
                    "The phone finds the PC over Wi-Fi; no IP needed.",
                ),
                checked = prefs.discordBridgeEnabled,
                onChange = { enabled ->
                    if (enabled && prefs.discordBridgeHost.isBlank()) {
                        controller.toast(tr("Сначала найди компьютер", "Find the PC first"))
                    } else {
                        scope.launch { controller.prefs.setDiscordBridgeEnabled(enabled) }
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = if (searching) tr("Ищу…", "Searching…") else tr("Найти ПК", "Find PC"),
                    selected = true,
                    enabled = !searching,
                    onClick = {
                        searching = true
                        bridgeStatus = ""
                        scope.launch {
                            val found = withContext(Dispatchers.IO) { app.onyx.core.PcDiscovery.find() }
                            discovered = found
                            searching = false
                            if (found.isEmpty()) {
                                bridgeStatus = tr(
                                    "Компьютер не найден. Проверь Wi-Fi и что ONYX открыт на ПК.",
                                    "No PC found. Check Wi-Fi and that ONYX is running on the PC.",
                                )
                            }
                        }
                    },
                )
                GlassPill(
                    text = tr("Вручную", "Manual"),
                    selected = manualHost,
                    onClick = { manualHost = !manualHost },
                )
            }
            if (discovered.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                discovered.forEach { pc ->
                    val active = pc.host == prefs.discordBridgeHost
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                bridgeHost = pc.host
                                bridgePort = pc.port.toString()
                                scope.launch {
                                    controller.prefs.setDiscordBridgeHost(pc.host)
                                    controller.prefs.setDiscordBridgePort(pc.port)
                                    if (prefs.discordBridgeToken.isBlank()) {
                                        pairTarget = pc
                                        pairCodeInput = ""
                                    } else {
                                        controller.prefs.setDiscordBridgeEnabled(true)
                                        bridgeStatus = tr("Подключено: ${pc.name}", "Connected: ${pc.name}")
                                    }
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Computer,
                            contentDescription = null,
                            tint = if (active) OnyxWhite else OnyxDim,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                pc.name,
                                color = OnyxWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${pc.host}:${pc.port}" +
                                    if (pc.paired) tr(" · сопряжён", " · paired") else "",
                                color = OnyxFaint,
                                fontSize = 10.sp,
                            )
                        }
                        if (active) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = OnyxWhite,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            if (manualHost) {
                Spacer(Modifier.height(8.dp))
                ThemeField(tr("IP компьютера", "PC IP"), bridgeHost) {
                    bridgeHost = it
                    scope.launch {
                        controller.prefs.setDiscordBridgeHost(it.trim())
                        controller.prefs.setDiscordBridgeToken("")
                        // A bridge without a host cannot work; do not leave the
                        // switch looking enabled while every update is dropped.
                        if (it.isBlank() && prefs.discordBridgeEnabled) {
                            controller.prefs.setDiscordBridgeEnabled(false)
                        }
                    }
                }
                ThemeField(tr("Порт", "Port"), bridgePort) {
                    bridgePort = it.filter { ch -> ch.isDigit() }.take(5)
                    scope.launch {
                        controller.prefs.setDiscordBridgePort(bridgePort.toIntOrNull() ?: 8787)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                tr(
                    "На ПК открой ONYX и включи хаб в Настройки → Интеграции → Устройства. " +
                        "Телефон и ПК должны быть в одной Wi-Fi сети.",
                    "Open ONYX on the PC and enable the hub in Settings → Integrations → Devices. " +
                        "Phone and PC must be on the same Wi-Fi.",
                ),
                color = OnyxFaint,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassPill(
                    text = tr("Проверить связь", "Test connection"),
                    selected = false,
                    onClick = {
                        val host = bridgeHost.trim()
                        val port = bridgePort.toIntOrNull() ?: 8787
                        if (host.isBlank()) {
                            bridgeStatus = tr("Сначала найди компьютер", "Find the PC first")
                        } else {
                            scope.launch {
                                bridgeStatus = tr("Проверяю…", "Checking…")
                                bridgeStatus = withContext(Dispatchers.IO) { pingBridge(host, port) }
                            }
                        }
                    },
                )
                if (bridgeStatus.isNotBlank()) {
                    Text(
                        text = bridgeStatus,
                        color = OnyxFaint,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                tr("Плейлисты и лайки", "Playlists and likes"),
                color = OnyxWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr(
                    "Передача вручную и только добавлением: существующие плейлисты не перезаписываются, " +
                        "в них доливаются недостающие треки.",
                    "Manual and additive: existing playlists are never overwritten, only missing tracks are added.",
                ),
                color = OnyxFaint,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = tr("Отправить на ПК", "Send to PC"),
                    selected = true,
                    onClick = {
                        scope.launch {
                            val result = controller.syncClient.pushToPc()
                            transferStatus = syncMessage(result, sending = true)
                        }
                    },
                )
                GlassPill(
                    text = tr("Загрузить с ПК", "Load from PC"),
                    selected = false,
                    onClick = {
                        scope.launch {
                            val result = controller.syncClient.pullFromPc()
                            transferStatus = syncMessage(result, sending = false)
                        }
                    },
                )
            }
            if (transferStatus.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    transferStatus,
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel(tr("Подключение к ПК", "PC connection"))
            Spacer(Modifier.height(10.dp))
            if (prefs.discordBridgeHost.isNotBlank()) {
                Text(
                    tr(
                        "Подключено: ${prefs.discordBridgeHost}:${prefs.discordBridgePort}",
                        "Connected: ${prefs.discordBridgeHost}:${prefs.discordBridgePort}",
                    ),
                    color = OnyxWhite,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                GlassPill(
                    text = tr("Отключить от ПК", "Disconnect from PC"),
                    selected = false,
                    onClick = {
                        scope.launch {
                            controller.syncClient.unpair()
                            transferStatus = ""
                            bridgeStatus = tr("Сопряжение сброшено", "Pairing reset")
                            controller.toast(tr("ПК отключён", "PC disconnected"))
                        }
                    },
                )
            } else {
                Text(
                    tr(
                        "Компьютер не подключён. Нажми «Найти ПК» выше и введи код с ПК.",
                        "No PC connected. Tap “Find PC” above and enter the code from the PC.",
                    ),
                    color = OnyxDim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.height(14.dp))
            ToggleRow(
                title = tr("Копировать аудиофайлы", "Copy audio files"),
                subtitle = tr(
                    "Недостающие локальные треки переносятся вместе с плейлистами.",
                    "Missing local tracks are copied along with playlists.",
                ),
                checked = prefs.syncCopyFiles,
                onChange = { enabled ->
                    scope.launch { controller.prefs.setSyncCopyFiles(enabled) }
                },
            )
        }
        }

        if (section == 3) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel(tr("Локальная музыка", "Local music"))
            Spacer(Modifier.height(10.dp))
            Text(
                tr(
                    "Папки, плейлисты и нарезка YouTube-миксов теперь живут во вкладке «Библиотека» и «Поиск».",
                    "Folders, playlists and YouTube mix splitting now live in the Library and Search tabs.",
                ),
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(10.dp))
            GlassPill(
                text = tr("Состояние медиатеки", "Library health"),
                selected = false,
                onClick = onOpenHealth,
            )
        }
        }

        if (section == 0) {
        // -------------------------------------------------------------- Lyrics
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel(tr("Текст и караоке", "Lyrics & karaoke"))
            Spacer(Modifier.height(12.dp))
            Text(
                text = tr("Spicy Lyrics · общинная база TTML. Отдаёт текст и тайминги " +
                    "даже для ремиксов и slowed-версий, которых нет в Musixmatch.", "Spicy Lyrics is a community TTML catalogue with timings for remixes and slowed versions Musixmatch does not cover."),
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = tr("Ключ Spicy Lyrics встроен в сборку. Если источник недоступен, " +
                    "ONYX автоматически перейдёт к Musixmatch, Genius и LRCLIB.", "The Spicy Lyrics key is built into this APK. If unavailable, ONYX falls back to Musixmatch, Genius and LRCLIB."),
                color = OnyxFaint,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(18.dp))
            ToggleRow(
                title = tr("Караоке по словам", "Word karaoke"),
                subtitle = tr("Плавная подсветка слов, если провайдер отдал тайминги", "Smooth word highlighting when timings are available"),
                checked = prefs.karaoke,
                onChange = { scope.launch { controller.prefs.setKaraoke(it) } },
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = tr("Русский перевод", "Russian translation"),
                subtitle = tr("Spicy/Musixmatch, затем Google", "Spicy/Musixmatch, then Google"),
                checked = prefs.translate,
                onChange = { scope.launch { controller.prefs.setTranslate(it) } },
            )
            Spacer(Modifier.height(18.dp))
            SliderRow(
                title = tr("Размер текста", "Lyrics size"),
                value = "${fontSize.toInt()}",
                sliderValue = (fontSize - 20f) / 28f,
                onChange = { fontSize = 20f + it * 28f },
                onFinish = { scope.launch { controller.prefs.setFontSize(fontSize) } },
            )
            Spacer(Modifier.height(10.dp))
            SliderRow(
                title = tr("Смещение текста", "Lyrics offset"),
                value = "${(offset / 100).toInt() * 100} ${tr("мс", "ms")}",
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
            SectionLabel(tr("Наушники", "Headphones"))
            Spacer(Modifier.height(12.dp))
            Text(
                text = tr("ONYX управляет Galaxy Buds по служебному SPP-каналу Samsung. " +
                    "Другие Bluetooth-наушники работают как обычный аудиовыход: " +
                    "плеер, громкость и переключение маршрута. Автоподключения к Buds нет — " +
                    "только по кнопке.", "ONYX controls Galaxy Buds over Samsung SPP. Other Bluetooth headphones work as a standard audio output: player, volume and route switching. There is no automatic SPP reconnect."),
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = tr("Переподключить", "Reconnect"),
                    selected = false,
                    onClick = { BudsService.reconnect(context) },
                )
                GlassPill(
                    text = tr("Открыть Bluetooth", "Open Bluetooth"),
                    selected = false,
                    onClick = { controller.router.openBluetoothSettings() },
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = tr("Для стабильного SPP на Samsung отключи менеджер (один раз, через adb):", "For stable SPP on Samsung, disable the manager once via adb:") + "\n" +
                    "adb shell pm disable-user --user 0 com.samsung.accessory.paranmgr",
                color = OnyxFaint,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
        }
        }

        if (section == 3) {
        // ------------------------------------------------------------- System
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel(tr("Доступы", "Permissions"))
            Spacer(Modifier.height(12.dp))
            StatusRow(
                title = tr("Доступ к уведомлениям", "Notification access"),
                ok = listenerOn,
                action = tr("Открыть", "Open"),
                onAction = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
            if (!listenerOn) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = tr(
                        "Переключатель серый? Откройте настройки приложения → меню ⋮ → " +
                            "«Разрешить ограниченные настройки», затем включите доступ к уведомлениям.",
                        "Switch greyed out? Open app settings → ⋮ → Allow restricted settings, " +
                            "then enable notification access.",
                    ),
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(8.dp))
                GlassPill(
                    text = tr("Настройки приложения", "App settings"),
                    selected = false,
                    onClick = { controller.updates.openAppSettings() },
                )
            }
            Spacer(Modifier.height(12.dp))
            StatusRow(
                title = tr("Установка обновлений", "Update installation"),
                ok = installAllowed,
                action = tr("Разрешить", "Allow"),
                onAction = { controller.updates.openInstallPermission() },
            )
            Spacer(Modifier.height(12.dp))
            StatusRow(
                title = tr("Bluetooth-разрешения", "Bluetooth permissions"),
                ok = hasBluetooth(context),
                action = tr("Выдать", "Grant"),
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
                title = tr("Без оптимизации батареи", "Battery optimization off"),
                ok = batteryFree,
                action = tr("Открыть", "Open"),
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
            SectionLabel(tr("Локальная музыка", "Local music"))
            Spacer(Modifier.height(10.dp))
            if (prefs.localFolders.isEmpty()) {
                Text(
                    tr("Папки не выбраны", "No folders selected"),
                    color = OnyxFaint,
                    fontSize = 13.sp,
                )
            } else {
                prefs.localFolders.forEach { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            folder.name.ifBlank { tr("Папка", "Folder") },
                            color = OnyxWhite,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = tr("Удалить папку", "Remove folder"),
                            tint = OnyxFaint,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { controller.localLibrary.removeFolder(folder.uri) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            GlassPill(
                text = tr("Найти музыку автоматически", "Find music automatically"),
                selected = true,
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, audioPermission) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        detectMusic()
                    } else {
                        audioPermissionLauncher.launch(audioPermission)
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = if (prefs.localFolders.isEmpty()) tr("Выбрать папку", "Choose folder") else tr("Добавить папку", "Add folder"),
                    selected = false,
                    onClick = { musicFolderLauncher.launch(musicFolderUri()) },
                )
                if (prefs.localFolders.isNotEmpty()) {
                    GlassPill(
                        text = tr("Очистить всё", "Clear all"),
                        selected = false,
                        onClick = { controller.localLibrary.clearFolder() },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = tr(
                    "В проводнике откройте Music или Download и нажмите «Использовать эту папку». Корень хранилища Android выбрать не даёт.",
                    "In the picker open Music or Download and tap Use this folder. Android does not allow the storage root.",
                ),
                color = OnyxFaint,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel(tr("Звук", "Sound"))
            Spacer(Modifier.height(10.dp))
            Text(
                tr("Эквалайзер с пресетами и ручной настройкой полос. " +
                    "Работает через общий аудиовыход; на части Bluetooth-устройств эффект может быть недоступен.", "Equalizer with presets and manual bands. Uses the global audio output; may be unavailable on some Bluetooth devices."),
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            GlassPill(
                text = tr("Открыть эквалайзер", "Open equalizer"),
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
        }

        if (section == 4) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel(tr("Данные", "Data"))
            Spacer(Modifier.height(10.dp))
            Text(
                text = tr(
                    "Экспорт и импорт плейлистов, избранного и настроек. Файл содержит токены Spotify и Discord — храните его в безопасном месте.",
                    "Export and import playlists, favorites and settings. The file contains Spotify and Discord tokens — keep it private.",
                ),
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = tr("Экспорт данных", "Export data"),
                    selected = true,
                    onClick = {
                        val stamp = java.text.SimpleDateFormat(
                            "yyyyMMdd-HHmm",
                            java.util.Locale.US,
                        ).format(java.util.Date())
                        exportLauncher.launch("onyx-backup-$stamp.json")
                    },
                )
                GlassPill(
                    text = tr("Импорт данных", "Import data"),
                    selected = false,
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                )
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel(tr("Обновления", "Updates"))
            Spacer(Modifier.height(10.dp))
            Text(
                text = tr(
                    "Версия ${app.onyx.BuildConfig.VERSION_NAME} (сборка ${app.onyx.BuildConfig.VERSION_CODE})",
                    "Version ${app.onyx.BuildConfig.VERSION_NAME} (build ${app.onyx.BuildConfig.VERSION_CODE})",
                ),
                color = OnyxFaint,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = tr("Проверять при запуске", "Check on launch"),
                subtitle = tr(
                    "Тихая проверка при открытии приложения.",
                    "Silent check when the app opens.",
                ),
                checked = prefs.autoUpdateCheck,
                onChange = { scope.launch { controller.prefs.setAutoUpdateCheck(it) } },
            )
            Spacer(Modifier.height(12.dp))
            val statusText = when (val state = updateState) {
                is UpdateState.Idle -> tr("Проверка не запускалась", "Not checked yet")
                is UpdateState.Checking -> tr("Проверяем…", "Checking…")
                is UpdateState.UpToDate -> tr("Обновлений нет", "You are up to date")
                is UpdateState.Available -> tr(
                    "Доступно ${state.info.versionName}",
                    "${state.info.versionName} is available",
                )
                is UpdateState.Downloading -> tr(
                    "Скачивание ${state.progress}%",
                    "Downloading ${state.progress}%",
                )
                is UpdateState.Installing -> tr("Установка…", "Installing…")
                is UpdateState.NeedsInstallPermission -> tr(
                    "Нужно разрешение на установку",
                    "Install permission required",
                )
                is UpdateState.Error -> state.message
            }
            Text(text = statusText, color = OnyxDim, fontSize = 12.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = tr("Проверить обновления", "Check for updates"),
                    selected = false,
                    enabled = updateState !is UpdateState.Checking,
                    onClick = { scope.launch { controller.updates.check() } },
                )
                when (val state = updateState) {
                    is UpdateState.Available -> GlassPill(
                        text = tr("Скачать и установить", "Download and install"),
                        selected = true,
                        onClick = {
                            scope.launch { controller.updates.downloadAndInstall(state.info) }
                        },
                    )
                    is UpdateState.NeedsInstallPermission -> GlassPill(
                        text = tr("Разрешить установку", "Allow installation"),
                        selected = true,
                        onClick = { controller.updates.openInstallPermission() },
                    )
                    else -> Unit
                }
            }
            if (!installAllowed) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = tr(
                        "Если пункт «Установка неизвестных приложений» недоступен: настройки приложения → ⋮ → «Разрешить ограниченные настройки».",
                        "If Install unknown apps is unavailable: app settings → ⋮ → Allow restricted settings.",
                    ),
                    color = OnyxFaint,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel(tr("О приложении", "About"))
            Spacer(Modifier.height(8.dp))
            Text(
                text = tr("ONYX — личный пульт управления музыкой. " +
                    "Медиа, Bluetooth-маршруты, Galaxy Buds и другие наушники, Spotify и тексты " +
                    "Spicy Lyrics в одном месте. Протокол Buds портирован из GalaxyBudsClient (GPLv3).", "ONYX is a personal music control center: media, Bluetooth routes, Galaxy Buds and other headphones, Spotify and Spicy Lyrics in one place. Buds protocol ported from GalaxyBudsClient (GPLv3)."),
                color = OnyxDim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = tr(
                    "Эксклюзивный набор иконок — в коллаборации с художницей.",
                    "Exclusive icon set — in collaboration with the artist.",
                ),
                color = OnyxFaint,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = tr(
                    "Версия ${app.onyx.BuildConfig.VERSION_NAME} (сборка ${app.onyx.BuildConfig.VERSION_CODE})",
                    "Version ${app.onyx.BuildConfig.VERSION_NAME} (build ${app.onyx.BuildConfig.VERSION_CODE})",
                ),
                color = OnyxFaint,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
            )
        }
        }

        if (section == 1) {
            AppearanceSection(
                controller = controller,
                prefs = prefs,
                scope = scope,
                onPickBackground = { page ->
                    pendingBgPage = page
                    backgroundPicker.launch(arrayOf("image/*"))
                },
            )
        }

        val pairPc = pairTarget
        if (pairPc != null) {
            AlertDialog(
                onDismissRequest = { pairTarget = null },
                containerColor = OnyxSurface,
                title = {
                    Text(
                        tr("Код сопряжения", "Pairing code"),
                        color = OnyxWhite,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Column {
                        Text(
                            tr(
                                "Введи 6-значный код из ONYX на компьютере «${pairPc.name}».",
                                "Enter the 6-digit code from ONYX on \"${pairPc.name}\".",
                            ),
                            color = OnyxDim,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = pairCodeInput,
                            onValueChange = { pairCodeInput = it.filter(Char::isDigit).take(6) },
                            singleLine = true,
                            label = { Text(tr("Код", "Code"), fontSize = 11.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = OnyxWhite,
                                fontSize = 20.sp,
                                letterSpacing = 4.sp,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = OnyxWhite.copy(alpha = 0.45f),
                                unfocusedBorderColor = OnyxLine,
                                focusedLabelColor = OnyxDim,
                                unfocusedLabelColor = OnyxFaint,
                                cursorColor = OnyxWhite,
                            ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = pairCodeInput.length == 6 && !pairing,
                        onClick = {
                            pairing = true
                            scope.launch {
                                val token = withContext(Dispatchers.IO) {
                                    pairBridge(pairPc.host, pairPc.port, pairCodeInput)
                                }
                                pairing = false
                                if (token != null) {
                                    controller.prefs.setDiscordBridgeToken(token)
                                    controller.prefs.setDiscordBridgeHost(pairPc.host)
                                    controller.prefs.setDiscordBridgePort(pairPc.port)
                                    controller.prefs.setDiscordBridgeEnabled(true)
                                    bridgeStatus = tr(
                                        "Подключено: ${pairPc.name}",
                                        "Connected: ${pairPc.name}",
                                    )
                                    pairTarget = null
                                    controller.toast(
                                        tr("Телефон подключён к ПК", "Phone connected to the PC"),
                                    )
                                } else {
                                    bridgeStatus = tr(
                                        "Неверный код или компьютер недоступен",
                                        "Wrong code or PC unavailable",
                                    )
                                }
                            }
                        },
                    ) {
                        Text(
                            if (pairing) tr("Подключаю…", "Connecting…") else tr("Подключить", "Connect"),
                            color = OnyxWhite,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pairTarget = null }) {
                        Text(tr("Отмена", "Cancel"), color = OnyxDim)
                    }
                },
            )
        }

        val previewUri = previewBgUri
        val previewPage = previewBgPage
        if (previewUri != null && previewPage != null) {
            BackgroundPreviewDialog(
                uri = previewUri,
                applyAll = previewAll,
                onApplyAll = { previewAll = it },
                onDismiss = {
                    previewBgUri = null
                    previewBgPage = null
                },
                onApply = {
                    scope.launch {
                        val pages = if (previewAll) {
                            listOf("player", "lyrics", "queue", "music", "youtube", "visualizer", "settings")
                        } else {
                            listOf(previewPage)
                        }
                        val target = java.io.File(
                            context.filesDir,
                            if (previewAll) "bg_all.jpg" else "bg_$previewPage.jpg",
                        )
                        val saved = runCatching {
                            context.contentResolver.openInputStream(previewUri)?.use { input ->
                                target.outputStream().use { out -> input.copyTo(out) }
                            }
                            target.absolutePath
                        }.getOrNull()
                        if (saved != null) {
                            val bg = decodeBackgrounds(prefs.pageBackgrounds).toMutableMap()
                            pages.forEach { bg[it] = saved }
                            controller.prefs.setPageBackgrounds(encodeBackgrounds(bg))
                            val blur = decodeBackgrounds(prefs.pageBackgroundBlur).toMutableMap()
                            pages.forEach { blur[it] = "0" }
                            controller.prefs.setPageBackgroundBlur(encodeBackgrounds(blur))
                            controller.toast(tr("Фон применён", "Background applied"))
                            previewBgUri = null
                            previewBgPage = null
                        } else {
                            controller.toast(tr("Не удалось загрузить фото", "Could not load the photo"))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun AppearanceSection(
    controller: AppController,
    prefs: app.onyx.core.PrefsData,
    scope: kotlinx.coroutines.CoroutineScope,
    onPickBackground: (String) -> Unit,
) {
    var intensity by remember(prefs.coverIntensity) {
        mutableFloatStateOf(prefs.coverIntensity.toFloat())
    }
    var lyricBrightness by remember(prefs.lyricBrightness) {
        mutableFloatStateOf(prefs.lyricBrightness.toFloat())
    }
    var uiBrightness by remember(prefs.uiTextBrightness) {
        mutableFloatStateOf(prefs.uiTextBrightness.toFloat())
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val savedThemes = remember(prefs.customThemes) {
        CustomThemeCodec.decodeList(prefs.customThemes)
    }
    val customThemes = remember(savedThemes) {
        (savedThemes + app.onyx.core.BuiltInThemes).distinctBy { it.id }
    }
    var showThemeEditor by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }
    var editingTheme by remember { mutableStateOf<CustomTheme?>(null) }
    var exportTheme by remember { mutableStateOf<CustomTheme?>(null) }
    val themeImporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                val theme = text?.let { CustomThemeCodec.decodeOne(it) }
                if (theme != null) {
                    val next = savedThemes.filterNot { it.id == theme.id } + theme
                    controller.prefs.setCustomThemes(CustomThemeCodec.encodeList(next))
                    controller.prefs.setCustomThemeId(theme.id)
                    controller.toast(tr("Тема импортирована", "Theme imported"))
                } else {
                    controller.toast(tr("Не удалось прочитать тему", "Could not read the theme"))
                }
            }
        }
    }
    val themeExporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val theme = exportTheme
        exportTheme = null
        if (uri != null && theme != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(CustomThemeCodec.encodeOne(theme))
                    }
                }
                controller.toast(tr("Тема сохранена", "Theme exported"))
            }
        }
    }
    var showUiFont by remember { mutableStateOf(false) }
    var showLyricFont by remember { mutableStateOf(false) }
    val pages = prefs.coverPages.split(',').filter { it.isNotBlank() }.toSet()

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(tr("Язык", "Language"))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill(
                text = tr("Русский", "Russian"),
                selected = prefs.language == app.onyx.core.AppLanguage.RU,
                onClick = { scope.launch { controller.prefs.setLanguage(app.onyx.core.AppLanguage.RU) } },
            )
            GlassPill(
                text = "English",
                selected = prefs.language == app.onyx.core.AppLanguage.EN,
                onClick = { scope.launch { controller.prefs.setLanguage(app.onyx.core.AppLanguage.EN) } },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            tr("Интерфейс переключается с плавным затуханием.", "The interface switches with a soft fade."),
            color = OnyxFaint,
            fontSize = 11.sp,
        )
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(tr("Шрифты", "Fonts"))
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr("Шрифт интерфейса", "Interface font"),
                color = OnyxWhite,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            GlassPill(
                text = FontCatalog.choices.firstOrNull { it.id == prefs.uiFont }?.label
                    ?: tr("Системный", "System"),
                selected = false,
                onClick = { showUiFont = true },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr("Шрифт текста песен", "Lyrics font"),
                color = OnyxWhite,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            GlassPill(
                text = FontCatalog.choices.firstOrNull { it.id == prefs.lyricFont }?.label
                    ?: tr("Системный", "System"),
                selected = false,
                onClick = { showLyricFont = true },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            tr(
                "Шрифт интерфейса меняет всё приложение. Шрифт текста — только строки песен.",
                "Interface font changes the whole app. Lyrics font only affects song lines.",
            ),
            color = OnyxFaint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(tr("Тема", "Theme"))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill(
                text = tr("Тёмная", "Dark"),
                selected = prefs.themeMode == app.onyx.core.ThemeMode.DARK,
                onClick = { scope.launch { controller.prefs.setCustomThemeId(""); controller.prefs.setThemeMode(app.onyx.core.ThemeMode.DARK) } },
            )
            GlassPill(
                text = tr("Светлая", "Light"),
                selected = prefs.themeMode == app.onyx.core.ThemeMode.LIGHT,
                onClick = { scope.launch { controller.prefs.setCustomThemeId(""); controller.prefs.setThemeMode(app.onyx.core.ThemeMode.LIGHT) } },
            )
            GlassPill(
                text = tr("Система", "System"),
                selected = prefs.themeMode == app.onyx.core.ThemeMode.SYSTEM,
                onClick = { scope.launch { controller.prefs.setCustomThemeId(""); controller.prefs.setThemeMode(app.onyx.core.ThemeMode.SYSTEM) } },
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = tr("Светлая тема меняет фон, стекло и цвет текста. Переключение анимировано.", "Light theme changes the background, glass and text colors with a smooth animation."),
            color = OnyxFaint,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(tr("Кастомные темы", "Custom themes"))
        Spacer(Modifier.height(8.dp))
        Text(
            tr(
                "Импортируй JSON-тему или создай свою. Цвета применяются ко всему интерфейсу.",
                "Import a JSON theme or create your own. Colors apply to the whole interface.",
            ),
            color = OnyxFaint,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        Spacer(Modifier.height(10.dp))
        val builtIn = LocalOnyx.current
        val builtInActive = prefs.customThemeId.isBlank()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable {
                    scope.launch { controller.prefs.setCustomThemeId("") }
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(builtIn.black, builtIn.surface, builtIn.accent).forEach { c ->
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(c),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                tr("Стандартная тема", "Original theme"),
                color = if (builtInActive) OnyxWhite else OnyxDim,
                fontSize = 13.sp,
                fontWeight = if (builtInActive) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (builtInActive) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = OnyxWhite,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        customThemes.forEach { theme ->
            val colors = remember(theme) { theme.toOnyxColors() }
            val active = prefs.customThemeId == theme.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        scope.launch { controller.prefs.setCustomThemeId(theme.id) }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(colors.black, colors.surface, colors.accent).forEach { c ->
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(c),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    theme.name,
                    color = if (active) OnyxWhite else OnyxDim,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (active) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = OnyxWhite,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                CircleButton(
                    icon = Icons.Rounded.Share,
                    contentDescription = tr("Экспорт", "Export"),
                    size = 30.dp,
                    iconSize = 14.dp,
                    onClick = {
                        exportTheme = theme
                        themeExporter.launch("${theme.id}.json")
                    },
                )
                Spacer(Modifier.width(4.dp))
                if (savedThemes.any { it.id == theme.id }) {
                    CircleButton(
                        icon = Icons.Rounded.Delete,
                        contentDescription = tr("Удалить", "Delete"),
                        size = 30.dp,
                        iconSize = 14.dp,
                        onClick = {
                            scope.launch {
                                val next = savedThemes.filterNot { it.id == theme.id }
                                controller.prefs.setCustomThemes(CustomThemeCodec.encodeList(next))
                                if (prefs.customThemeId == theme.id) {
                                    controller.prefs.setCustomThemeId("")
                                }
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill(
                text = tr("Импорт JSON", "Import JSON"),
                selected = false,
                onClick = {
                    themeImporter.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream"),
                    )
                },
            )
            GlassPill(
                text = tr("Создать тему", "Create theme"),
                selected = false,
                onClick = {
                    editingTheme = null
                    showThemeEditor = true
                },
            )
        }
    }

    if (showThemeEditor) {
        ThemeEditorDialog(
            initial = editingTheme,
            onDismiss = { showThemeEditor = false },
            onSave = { theme ->
                scope.launch {
                    val next = savedThemes.filterNot { it.id == theme.id } + theme
                    controller.prefs.setCustomThemes(CustomThemeCodec.encodeList(next))
                    controller.prefs.setCustomThemeId(theme.id)
                    showThemeEditor = false
                }
            },
        )
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(tr("Иконка приложения", "App icon"))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .border(1.dp, OnyxLine, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(appIconRes(prefs.appIcon)),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.82f),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                val exclusive = prefs.appIcon >= IconSwitcher.FIRST_EXCLUSIVE
                Text(
                    text = if (exclusive) {
                        "EXCLUSIVE · ${markCaption(prefs.appIcon)}"
                    } else {
                        "ONYX %02d".format(prefs.appIcon)
                    },
                    color = OnyxWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (exclusive) {
                        tr("В коллаборации с художницей", "In collaboration with the artist")
                    } else {
                        tr("Встроенный набор", "Built-in set")
                    },
                    color = OnyxFaint,
                    fontSize = 9.sp,
                )
            }
            GlassPill(
                text = tr("Выбрать", "Choose"),
                selected = false,
                onClick = { showIconPicker = true },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            tr(
                "Смена иконки может убрать значок с домашнего экрана — добавь ONYX заново из меню приложений.",
                "Changing the icon may remove it from the home screen — add ONYX again from the app drawer.",
            ),
            color = OnyxFaint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }

    if (showIconPicker) {
        IconPickerDialog(
            current = prefs.appIcon,
            onPick = { index ->
                scope.launch { controller.prefs.setAppIcon(index) }
                IconSwitcher.apply(context, index)
                controller.toast(tr("Иконка изменена", "App icon changed"))
                showIconPicker = false
            },
            onDismiss = { showIconPicker = false },
        )
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(tr("Цвет караоке", "Karaoke color"))
        Spacer(Modifier.height(12.dp))
        val colors = listOf(
            0xFFF6F6F7L, 0xFFB8B8C0L, 0xFFFF6B6BL, 0xFFFFA94DL,
            0xFFFFD166L, 0xFF6EE7B7L, 0xFF6CB6FFL, 0xFFC084FCL,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            colors.forEach { value ->
                val selected = prefs.lyricActiveColor == value
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(value))
                        .then(
                            if (selected) Modifier.border(
                                2.dp,
                                OnyxWhite,
                                androidx.compose.foundation.shape.CircleShape,
                            ) else Modifier,
                        )
                        .clickable {
                            scope.launch { controller.prefs.setLyricActiveColor(value) }
                        },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            tr("Применяется к активной строке и заливке слов.", "Applied to the active line and word fill."),
            color = OnyxFaint,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Яркость текста", "Lyrics brightness"),
                color = OnyxWhite,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${lyricBrightness.toInt()}%",
                color = OnyxFaint,
                fontSize = 10.sp,
            )
        }
        OnyxSlider(
            value = (lyricBrightness / 100f).coerceIn(0.35f, 1f),
            onValueChange = { lyricBrightness = it * 100f },
            onValueChangeFinished = {
                scope.launch {
                    controller.prefs.setLyricBrightness(lyricBrightness.toInt().coerceIn(35, 100))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Яркость текста приложения", "App text brightness"),
                color = OnyxWhite,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${uiBrightness.toInt()}%",
                color = OnyxFaint,
                fontSize = 10.sp,
            )
        }
        OnyxSlider(
            value = (uiBrightness / 100f).coerceIn(0.35f, 1f),
            onValueChange = { uiBrightness = it * 100f },
            onValueChangeFinished = {
                scope.launch {
                    controller.prefs.setUiTextBrightness(uiBrightness.toInt().coerceIn(35, 100))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(tr("Фон", "Background"))
        Spacer(Modifier.height(12.dp))
        ToggleRow(
            title = tr("Фон по обложке", "Cover background"),
            subtitle = tr("Размытая обложка и цветовой оттенок", "Blurred cover and color tint"),
            checked = prefs.coverBackdrop,
            onChange = { scope.launch { controller.prefs.setCoverBackdrop(it) } },
        )
        Spacer(Modifier.height(10.dp))
        SliderRow(
            title = tr("Интенсивность фона", "Background intensity"),
            value = "${intensity.toInt()}%",
            sliderValue = (intensity / 100f).coerceIn(0f, 1f),
            onChange = { intensity = (it * 100f).coerceIn(0f, 100f) },
            onFinish = { scope.launch { controller.prefs.setCoverIntensity(intensity.toInt()) } },
        )
        Spacer(Modifier.height(12.dp))
        SectionLabel(tr("Где показывать", "Show background on"))
        Spacer(Modifier.height(8.dp))
        val pageItems = listOf(
            "player" to tr("Плеер", "Player"),
            "lyrics" to tr("Текст", "Lyrics"),
            "queue" to tr("Очередь", "Queue"),
            "playlists" to tr("Плейлисты", "Playlists"),
            "music" to tr("Музыка", "Music"),
            "youtube" to tr("YouTube", "YouTube"),
            "visualizer" to tr("Визуал", "Visual"),
            "settings" to tr("Настройки", "Settings"),
        )
        pageItems.forEachIndexed { index, (key, label) ->
            ToggleRow(
                title = label,
                subtitle = tr("Фон из обложки на этой странице", "Cover background on this page"),
                checked = pages.contains(key),
                onChange = { on ->
                    val next = if (on) pages + key else pages - key
                    scope.launch { controller.prefs.setCoverPages(next.joinToString(",")) }
                },
            )
            if (index != pageItems.lastIndex) Spacer(Modifier.height(10.dp))
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(tr("Фоны страниц", "Page backgrounds"))
        Spacer(Modifier.height(10.dp))
        val backgrounds = decodeBackgrounds(prefs.pageBackgrounds)
        val pages = listOf(
            "player" to tr("Плеер", "Player"),
            "lyrics" to tr("Текст", "Lyrics"),
            "queue" to tr("Очередь", "Queue"),
            "playlists" to tr("Плейлисты", "Playlists"),
            "music" to tr("Музыка", "Music"),
            "youtube" to tr("YouTube", "YouTube"),
            "visualizer" to tr("Визуал", "Visual"),
            "settings" to tr("Настройки", "Settings"),
        )
        pages.forEachIndexed { index, (key, label) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    color = OnyxWhite,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                if (backgrounds[key] != null) {
                    GlassPill(
                        text = tr("Сброс", "Reset"),
                        selected = false,
                        onClick = {
                            val map = backgrounds.toMutableMap()
                            map.remove(key)
                            scope.launch { controller.prefs.setPageBackgrounds(encodeBackgrounds(map)) }
                        },
                    )
                } else {
                    GlassPill(
                        text = tr("Фото", "Photo"),
                        selected = false,
                        onClick = { onPickBackground(key) },
                    )
                }
            }
            if (backgrounds[key] != null) {
                val blurNow = decodeBackgrounds(prefs.pageBackgroundBlur)[key]?.toIntOrNull() ?: 0
                PageBlurRow(
                    label = tr("Размытие фона", "Background blur"),
                    initial = blurNow,
                    onFinish = { value ->
                        val blur = decodeBackgrounds(prefs.pageBackgroundBlur).toMutableMap()
                        blur[key] = value.toString()
                        scope.launch { controller.prefs.setPageBackgroundBlur(encodeBackgrounds(blur)) }
                    },
                )
            }
            if (index != pages.lastIndex) Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            tr(
                "Своё фото заменяет обложку на выбранной странице. Оно сильно размывается, чтобы не мешать тексту.",
                "A custom photo replaces the cover on that page. It is heavily blurred so text stays readable.",
            ),
            color = OnyxFaint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }

    if (showUiFont) {
        FontPickerDialog(
            selected = prefs.uiFont,
            onSelect = { scope.launch { controller.prefs.setUiFont(it) } },
            onDismiss = { showUiFont = false },
        )
    }
    if (showLyricFont) {
        FontPickerDialog(
            selected = prefs.lyricFont,
            onSelect = { scope.launch { controller.prefs.setLyricFont(it) } },
            onDismiss = { showLyricFont = false },
        )
    }
}

@Composable
private fun PageBlurRow(
    label: String,
    initial: Int,
    onFinish: (Int) -> Unit,
) {
    var value by remember(initial) { mutableFloatStateOf(initial.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label ${value.toInt()}%",
            color = OnyxFaint,
            fontSize = 10.sp,
            modifier = Modifier.width(120.dp),
        )
        OnyxSlider(
            value = (value / 100f).coerceIn(0f, 1f),
            onValueChange = { value = it * 100f },
            onValueChangeFinished = { onFinish(value.toInt()) },
            modifier = Modifier.weight(1f),
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
            Text(tr("готово", "done"), color = OnyxFaint, fontSize = 11.sp)
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

@Composable
private fun BackgroundPreviewDialog(
    uri: android.net.Uri,
    applyAll: Boolean,
    onApplyAll: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OnyxSurface,
        title = { Text(tr("Предпросмотр фона", "Background preview"), color = OnyxWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp)),
                ) {
                    CustomBackdrop(art = uri, blurPercent = 0)
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(14.dp),
                    ) {
                        Text("ONYX", color = OnyxWhite, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Text(
                            tr("Так будет выглядеть страница", "This is how the page will look"),
                            color = OnyxWhite.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("Применить ко всем страницам", "Apply to all pages"),
                        color = OnyxDim,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    OnyxSwitch(checked = applyAll, onCheckedChange = onApplyAll)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply) {
                Text(tr("Применить", "Apply"), color = OnyxWhite, fontWeight = FontWeight.SemiBold)
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
private fun ThemeEditorDialog(
    initial: CustomTheme?,
    onDismiss: () -> Unit,
    onSave: (CustomTheme) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var background by remember(initial) { mutableStateOf(initial?.background ?: "#050506") }
    var surface by remember(initial) { mutableStateOf(initial?.surface ?: "#101013") }
    var text by remember(initial) { mutableStateOf(initial?.text ?: "#F6F6F7") }
    var accent by remember(initial) { mutableStateOf(initial?.accent ?: "#EDEDEF") }
    var light by remember(initial) { mutableStateOf(initial?.light ?: false) }
    val hexOk = listOf(background, surface, text, accent).all { CustomTheme.HEX.matches(it) }
    val valid = name.isNotBlank() && hexOk

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OnyxSurface,
        title = {
            Text(
                tr("Своя тема", "Custom theme"),
                color = OnyxWhite,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                ThemeField(tr("Название", "Name"), name) { name = it }
                ThemeField(tr("Фон", "Background"), background) { background = it }
                ThemeField(tr("Поверхность", "Surface"), surface) { surface = it }
                ThemeField(tr("Текст", "Text"), text) { text = it }
                ThemeField(tr("Акцент", "Accent"), accent) { accent = it }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("Светлая тема", "Light theme"),
                        color = OnyxDim,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    OnyxSwitch(checked = light, onCheckedChange = { light = it })
                }
                if (!hexOk) {
                    Text(
                        tr("Цвета в формате #RRGGBB", "Colors must use #RRGGBB"),
                        color = OnyxFaint,
                        fontSize = 10.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        CustomTheme(
                            id = name.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "custom" },
                            name = name.trim(),
                            light = light,
                            background = background,
                            surface = surface,
                            text = text,
                            accent = accent,
                        ),
                    )
                },
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
private fun ThemeField(label: String, value: String, onChange: (String) -> Unit) {
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

private fun decodeBackgrounds(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return raw.split('\u001E').mapNotNull { record ->
        if (record.isBlank()) return@mapNotNull null
        val parts = record.split('\u001F', limit = 2)
        val key = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        if (key.isBlank() || path.isBlank()) null else key to path
    }.toMap()
}

private fun encodeBackgrounds(map: Map<String, String>): String =
    map.entries.joinToString("\u001E") { it.key + "\u001F" + it.value }

/**
 * Formats the result of an additive transfer for the settings card.
 */
private fun syncMessage(result: SyncResult, sending: Boolean): String {
    if (!result.ok) {
        return when {
            result.unauthorized -> tr(
                "Сопряжение устарело: нажми «Отключить от ПК» и подключись заново.",
                "Pairing is stale: tap “Disconnect from PC” and pair again.",
            )
            result.offline -> tr(
                "ПК недоступен: открой ONYX на компьютере и проверь Wi-Fi.",
                "PC unavailable: open ONYX on the PC and check Wi-Fi.",
            )
            else -> tr("Не удалось передать данные", "Could not transfer the data")
        }
    }
    val parts = mutableListOf<String>()
    if (result.playlistsAdded > 0) {
        parts += tr(
            "новых плейлистов: ${result.playlistsAdded}",
            "new playlists: ${result.playlistsAdded}",
        )
    }
    if (result.tracksAdded > 0) {
        parts += tr(
            "добавлено треков: ${result.tracksAdded}",
            "tracks added: ${result.tracksAdded}",
        )
    }
    if (result.likesAdded > 0) {
        parts += tr(
            "лайков: ${result.likesAdded}",
            "likes: ${result.likesAdded}",
        )
    }
    if (result.filesSent > 0) {
        parts += tr(
            "файлов отправлено: ${result.filesSent}",
            "files sent: ${result.filesSent}",
        )
    }
    if (result.filesReceived > 0) {
        parts += tr(
            "файлов скачано: ${result.filesReceived}",
            "files downloaded: ${result.filesReceived}",
        )
    }
    if (result.filesFailed > 0) {
        parts += tr(
            "не удалось: ${result.filesFailed}",
            "failed: ${result.filesFailed}",
        )
    }
    if (result.noFolder) {
        parts += tr(
            "нет папки музыки — файлы не скопированы",
            "no music folder — files were not copied",
        )
    }
    if (parts.isEmpty()) {
        return if (sending) {
            tr("Всё уже было на ПК", "Everything was already on the PC")
        } else {
            tr("Всё уже было на телефоне", "Everything was already on the phone")
        }
    }
    return parts.joinToString(" · ")
}

/**
 * Exchanges the 6-digit pairing code for a long-lived token. Returns null when
 * the code is wrong or the PC is not reachable.
 */
private fun pairBridge(host: String, port: Int, code: String): String? {
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val payload = org.json.JSONObject()
        .put("code", code)
        .put("device", android.os.Build.MODEL ?: "Android")
        .toString()
    val request = okhttp3.Request.Builder()
        .url("http://$host:${port.coerceIn(1, 65535)}/pair")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()
    return runCatching {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            org.json.JSONObject(response.body?.string().orEmpty())
                .optString("token")
                .ifBlank { null }
        }
    }.getOrNull()
}

/**
 * Short-timeout health check for the LAN bridge behind the Discord card.
 * Uses its own client so a wrong IP fails in ~2 s instead of the global 12 s.
 */
private fun pingBridge(host: String, port: Int): String {
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val url = "http://$host:${port.coerceIn(1, 65535)}/health"
    return runCatching {
        val request = okhttp3.Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@use tr("Мост ответил ошибкой ${response.code}", "Bridge replied ${response.code}")
            }
            val body = org.json.JSONObject(response.body?.string().orEmpty())
            if (body.optBoolean("connected")) {
                tr("Мост на связи, Discord подключён", "Bridge online, Discord connected")
            } else {
                tr("Мост работает, но Discord не запущен", "Bridge online, Discord is not running")
            }
        }
    }.getOrElse {
        tr("Мост не отвечает — проверь IP, порт и фаервол", "No response — check IP, port and firewall")
    }
}

package app.onyx.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onyx.AppController
import app.onyx.core.PrefsData
import app.onyx.ui.components.GlassCard
import app.onyx.ui.components.GlassPill
import app.onyx.ui.components.SectionLabel
import app.onyx.ui.theme.OnyxBlack
import app.onyx.ui.theme.OnyxDim
import app.onyx.ui.theme.OnyxFaint
import app.onyx.ui.theme.OnyxLine
import app.onyx.ui.theme.OnyxWhite
import kotlinx.coroutines.launch

/**
 * First-run setup. Android 13+ blocks notification access and "install unknown
 * apps" for sideloaded APKs until the user allows restricted settings; the
 * steps explain that instead of leaving a greyed-out switch.
 */
@Composable
fun OnboardingScreen(controller: AppController, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by controller.prefs.data.collectAsStateWithLifecycle(initialValue = PrefsData())
    var step by remember { mutableIntStateOf(0) }
    var listenerOn by remember { mutableStateOf(isListenerOn(context)) }
    var installAllowed by remember { mutableStateOf(canInstall(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        listenerOn = isListenerOn(context)
        installAllowed = canInstall(context)
    }

    val folderLauncher = rememberLauncherForActivityResult(OpenFolderTree()) { uri ->
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

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scope.launch {
                val found = controller.localLibrary.detectMediaFolders()
                if (found.isEmpty()) {
                    controller.toast(tr("Музыка не найдена", "No music found"))
                } else {
                    controller.localLibrary.addFolders(found)
                    controller.toast(tr("Найдено папок: ${found.size}", "Found ${found.size} folders"))
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(OnyxBlack)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Text(
                text = "ONYX",
                color = OnyxWhite,
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            Text(
                text = tr("настройка за три шага", "three-step setup"),
                color = OnyxFaint,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (index <= step) OnyxWhite else OnyxLine),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            when (step) {
                0 -> NotificationsStep(
                    listenerOn = listenerOn,
                    onOpen = { openNotificationAccess(context) },
                    onAppSettings = { openAppDetails(context) },
                )
                1 -> MusicStep(
                    prefs = prefs,
                    onChoose = { folderLauncher.launch(musicFolderUri()) },
                    onAuto = {
                        audioLauncher.launch(audioPermission)
                    },
                )
                else -> InstallStep(
                    installAllowed = installAllowed,
                    onOpen = { openInstallPermission(context) },
                    onAppSettings = { openAppDetails(context) },
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (step > 0) {
                    GlassPill(
                        text = tr("Назад", "Back"),
                        selected = false,
                        onClick = { step-- },
                    )
                }
                Spacer(Modifier.weight(1f))
                GlassPill(
                    text = if (step < 2) tr("Далее", "Next") else tr("Готово", "Done"),
                    selected = true,
                    onClick = {
                        if (step < 2) {
                            step++
                        } else {
                            // Use the app-level scope: the screen leaves the
                            // composition right after this, which cancels a
                            // rememberCoroutineScope and dropped the write.
                            controller.onboardingDone = true
                            controller.appScope.launch {
                                controller.prefs.setOnboardingDone(true)
                            }
                            onDone()
                        }
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = tr(
                    "Всё это можно изменить позже: Настройки → Доступы.",
                    "Everything can be changed later: Settings → Permissions.",
                ),
                color = OnyxFaint,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun NotificationsStep(
    listenerOn: Boolean,
    onOpen: () -> Unit,
    onAppSettings: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(tr("Шаг 1 · Уведомления", "Step 1 · Notifications"))
        Spacer(Modifier.height(10.dp))
        Text(
            text = tr(
                "ONYX читает медиа-уведомления, чтобы видеть, что играет в других приложениях.",
                "ONYX reads media notifications to see what other apps are playing.",
            ),
            color = OnyxDim,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(12.dp))
        StatusLine(ok = listenerOn, onText = tr("Доступ выдан", "Access granted"))
        Spacer(Modifier.height(12.dp))
        GlassPill(
            text = if (listenerOn) tr("Открыть настройки", "Open settings") else tr("Открыть доступ", "Open access"),
            selected = !listenerOn,
            onClick = onOpen,
        )
        if (!listenerOn) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = tr(
                    "Если переключатель серый — это «ограниченные настройки» Android. " +
                        "Откройте «Настройки приложения» → меню ⋮ → «Разрешить ограниченные настройки», " +
                        "затем включите доступ к уведомлениям.",
                    "If the switch is greyed out, Android restricted the setting. Open App settings → ⋮ → " +
                        "Allow restricted settings, then enable notification access.",
                ),
                color = OnyxFaint,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            GlassPill(
                text = tr("Настройки приложения", "App settings"),
                selected = false,
                onClick = onAppSettings,
            )
        }
    }
}

@Composable
private fun MusicStep(
    prefs: PrefsData,
    onChoose: () -> Unit,
    onAuto: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(tr("Шаг 2 · Музыка", "Step 2 · Music"))
        Spacer(Modifier.height(10.dp))
        Text(
            text = tr(
                "Добавьте папку с локальными треками. Корень хранилища Android выбрать не даст — " +
                    "откройте Music или Download и нажмите «Использовать эту папку».",
                "Add a folder with local tracks. Android will not allow the storage root — open Music " +
                    "or Download and tap Use this folder.",
            ),
            color = OnyxDim,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(12.dp))
        if (prefs.localFolders.isEmpty()) {
            Text(tr("Папки не выбраны", "No folders selected"), color = OnyxFaint, fontSize = 12.sp)
        } else {
            prefs.localFolders.forEach { folder ->
                Text(
                    text = "• " + folder.name.ifBlank { folder.uri },
                    color = OnyxWhite,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill(text = tr("Выбрать папку", "Choose folder"), selected = true, onClick = onChoose)
            GlassPill(text = tr("Найти автоматически", "Find automatically"), selected = false, onClick = onAuto)
        }
    }
}

@Composable
private fun InstallStep(
    installAllowed: Boolean,
    onOpen: () -> Unit,
    onAppSettings: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(tr("Шаг 3 · Обновления", "Step 3 · Updates"))
        Spacer(Modifier.height(10.dp))
        Text(
            text = tr(
                "Чтобы ONYX мог устанавливать свои обновления, разрешите установку из этого источника.",
                "Allow ONYX to install its own updates from this source.",
            ),
            color = OnyxDim,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(12.dp))
        StatusLine(ok = installAllowed, onText = tr("Установка разрешена", "Installation allowed"))
        Spacer(Modifier.height(12.dp))
        GlassPill(
            text = if (installAllowed) tr("Открыть настройки", "Open settings") else tr("Разрешить установку", "Allow installation"),
            selected = !installAllowed,
            onClick = onOpen,
        )
        if (!installAllowed) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = tr(
                    "Если пункт недоступен: «Настройки приложения» → ⋮ → «Разрешить ограниченные настройки».",
                    "If the option is unavailable: App settings → ⋮ → Allow restricted settings.",
                ),
                color = OnyxFaint,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            GlassPill(
                text = tr("Настройки приложения", "App settings"),
                selected = false,
                onClick = onAppSettings,
            )
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, onText: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(if (ok) OnyxWhite else OnyxDim),
        )
        Text(
            text = if (ok) onText else tr("Не выдано", "Not granted"),
            color = if (ok) OnyxWhite else OnyxDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 9.dp),
        )
    }
}

private fun isListenerOn(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun canInstall(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.packageManager.canRequestPackageInstalls()
    } else {
        true
    }

private fun openNotificationAccess(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openInstallPermission(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

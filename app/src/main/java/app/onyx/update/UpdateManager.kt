package app.onyx.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.onyx.BuildConfig
import app.onyx.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val url: String,
    val sha256: String,
    val notes: String,
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data object UpToDate : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    data class Installing(val info: UpdateInfo) : UpdateState()
    data object NeedsInstallPermission : UpdateState()
    data class Error(val message: String) : UpdateState()
}

/**
 * Self-update for the sideloaded APK. Reads the small `android.json` asset from
 * the latest GitHub release, compares versionCode, downloads the APK, checks
 * SHA-256 and hands it to PackageInstaller.
 *
 * The manifest is public and contains no secrets:
 * https://github.com/ysl021/onyx/releases/latest/download/android.json
 */
class UpdateManager(private val context: Context) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        if (BuildConfig.TRANSITIONAL) {
            _state.value = UpdateState.Error(
                "Переходная сборка: экспортируйте данные и установите 0.9.0 вручную",
            )
            return@withContext _state.value
        }
        _state.value = UpdateState.Checking
        try {
            val request = Request.Builder()
                .url(MANIFEST_URL)
                .header("User-Agent", "ONYX/${BuildConfig.VERSION_NAME}")
                .build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _state.value = UpdateState.Error("Сервер обновлений: HTTP ${response.code}")
                    return@withContext _state.value
                }
                val root = JSONObject(response.body?.string().orEmpty())
                val code = root.optInt("versionCode", 0)
                val name = root.optString("versionName", "")
                val url = root.optString("url", "")
                val sha = root.optString("sha256", "")
                val notes = root.optString("notes", "")
                _state.value = when {
                    code <= BuildConfig.VERSION_CODE -> UpdateState.UpToDate
                    url.isBlank() -> UpdateState.Error("В манифесте нет ссылки на APK")
                    else -> UpdateState.Available(UpdateInfo(name, code, url, sha, notes))
                }
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Не удалось проверить обновления")
        }
        _state.value
    }

    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun openInstallPermission() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openAppSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    suspend fun downloadAndInstall(info: UpdateInfo): UpdateState = withContext(Dispatchers.IO) {
        if (BuildConfig.TRANSITIONAL) {
            _state.value = UpdateState.Error(
                "Переходная сборка: экспортируйте данные и установите 0.9.0 вручную",
            )
            return@withContext _state.value
        }
        if (!canInstall()) {
            _state.value = UpdateState.NeedsInstallPermission
            return@withContext _state.value
        }
        try {
            val apk = File(context.cacheDir, "onyx-${info.versionCode}.apk")
            if (!apk.exists() || !shaMatches(apk, info.sha256)) {
                apk.delete()
                val request = Request.Builder()
                    .url(info.url)
                    .header("User-Agent", "ONYX/${BuildConfig.VERSION_NAME}")
                    .build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        _state.value = UpdateState.Error("Скачивание: HTTP ${response.code}")
                        return@withContext _state.value
                    }
                    val total = response.body?.contentLength() ?: -1L
                    response.body?.byteStream()?.use { input ->
                        apk.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var read = 0L
                            var lastPercent = -1
                            while (true) {
                                val count = input.read(buffer)
                                if (count <= 0) break
                                out.write(buffer, 0, count)
                                read += count
                                if (total > 0) {
                                    val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        _state.value = UpdateState.Downloading(percent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!shaMatches(apk, info.sha256)) {
                apk.delete()
                _state.value = UpdateState.Error("SHA-256 не совпал")
                return@withContext _state.value
            }
            install(apk)
            _state.value = UpdateState.Installing(info)
            _state.value
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Не удалось скачать обновление")
            _state.value
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("onyx.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java)
                .putExtra(UpdateReceiver.EXTRA_SESSION, sessionId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun shaMatches(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }

    companion object {
        const val MANIFEST_URL =
            "https://github.com/ysl021/onyx/releases/latest/download/android.json"
    }
}

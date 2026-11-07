package app.onyx

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import app.onyx.buds.BudsService
import app.onyx.audio.SoundEffects
import app.onyx.core.Prefs
import app.onyx.lyrics.LyricsRepository
import app.onyx.media.MediaRepository
import app.onyx.media.OutputRouter
import app.onyx.media.HeadphonesRepository
import app.onyx.music.LocalLibrary
import app.onyx.music.LocalPlaybackController
import app.onyx.spotify.SpotifyClient
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AppController private constructor(context: Context) {

    val appContext: Context = context.applicationContext
    val prefs = Prefs(appContext)
    val media = MediaRepository.get(appContext)
    val router = OutputRouter(appContext)
    val headphones = HeadphonesRepository(appContext)
    val spotify = SpotifyClient(appContext, prefs)
    val sound = SoundEffects(appContext, prefs)
    val localLibrary = LocalLibrary(appContext, prefs)
    val localPlayer = LocalPlaybackController(appContext, localLibrary)
    val lyrics = LyricsRepository(appContext, prefs) { spotify.token() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    fun start() {
        media.start()
        headphones.refresh()
        sound.init()
        localPlayer.connect()
        startBudsIfAllowed()
        if (watcher != null) return
        watcher = scope.launch {
            media.state
                .map { it.now }
                .distinctUntilChangedBy {
                    listOf(
                        it.packageName,
                        it.title,
                        it.artist,
                        it.durationMs,
                        it.mediaId,
                    )
                }
                .collect { lyrics.sync(it) }
        }
    }

    fun toast(message: String) {
        _messages.tryEmit(message)
    }

    /**
     * Never reconnect in the background just because the app is open. Start the
     * SPP service only when the Buds are actually connected as an audio device;
     * otherwise the user taps "Подключить" explicitly.
     */
    fun startBudsIfAllowed() {
        val granted = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted && budsAudioConnected()) {
            BudsService.ensure(appContext)
        }
    }

    private fun budsAudioConnected(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val am = appContext.getSystemService(AudioManager::class.java) ?: return false
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
                val isBt = d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                isBt && d.productName?.toString()?.contains("buds", ignoreCase = true) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun close() {
        watcher?.cancel()
        watcher = null
        lyrics.close()
        sound.close()
        localPlayer.close()
        localLibrary.close()
        media.close()
    }

    companion object {
        @Volatile
        private var inst: AppController? = null

        fun get(context: Context): AppController =
            inst ?: synchronized(this) {
                inst ?: AppController(context).also { inst = it }
            }
    }
}

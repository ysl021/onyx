package app.onyx.audio

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class VolumeState(
    val fraction: Float = 0f,
    val percent: Int = 0,
    val max: Int = 15,
)

/** Controls the media stream for the phone, Bluetooth and Buds alike. */
class VolumeController(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(VolumeState())
    val state: StateFlow<VolumeState> = _state.asStateFlow()
    private var lastSetAt = 0L

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    init {
        refresh()
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                observer,
            )
        }
    }

    fun refresh() {
        if (SystemClock.elapsedRealtime() - lastSetAt < 700L) return
        val am = audio ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val value = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        _state.value = VolumeState(
            fraction = value.toFloat() / max,
            percent = (value * 100f / max).roundToInt(),
            max = max,
        )
    }

    fun set(fraction: Float, stepPercent: Int) {
        val am = audio ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val step = stepPercent.coerceIn(1, 100) / 100f
        val snapped = (fraction.coerceIn(0f, 1f) / step).roundToInt() * step
        val value = (snapped * max).roundToInt().coerceIn(0, max)
        lastSetAt = SystemClock.elapsedRealtime()
        runCatching {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        }
        _state.value = VolumeState(
            fraction = snapped.coerceIn(0f, 1f),
            percent = (snapped * 100f).roundToInt().coerceIn(0, 100),
            max = max,
        )
    }

    fun close() {
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
    }
}

package app.onyx.audio

import android.content.Context
import android.media.audiofx.Equalizer
import app.onyx.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EqBand(
    val index: Int,
    val freqHz: Int,
    val level: Short,
)

data class EqState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val presets: List<String> = emptyList(),
    val preset: Int = 0,
    val bands: List<EqBand> = emptyList(),
    val minLevel: Short = -1500,
    val maxLevel: Short = 1500,
    val error: String? = null,
)

class SoundEffects(
    private val context: Context,
    private val prefs: Prefs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state.asStateFlow()

    private var equalizer: Equalizer? = null
    private var sessionId: Int = 0

    fun init() {
        scope.launch {
            attach(0, restore = true)
        }
    }

    /** Re-attach to the local player's audio session once 0.3 playback starts. */
    suspend fun attach(session: Int, restore: Boolean = true) {
        if (session == sessionId && equalizer != null) return
        release()
        sessionId = session
        try {
            val e = Equalizer(0, session)
            equalizer = e
            val range = e.bandLevelRange
            val n = e.numberOfBands.toInt().coerceAtLeast(0)
            val presets = (0 until e.numberOfPresets.toInt()).mapNotNull { i ->
                runCatching { e.getPresetName(i.toShort()) }.getOrNull()
            }
            val bands = (0 until n).map { i ->
                EqBand(
                    index = i,
                    freqHz = (e.getCenterFreq(i.toShort()).toInt() / 1000),
                    level = e.getBandLevel(i.toShort()),
                )
            }
            _state.value = EqState(
                available = true,
                enabled = e.enabled,
                presets = presets,
                preset = if (presets.isEmpty()) -1 else 0,
                bands = bands,
                minLevel = range[0],
                maxLevel = range[1],
                error = null,
            )
            e.enabled = false
            if (restore) {
                val p = prefs.snapshot()
                applyStored(p.eqEnabled, p.eqPreset, p.eqLevels)
            }
        } catch (t: Throwable) {
            equalizer = null
            _state.value = EqState(
                available = false,
                error = "Эквалайзер недоступен на этом устройстве: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    private fun applyStored(enabled: Boolean, preset: Int, levels: String) {
        val e = equalizer ?: return
        try {
            val parsed = levels.split(',').mapNotNull { it.trim().toShortOrNull() }
            if (parsed.size == _state.value.bands.size && parsed.isNotEmpty()) {
                parsed.forEachIndexed { i, level ->
                    e.setBandLevel(i.toShort(), level)
                }
                _state.value = _state.value.copy(preset = -1)
            } else if (preset in _state.value.presets.indices) {
                e.usePreset(preset.toShort())
                _state.value = _state.value.copy(preset = preset)
            }
            e.enabled = enabled
            _state.value = _state.value.copy(enabled = enabled)
            readBands()
        } catch (_: Throwable) {
        }
    }

    fun setEnabled(enabled: Boolean) {
        val e = equalizer ?: return
        try {
            e.enabled = enabled
            _state.value = _state.value.copy(enabled = enabled)
            persist()
        } catch (_: Throwable) {
        }
    }

    fun setPreset(index: Int) {
        val e = equalizer ?: return
        try {
            e.usePreset(index.toShort())
            _state.value = _state.value.copy(preset = index)
            readBands()
            persist()
        } catch (_: Throwable) {
        }
    }

    fun setBand(index: Int, level: Short, persist: Boolean = true) {
        val e = equalizer ?: return
        try {
            e.setBandLevel(index.toShort(), level)
            _state.value = _state.value.copy(preset = -1)
            readBands()
            if (persist) persist()
        } catch (_: Throwable) {
        }
    }

    fun persistNow() = persist()

    private fun readBands() {
        val e = equalizer ?: return
        val bands = _state.value.bands.map { b ->
            runCatching { b.copy(level = e.getBandLevel(b.index.toShort())) }.getOrDefault(b)
        }
        _state.value = _state.value.copy(bands = bands)
    }

    private fun persist() {
        val s = _state.value
        val levels = s.bands.joinToString(",") { it.level.toString() }
        scope.launch { prefs.setEq(s.enabled, s.preset, levels) }
    }

    fun release() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
        } catch (_: Throwable) {
        }
        equalizer = null
    }

    fun close() {
        release()
        scope.cancel()
    }
}

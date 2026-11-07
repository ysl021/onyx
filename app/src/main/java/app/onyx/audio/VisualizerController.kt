package app.onyx.audio

import android.content.Context
import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VisualizerController(context: Context) {

    private val _wave = MutableStateFlow(FloatArray(0))
    val wave: StateFlow<FloatArray> = _wave.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var visualizer: Visualizer? = null

    fun start() {
        if (visualizer != null) return
        try {
            val v = Visualizer(0)
            val size = Visualizer.getCaptureSizeRange()[1].coerceAtLeast(256)
            v.captureSize = size
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (waveform == null) return
                        val step = (waveform.size / 96).coerceAtLeast(1)
                        val out = ArrayList<Float>(96)
                        var i = 0
                        while (i < waveform.size) {
                            var sum = 0f
                            var n = 0
                            var j = i
                            while (j < minOf(i + step, waveform.size)) {
                                sum += ((waveform[j].toInt() and 0xFF) - 128) / 128f
                                n++
                                j++
                            }
                            out += if (n > 0) sum / n else 0f
                            i += step
                        }
                        _wave.value = out.toFloatArray()
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) = Unit
                },
                Visualizer.getMaxCaptureRate() / 2,
                true,
                false,
            )
            v.enabled = true
            visualizer = v
            _error.value = null
        } catch (t: Throwable) {
            _error.value = when (t) {
                is SecurityException -> "Нужно разрешение на микрофон (Audio Visualizer)."
                else -> "Визуализатор недоступен: ${t.message ?: t.javaClass.simpleName}"
            }
            visualizer = null
        }
    }

    fun stop() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Throwable) {
        }
        visualizer = null
    }
}

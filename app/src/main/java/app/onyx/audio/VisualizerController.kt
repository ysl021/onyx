package app.onyx.audio

import android.content.Context
import android.media.audiofx.Visualizer
import app.onyx.ui.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VisualizerController(context: Context) {

    private val _wave = MutableStateFlow(FloatArray(0))
    val wave: StateFlow<FloatArray> = _wave.asStateFlow()

    private val _fft = MutableStateFlow(FloatArray(0))
    val fft: StateFlow<FloatArray> = _fft.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var visualizer: Visualizer? = null
    private var smoothed = FloatArray(64)

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
                    ) {
                        if (fft == null || fft.size < 4) return
                        val n = fft.size / 2
                        val out = FloatArray(64)
                        var max = 0.0001f
                        for (i in out.indices) {
                            val frac = i.toDouble() / (out.size - 1)
                            // Log-ish spacing: more resolution in the low end.
                            val bin = (Math.pow(n.toDouble(), frac) - 1.0)
                                .toInt().coerceIn(0, n - 1)
                            val re = fft[bin * 2].toInt()
                            val im = fft[bin * 2 + 1].toInt()
                            val mag = kotlin.math.sqrt((re * re + im * im).toFloat()) / 128f
                            out[i] = mag
                            if (mag > max) max = mag
                        }
                        for (i in out.indices) {
                            val norm = (out[i] / max).coerceIn(0f, 1f)
                            smoothed[i] = smoothed[i] * 0.65f + norm * 0.35f
                        }
                        _fft.value = smoothed.copyOf()
                    }
                },
                Visualizer.getMaxCaptureRate(),
                true,
                true,
            )
            v.enabled = true
            visualizer = v
            _error.value = null
        } catch (t: Throwable) {
            _error.value = when (t) {
                is SecurityException -> tr("Нужно разрешение на микрофон (Audio Visualizer).", "Microphone permission is required (Audio Visualizer).")
                else -> tr("Визуализатор недоступен", "Visualizer is unavailable") + ": ${t.message ?: t.javaClass.simpleName}"
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

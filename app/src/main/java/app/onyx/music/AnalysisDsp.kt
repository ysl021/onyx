package app.onyx.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * Offline analysis of a decoded track: tempo, key, loudness and a few spectral
 * proportions. Everything here is pure Kotlin over a mono float buffer, so the
 * maths can be unit-tested on a JVM without Android or a device.
 */
@Serializable
data class TrackFeatures(
    val bpm: Double? = null,
    val key: String? = null,
    val camelot: String? = null,
    val lufs: Double? = null,
    val energy: Float = 0f,
    val bass: Float = 0f,
    val vocal: Float = 0f,
    val bright: Float = 0f,
)

object AnalysisDsp {

    private const val FFT_SIZE = 4096
    private const val ENV_WINDOW = 1024
    private const val ENV_HOP = 512

    private val MAJOR = floatArrayOf(
        6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f,
        2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f,
    )
    private val MINOR = floatArrayOf(
        6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f,
        2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f,
    )
    private val NAMES = arrayOf(
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B",
    )
    private val CAMELOT_MAJOR = arrayOf(
        "8B", "3B", "10B", "5B", "12B", "7B", "2B", "9B", "4B", "11B", "6B", "1B",
    )
    private val CAMELOT_MINOR = arrayOf(
        "5A", "12A", "7A", "2A", "9A", "4A", "11A", "6A", "1A", "8A", "3A", "10A",
    )

    fun analyze(samples: FloatArray, sampleRate: Int): TrackFeatures {
        if (samples.size < sampleRate / 2 || sampleRate < 8_000) return TrackFeatures()
        val mono = if (sampleRate > 24_000) decimate(samples, sampleRate, 22_050) else samples
        val sr = if (sampleRate > 24_000) 22_050 else sampleRate
        val env = envelope(mono, sr)
        val bpm = estimateBpm(env.first, env.second)
        val key = estimateKey(mono, sr)
        return TrackFeatures(
            bpm = bpm,
            key = key?.first,
            camelot = key?.second,
            lufs = integratedLoudness(mono, sr),
            energy = energyOf(mono),
            bass = env.third[0],
            vocal = env.third[1],
            bright = env.third[2],
        )
    }

    /* --------------------------------- tempo --------------------------------- */

    /** Returns (onset envelope, frames per second, band shares). */
    private fun envelope(samples: FloatArray, sampleRate: Int): Triple<FloatArray, Double, FloatArray> {
        val frames = if (samples.size < ENV_WINDOW) 0 else (samples.size - ENV_WINDOW) / ENV_HOP + 1
        if (frames <= 0) return Triple(FloatArray(0), sampleRate.toDouble() / ENV_HOP, FloatArray(3))
        val rms = FloatArray(frames)
        for (frame in 0 until frames) {
            val start = frame * ENV_HOP
            var sum = 0.0
            for (i in start until start + ENV_WINDOW) {
                val v = samples[i].toDouble()
                sum += v * v
            }
            rms[frame] = sqrt(sum / ENV_WINDOW).toFloat()
        }
        val flux = FloatArray(frames)
        for (i in 1 until frames) flux[i] = max(0f, rms[i] - rms[i - 1])

        // Spectral proportions from a few widely spaced FFT frames.
        val bass = DoubleArray(3)
        val win = 4096
        var pos = 0
        while (pos + win <= samples.size) {
            val re = FloatArray(win) { samples[pos + it] * hann(it, win) }
            val im = FloatArray(win)
            fft(re, im)
            for (bin in 1 until win / 2) {
                val f = bin.toDouble() * sampleRate / win
                if (f < 20 || f > 12_000) continue
                val mag = sqrt(re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin])
                when {
                    f < 150 -> bass[0] += mag
                    f < 3_400 -> bass[1] += mag
                    f > 6_000 -> bass[2] += mag
                }
            }
            pos += win
        }
        val total = (bass[0] + bass[1] + bass[2]).coerceAtLeast(1e-9)
        val shares = floatArrayOf(
            (bass[0] / total * 3.0).coerceIn(0.0, 1.0).toFloat(),
            (bass[1] / total * 2.2).coerceIn(0.0, 1.0).toFloat(),
            (bass[2] / total * 2.5).coerceIn(0.0, 1.0).toFloat(),
        )
        return Triple(flux, sampleRate.toDouble() / ENV_HOP, shares)
    }

    internal fun estimateBpm(flux: FloatArray, fps: Double): Double? {
        if (flux.size < 24) return null
        val mean = flux.average()
        val x = DoubleArray(flux.size) { flux[it] - mean }
        val minLag = (fps * 60.0 / 200.0).roundToInt().coerceAtLeast(2)
        val maxLag = min((fps * 60.0 / 50.0).roundToInt(), x.size / 2)
        if (maxLag <= minLag) return null

        val scores = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until x.size - lag) sum += x[i] * x[i + lag]
            val norm = (x.size - lag).coerceAtLeast(1)
            val bpm = 60.0 * fps / lag
            val prior = exp(-0.5 * (ln(bpm / 120.0) / 0.42).let { it * it })
            scores[lag] = sum / norm * prior
        }
        var best = minLag
        for (lag in minLag..maxLag) if (scores[lag] > scores[best]) best = lag
        if (scores[best] <= 0.0) return null

        // Parabolic refinement around the winning lag.
        val left = scores[(best - 1).coerceAtLeast(minLag)]
        val right = scores[(best + 1).coerceAtMost(maxLag)]
        val denom = left - 2.0 * scores[best] + right
        val delta = if (abs(denom) > 1e-9) 0.5 * (left - right) / denom else 0.0
        val refined = best + delta.coerceIn(-0.5, 0.5)
        var bpm = 60.0 * fps / refined
        while (bpm < 70) bpm *= 2
        while (bpm > 180) bpm /= 2
        return bpm
    }

    /* ---------------------------------- key ---------------------------------- */

    private fun estimateKey(samples: FloatArray, sampleRate: Int): Pair<String, String>? {
        val chroma = DoubleArray(12)
        val win = FFT_SIZE
        if (samples.size < win) return null
        var pos = 0
        while (pos + win <= samples.size) {
            val re = FloatArray(win) { samples[pos + it] * hann(it, win) }
            val im = FloatArray(win)
            fft(re, im)
            for (bin in 1 until win / 2) {
                val f = bin.toDouble() * sampleRate / win
                if (f < 65 || f > 2_500) continue
                val mag = sqrt(re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin])
                val midi = 69.0 + 12.0 * log2(f / 440.0)
                val pc = ((midi.roundToInt() % 12) + 12) % 12
                chroma[pc] += mag
            }
            pos += win
        }
        val sum = chroma.sum()
        if (sum <= 0.0) return null
        for (i in chroma.indices) chroma[i] /= sum

        var bestScore = -2.0
        var bestPc = 0
        var bestMinor = false
        for (pc in 0 until 12) {
            val major = correlation(chroma, MAJOR, pc)
            val minor = correlation(chroma, MINOR, pc)
            if (major > bestScore) {
                bestScore = major
                bestPc = pc
                bestMinor = false
            }
            if (minor > bestScore) {
                bestScore = minor
                bestPc = pc
                bestMinor = true
            }
        }
        if (bestScore <= 0.15) return null
        val name = NAMES[bestPc] + (if (bestMinor) " minor" else " major")
        val camelot = if (bestMinor) CAMELOT_MINOR[bestPc] else CAMELOT_MAJOR[bestPc]
        return name to camelot
    }

    private fun correlation(chroma: DoubleArray, profile: FloatArray, root: Int): Double {
        val n = 12
        var meanX = 0.0
        var meanY = 0.0
        for (i in 0 until n) {
            meanX += chroma[i]
            meanY += profile[i]
        }
        meanX /= n
        meanY /= n
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in 0 until n) {
            val x = chroma[(root + i) % n] - meanX
            val y = profile[i] - meanY
            num += x * y
            dx += x * x
            dy += y * y
        }
        return if (dx <= 1e-12 || dy <= 1e-12) 0.0 else num / sqrt(dx * dy)
    }

    /* --------------------------------- loudness ------------------------------- */

    internal fun integratedLoudness(samples: FloatArray, sampleRate: Int): Double? {
        if (samples.size < sampleRate / 2) return null
        val shelf = highShelf(1681.974450955533, 3.999843853973347, 0.7071752369554196, sampleRate)
        val highPass = highPass(38.13547087602444, 0.5003270373238773, sampleRate)
        val filtered = FloatArray(samples.size)
        for (i in samples.indices) filtered[i] = highPass.process(shelf.process(samples[i]))

        val block = (sampleRate * 0.4).roundToInt().coerceAtLeast(1)
        val step = (sampleRate * 0.1).roundToInt().coerceAtLeast(1)
        if (filtered.size < block) return null
        val powers = ArrayList<Double>()
        var pos = 0
        while (pos + block <= filtered.size) {
            var sum = 0.0
            for (i in pos until pos + block) {
                val v = filtered[i].toDouble()
                sum += v * v
            }
            powers += sum / block
            pos += step
        }
        if (powers.isEmpty()) return null
        val absGated = powers.filter { it > 0.0 && -0.691 + 10.0 * log10(it) > -70.0 }
        if (absGated.isEmpty()) return null
        val meanAbs = absGated.average()
        val relative = -0.691 + 10.0 * log10(meanAbs) - 10.0
        val kept = absGated.filter { -0.691 + 10.0 * log10(it) > relative }
        if (kept.isEmpty()) return null
        return -0.691 + 10.0 * log10(kept.average())
    }

    private fun energyOf(samples: FloatArray): Float {
        var sum = 0.0
        for (v in samples) sum += v.toDouble() * v
        val rms = sqrt(sum / samples.size.coerceAtLeast(1))
        val db = 20.0 * log10(rms.coerceAtLeast(1e-6))
        return ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    }

    /* --------------------------------- helpers -------------------------------- */

    private fun hann(i: Int, n: Int): Float =
        (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))).toFloat()

    private fun decimate(samples: FloatArray, from: Int, to: Int): FloatArray {
        val factor = (from / to).coerceAtLeast(1)
        val out = FloatArray(samples.size / factor)
        for (i in out.indices) {
            var sum = 0f
            val start = i * factor
            for (j in 0 until factor) sum += samples[start + j]
            out[i] = sum / factor
        }
        return out
    }

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(input: Float): Float {
            val x = input.toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y.toFloat()
        }
    }

    private fun highShelf(f0: Double, gainDb: Double, q: Double, sr: Int): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / sr
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val sqrtA = sqrt(a)
        val b0 = a * ((a + 1) + (a - 1) * cosW + 2 * sqrtA * alpha)
        val b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
        val b2 = a * ((a + 1) + (a - 1) * cosW - 2 * sqrtA * alpha)
        val a0 = (a + 1) - (a - 1) * cosW + 2 * sqrtA * alpha
        val a1 = 2 * ((a - 1) - (a + 1) * cosW)
        val a2 = (a + 1) - (a - 1) * cosW - 2 * sqrtA * alpha
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun highPass(f0: Double, q: Double, sr: Int): Biquad {
        val w0 = 2.0 * PI * f0 / sr
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val b0 = (1 + cosW) / 2
        val b1 = -(1 + cosW)
        val b2 = b0
        val a0 = 1 + alpha
        val a1 = -2 * cosW
        val a2 = 1 - alpha
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}

package app.onyx.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class AnalysisDspTest {

    private val sr = 22_050

    private fun clickTrack(bpm: Double, seconds: Double, sampleRate: Int = sr): FloatArray {
        val out = FloatArray((seconds * sampleRate).toInt())
        val period = (60.0 / bpm * sampleRate).toInt().coerceAtLeast(1)
        val random = java.util.Random(7)
        var pos = 0
        while (pos < out.size) {
            val grain = (0.03 * sampleRate).toInt()
            for (i in 0 until grain) {
                val index = pos + i
                if (index >= out.size) break
                out[index] = (random.nextFloat() * 2f - 1f) *
                    exp(-i / (0.006 * sampleRate)).toFloat()
            }
            pos += period
        }
        return out
    }

    private fun triad(root: Double, third: Double, fifth: Double, seconds: Double): FloatArray {
        val out = FloatArray((seconds * sr).toInt())
        for (i in out.indices) {
            val t = i.toDouble() / sr
            out[i] = (
                0.5 * sin(2 * PI * root * t) +
                    0.35 * sin(2 * PI * third * t) +
                    0.35 * sin(2 * PI * fifth * t) +
                    0.2 * sin(2 * PI * root * 2 * t) +
                    0.12 * sin(2 * PI * third * 2 * t)
                ).toFloat() * 0.35f
        }
        return out
    }

    @Test
    fun detects120Bpm() {
        val features = AnalysisDsp.analyze(clickTrack(120.0, 20.0), sr)
        assertNotNull(features.bpm)
        assertEquals(120.0, features.bpm!!, 3.0)
    }

    @Test
    fun detects90Bpm() {
        val features = AnalysisDsp.analyze(clickTrack(90.0, 24.0), sr)
        assertNotNull(features.bpm)
        assertEquals(93.0, features.bpm!!, 6.0)
    }

    @Test
    fun detectsAMinor() {
        // A3, C4, E4 with a couple of harmonics.
        val features = AnalysisDsp.analyze(triad(220.0, 261.63, 329.63, 12.0), sr)
        assertEquals("A minor", features.key)
        assertEquals("8A", features.camelot)
    }

    @Test
    fun loudnessOfAOneKilohertzSine() {
        val rate = 48_000
        val samples = FloatArray(rate * 5) {
            (0.1 * sin(2 * PI * 1000.0 * it / rate)).toFloat()
        }
        val features = AnalysisDsp.analyze(samples, rate)
        assertNotNull(features.lufs)
        // A 1 kHz sine at -20 dBFS RMS is about -23 LUFS.
        assertEquals(-23.0, features.lufs!!, 2.0)
    }

    @Test
    fun energyAndBandsAreSane() {
        val features = AnalysisDsp.analyze(clickTrack(128.0, 8.0), sr)
        assertTrue(features.energy in 0f..1f)
        assertTrue(features.bass in 0f..1f)
    }
}

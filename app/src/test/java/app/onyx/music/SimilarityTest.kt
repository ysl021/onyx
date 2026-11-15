package app.onyx.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarityTest {

    private fun features(
        bpm: Double = 120.0,
        camelot: String = "8A",
        energy: Float = 0.5f,
    ) = TrackFeatures(
        bpm = bpm,
        camelot = camelot,
        lufs = -9.0,
        energy = energy,
        bass = 0.5f,
        vocal = 0.4f,
        bright = 0.4f,
    )

    private fun track(id: String, title: String, artist: String = "A") = LocalTrack(
        id = id,
        uri = "content://track/$id",
        title = title,
        artist = artist,
        durationMs = 180_000,
    )

    @Test
    fun camelotCompatibility() {
        assertEquals(1f, Similarity.keyCompat("8A", "8A")!!, 0.001f)
        assertEquals(0.85f, Similarity.keyCompat("8A", "8B")!!, 0.001f)
        assertEquals(0.8f, Similarity.keyCompat("8A", "9A")!!, 0.001f)
        assertEquals(0.15f, Similarity.keyCompat("8A", "10B")!!, 0.001f)
    }

    @Test
    fun tempoToleratesHalfAndDoubleTime() {
        assertTrue(Similarity.bpmClose(120.0, 120.0)!! > 0.99f)
        assertTrue(Similarity.bpmClose(120.0, 60.0)!! > 0.99f)
        assertTrue(Similarity.bpmClose(120.0, 174.0)!! < 0.5f)
    }

    @Test
    fun findOrdersByClosenessAndDropsTheRest() {
        val seed = track("1", "Seed")
        val close = track("2", "Close")
        val near = track("3", "Near")
        val far = track("4", "Far")
        val map = mapOf(
            "1" to features(bpm = 120.0, camelot = "8A", energy = 0.5f),
            "2" to features(bpm = 120.0, camelot = "8A", energy = 0.5f),
            "3" to features(bpm = 118.0, camelot = "7A", energy = 0.45f),
            "4" to features(bpm = 174.0, camelot = "3B", energy = 0.95f),
        )
        val result = Similarity.find(seed, listOf(seed, close, near, far), { map[it.id] })
        assertEquals(listOf("Close", "Near"), result.map { it.track.title })
    }

    @Test
    fun sessionPrefersTheMood() {
        val calm = track("1", "Calm")
        val loud = track("2", "Loud")
        val map = mapOf(
            "1" to features(bpm = 90.0, energy = 0.25f),
            "2" to features(bpm = 128.0, energy = 0.95f),
        )
        val result = Similarity.buildSession(listOf(calm, loud), { map[it.id] }, Mood.Calm, 30)
        assertTrue(result.isNotEmpty())
        assertEquals("Calm", result.first().track.title)
    }
}

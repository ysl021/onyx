package app.onyx.youtube

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Cuts one slice out of an audio file without re-encoding: MediaExtractor reads
 * the compressed samples and MediaMuxer writes them into a fresh container.
 *
 * This is the "physical" split — every chapter becomes a real file on disk that
 * any other player can open. AAC/M4A stays M4A, Opus/Vorbis is written to WebM.
 */
object AudioSplitter {

    class SplitException(message: String) : Exception(message)

    fun split(input: File, output: File, startMs: Long, endMs: Long) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var mp4Output = false
        try {
            extractor.setDataSource(input.absolutePath)

            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            var mime = ""
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val formatMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (formatMime.startsWith("audio/")) {
                    trackIndex = index
                    trackFormat = format
                    mime = formatMime
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) {
                throw SplitException("no audio track in ${input.name}")
            }
            if (!trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                // Some muxers refuse a track without it; 256 KB is safely above
                // any AAC/Opus frame.
                trackFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            }

            val outputFormat = when (mime) {
                "audio/opus", "audio/vorbis" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            mp4Output = outputFormat == MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4

            extractor.selectTrack(trackIndex)
            val startUs = (startMs * 1000L).coerceAtLeast(0L)
            val endUs = if (endMs > startMs) endMs * 1000L else Long.MAX_VALUE
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val created = MediaMuxer(output.absolutePath, outputFormat)
            muxer = created
            val outTrack = created.addTrack(trackFormat)
            created.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocateDirect(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var written = false
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L) break
                if (sampleTimeUs >= endUs) break
                val pts = (sampleTimeUs - startUs).coerceAtLeast(0L)
                info.set(0, size, pts, extractor.sampleFlags)
                created.writeSampleData(outTrack, buffer, info)
                written = true
                if (!extractor.advance()) break
            }
            if (!written) throw SplitException("no samples in ${startMs}..${endMs}")
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            if (mp4Output) {
                runCatching { Mp4Normalizer.normalize(output) }
            }
            if (output.length() <= 0L) runCatching { output.delete() }
        }
    }
}

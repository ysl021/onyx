package app.onyx.music

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a local file once and hands the PCM to [AnalysisDsp].
 *
 * Only SAF/MediaStore URIs are supported: streaming URLs are intentionally
 * skipped, because downloading a whole track just to learn its tempo is not a
 * background task the phone should do behind the user's back.
 */
object TrackAnalyzer {

    data class Decoded(val samples: FloatArray, val sampleRate: Int)

    suspend fun analyze(context: Context, uri: String, maxSeconds: Int = 90): TrackFeatures {
        val decoded = withContext(Dispatchers.IO) { decode(context, Uri.parse(uri), maxSeconds) }
        return withContext(Dispatchers.Default) { AnalysisDsp.analyze(decoded.samples, decoded.sampleRate) }
    }

    private fun decode(context: Context, uri: Uri, maxSeconds: Int): Decoded {
        require(uri.scheme == "content" || uri.scheme == "file") {
            "Анализ работает только для локальных файлов"
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length > 0) {
                    extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                } else {
                    extractor.setDataSource(afd.fileDescriptor)
                }
            } ?: error("Не удалось открыть файл")

            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = format
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) error("В файле нет аудиодорожки")
            extractor.selectTrack(trackIndex)
            // Start a little inside long files: intros and silence make tempo
            // and key estimates noisy, and 90 seconds is plenty for both.
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            if (durationUs > 180_000_000L) {
                runCatching {
                    extractor.seekTo((durationUs * 0.2).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }
            }

            val mime = inputFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = if (inputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                inputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            // Cap the mono buffer at ~17 MB even for 96 kHz hi-res files: the
            // DSP downsamples to 22 kHz anyway, so a shorter window is enough.
            var maxFrames = maxSeconds * minOf(sampleRate, 48_000)
            val sink = Sink(minOf(maxFrames, sampleRate * 8))

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var loops = 0
            while (!outputDone && loops < 200_000) {
                loops++
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val input = decoder.getInputBuffer(inIndex)
                        if (input == null) {
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        maxFrames = maxSeconds * minOf(sampleRate, 48_000)
                    }
                    in 0..Int.MAX_VALUE -> {
                        val buffer = decoder.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            readPcm(buffer, encoding, channels, sink, maxFrames)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (sink.size >= maxFrames) outputDone = true
                    }
                }
            }
            if (sink.size < sampleRate / 2) error("Слишком короткий фрагмент")
            return Decoded(sink.toArray(), sampleRate)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun readPcm(
        buffer: ByteBuffer,
        encoding: Int,
        channels: Int,
        sink: Sink,
        maxFrames: Int,
    ) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                val frames = floats.remaining() / channels
                for (frame in 0 until frames) {
                    if (sink.size >= maxFrames) return
                    var sum = 0f
                    for (ch in 0 until channels) sum += floats.get()
                    sink.add((sum / channels).coerceIn(-1f, 1f))
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val frames = buffer.remaining() / channels
                for (frame in 0 until frames) {
                    if (sink.size >= maxFrames) return
                    var sum = 0f
                    for (ch in 0 until channels) {
                        sum += (buffer.get().toInt() and 0xFF) / 128f - 1f
                    }
                    sink.add((sum / channels).coerceIn(-1f, 1f))
                }
            }
            else -> {
                val shorts = buffer.asShortBuffer()
                val frames = shorts.remaining() / channels
                for (frame in 0 until frames) {
                    if (sink.size >= maxFrames) return
                    var sum = 0f
                    for (ch in 0 until channels) sum += shorts.get() / 32_768f
                    sink.add((sum / channels).coerceIn(-1f, 1f))
                }
            }
        }
    }

    private class Sink(initial: Int) {
        private var data = FloatArray(initial.coerceAtLeast(1024))
        var size = 0
            private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}

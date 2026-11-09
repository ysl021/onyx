package app.onyx.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Streams a remote file to disk with byte progress; cancelling the coroutine
 * aborts every request.
 *
 * YouTube throttles a single googlevideo connection hard (often ~30-50 KB/s),
 * while the same URL happily serves byte ranges in parallel. Large files are
 * therefore fetched as several ranged requests written into one preallocated
 * file, which is typically several times faster. Servers that ignore Range fall
 * back to a plain single-stream download.
 */
object AudioDownloader {

    private const val CHUNKS = 4
    private const val RANGE_THRESHOLD = 1_500_000L

    private class Probe(val total: Long, val supportsRanges: Boolean)

    suspend fun download(
        url: String,
        destination: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val probe = probe(url)
        if (probe != null && probe.supportsRanges && probe.total > RANGE_THRESHOLD) {
            downloadRanged(url, destination, probe.total, onProgress)
        } else {
            downloadSingle(url, destination, onProgress)
        }
        if (!destination.exists() || destination.length() <= 0L) {
            throw IOException("downloaded file is empty")
        }
    }

    /** Asks for the first byte to learn the total size and whether ranges work. */
    private fun probe(url: String): Probe? = runCatching {
        val request = baseRequest(url)
            .header("Range", "bytes=0-0")
            .build()
        YouTubeHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val contentRange = response.header("Content-Range").orEmpty()
            val total = contentRange.substringAfter('/', "-1").toLongOrNull()
                ?: response.body?.contentLength()?.takeIf { it > 0 }
                ?: -1L
            Probe(total, response.code == 206 && total > 0)
        }
    }.getOrNull()

    private suspend fun downloadRanged(
        url: String,
        destination: File,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) = runCatching {
        coroutineScope {
            destination.delete()
            RandomAccessFile(destination, "rw").use { it.setLength(total) }
            val done = AtomicLong(0)
            val chunkSize = (total + CHUNKS - 1) / CHUNKS
            (0 until CHUNKS).map { index ->
                async(Dispatchers.IO) {
                    val start = index * chunkSize
                    val end = ((index + 1) * chunkSize - 1).coerceAtMost(total - 1)
                    if (start > end) return@async
                    fetchRange(url, destination, start, end) { read ->
                        onProgress(done.addAndGet(read), total)
                    }
                }
            }.awaitAll()
        }
    }.getOrElse {
        // Ranged download failed halfway: start over cleanly with one stream.
        runCatching { destination.delete() }
        downloadSingle(url, destination, onProgress)
    }

    private suspend fun fetchRange(
        url: String,
        destination: File,
        start: Long,
        end: Long,
        onRead: (Long) -> Unit,
    ) {
        val request = baseRequest(url)
            .header("Range", "bytes=$start-$end")
            .build()
        val call = YouTubeHttp.client.newCall(request)
        try {
            call.execute().use { response ->
                if (response.code != 206) throw IOException("HTTP ${response.code}")
                val input = response.body?.byteStream() ?: throw IOException("empty body")
                RandomAccessFile(destination, "rw").use { file ->
                    file.seek(start)
                    input.use {
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = it.read(buffer)
                            if (read <= 0) break
                            file.write(buffer, 0, read)
                            onRead(read.toLong())
                        }
                    }
                }
            }
        } finally {
            runCatching { call.cancel() }
        }
    }

    private suspend fun downloadSingle(
        url: String,
        destination: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val request = baseRequest(url).build()
        val call = YouTubeHttp.client.newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val total = response.body?.contentLength() ?: -1L
                val input = response.body?.byteStream() ?: throw IOException("empty body")
                destination.outputStream().use { output ->
                    input.use {
                        val buffer = ByteArray(128 * 1024)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = it.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
            }
        } finally {
            runCatching { call.cancel() }
        }
    }

    private fun baseRequest(url: String) = okhttp3.Request.Builder()
        .url(url)
        .header("User-Agent", YouTubeHttp.USER_AGENT)
        .header("Accept", "*/*")
        .header("Accept-Encoding", "identity")

    suspend fun bytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", YouTubeHttp.USER_AGENT)
                .build()
            YouTubeHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.bytes()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
}

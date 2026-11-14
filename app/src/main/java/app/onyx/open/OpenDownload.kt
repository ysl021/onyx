package app.onyx.open

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.onyx.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Downloads an open-licence track into the folder the user picked.
 *
 * Remote bytes land in the app cache first: only after the whole file arrived
 * and its signature matched a real audio container do we create the visible
 * document. That way a failed download never leaves a half-file in the music
 * folder, and a renamed `.exe` cannot sneak in — the extension comes from the
 * bytes, not from the URL.
 */
object OpenDownload {

    private const val MAX_BYTES = 300L * 1024 * 1024

    data class Progress(val received: Long, val total: Long)

    suspend fun download(
        context: Context,
        item: OpenResult,
        folderUri: String,
        onProgress: (Progress) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        require(item.fileUrl.startsWith("https://", ignoreCase = true)) { "Только https" }
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            ?: error("Папка недоступна")
        require(tree.exists() && tree.canWrite()) { "Нет доступа на запись в папку" }

        val temp = File.createTempFile("onyx-open-", ".tmp", context.cacheDir)
        val head = ByteArray(16)
        var headLen = 0
        try {
            var received = 0L
            var total = 0L
            val request = Request.Builder()
                .url(item.fileUrl)
                .header("User-Agent", Http.UA)
                .header("Accept", "audio/*,*/*;q=0.8")
                .apply { if (item.pageUrl.startsWith("https://")) header("Referer", item.pageUrl) }
                .build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Пустой ответ")
                total = body.contentLength()
                if (total > MAX_BYTES) error("Файл больше 300 МБ")
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            if (headLen < head.size) {
                                val copy = minOf(read, head.size - headLen)
                                System.arraycopy(buffer, 0, head, headLen, copy)
                                headLen += copy
                            }
                            received += read
                            if (received > MAX_BYTES) error("Файл больше 300 МБ")
                            output.write(buffer, 0, read)
                            onProgress(Progress(received, total))
                        }
                    }
                }
            }

            val ext = sniff(head, headLen) ?: error("Это не аудиофайл")
            val base = sanitize(
                if (item.artist.isBlank()) item.title else "${item.artist} - ${item.title}",
            ).ifBlank { "track" }
            val name = uniqueName(tree, base, ext)
            val treeUri = Uri.parse(folderUri)
            val parentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            val docUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                mimeFor(ext),
                "$name.$ext",
            ) ?: error("Не удалось создать файл")

            context.contentResolver.openOutputStream(docUri)?.use { output ->
                temp.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
            } ?: error("Не удалось записать файл")

            "$name.$ext"
        } finally {
            temp.delete()
        }
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(110)

    private fun uniqueName(tree: DocumentFile, base: String, ext: String): String {
        val existing = tree.listFiles()
            .mapNotNull { it.name?.lowercase() }
            .toHashSet()
        var candidate = base
        var index = 2
        while ("$candidate.$ext".lowercase() in existing) {
            candidate = "$base ($index)"
            index++
        }
        return candidate
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "webm" -> "audio/webm"
        else -> "audio/*"
    }

    /** Returns the real container from the first bytes, never from the URL. */
    private fun sniff(bytes: ByteArray, length: Int): String? {
        if (length < 4) return null
        fun ascii(from: Int, to: Int) = String(bytes, from, to - from, Charsets.ISO_8859_1)
        return when {
            ascii(0, 3) == "ID3" -> "mp3"
            bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            ascii(0, 4) == "fLaC" -> "flac"
            ascii(0, 4) == "OggS" -> "ogg"
            length >= 12 && ascii(0, 4) == "RIFF" && ascii(8, 12) == "WAVE" -> "wav"
            length >= 8 && ascii(4, 8) == "ftyp" -> "m4a"
            bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
                bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte() -> "webm"
            else -> null
        }
    }
}

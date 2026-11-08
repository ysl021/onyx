package app.onyx.music

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.util.logging.Level

/**
 * Writes title/artist/album and cover art back into the audio file itself.
 *
 * SAF does not expose a [File], so the original is copied to a temporary file,
 * edited and verified there, and only then streamed back over the original URI.
 * If any step fails the source file is left untouched.
 */
object TagWriter {

    data class Result(val ok: Boolean, val message: String? = null)

    private val supported = setOf(
        "mp3", "flac", "m4a", "mp4", "ogg", "oga", "wav", "aiff", "aif",
    )

    suspend fun write(
        context: Context,
        uri: Uri,
        displayName: String,
        title: String,
        artist: String,
        album: String,
        artUri: Uri?,
    ): Result = withContext(Dispatchers.IO) {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext !in supported) {
            return@withContext Result(false, "unsupported")
        }
        val temp = try {
            File.createTempFile("onyx_edit_", ".$ext", context.cacheDir)
        } catch (t: Throwable) {
            return@withContext Result(false, t.message)
        }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext Result(false, "open")

            runCatching {
                org.jaudiotagger.audio.AudioFile.logger.level = Level.OFF
                TagOptionSingleton.getInstance().setAndroid(true)
            }

            val audio = AudioFileIO.read(temp)
            val tag = audio.tagOrCreateAndSetDefault
            if (title.isNotBlank()) tag.setField(FieldKey.TITLE, title)
            tag.setField(FieldKey.ARTIST, artist)
            if (album.isNotBlank()) tag.setField(FieldKey.ALBUM, album)

            if (artUri != null) {
                val artFile = File.createTempFile("onyx_art_", ".jpg", context.cacheDir)
                try {
                    context.contentResolver.openInputStream(artUri)?.use { input ->
                        artFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    tag.setField(ArtworkFactory.createArtworkFromFile(artFile))
                } finally {
                    artFile.delete()
                }
            }
            audio.commit()

            val verify = AudioFileIO.read(temp).tag
            val writtenTitle = verify?.getFirst(FieldKey.TITLE)
            if (title.isNotBlank() && writtenTitle != title) {
                return@withContext Result(false, "verify")
            }
            if (temp.length() <= 0L) return@withContext Result(false, "empty")

            // From here on the original file is truncated by "wt", so the write-back
            // must never be interrupted by coroutine cancellation (e.g. user leaving
            // the screen) — otherwise the file would be left damaged.
            withContext(NonCancellable) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    temp.inputStream().use { input -> input.copyTo(output) }
                } ?: return@withContext Result(false, "write")

                Result(true)
            }
        } catch (t: Throwable) {
            Result(false, t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { temp.delete() }
        }
    }
}

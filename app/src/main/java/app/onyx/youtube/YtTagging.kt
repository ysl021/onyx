package app.onyx.youtube

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.util.logging.Level

/** Writes title/artist/album and the video thumbnail into a freshly cut file. */
object YtTagging {

    fun write(
        file: File,
        title: String,
        artist: String,
        album: String,
        coverJpeg: ByteArray?,
        cacheDir: File,
    ): Boolean = runCatching {
        AudioFile.logger.level = Level.OFF
        TagOptionSingleton.getInstance().setAndroid(true)

        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        tag.setField(FieldKey.TITLE, title)
        tag.setField(FieldKey.ARTIST, artist)
        tag.setField(FieldKey.ALBUM, album)

        if (coverJpeg != null && coverJpeg.isNotEmpty()) {
            // Build the artwork from raw bytes: the file-based factory relies on
            // ImageIO, which Android does not ship. A failure here must not lose
            // the text tags, so it is isolated from the commit.
            runCatching {
                val artwork = ArtworkFactory.getNew()
                artwork.binaryData = coverJpeg
                artwork.mimeType = "image/jpeg"
                artwork.description = "Cover"
                tag.setField(artwork)
            }
        }
        audio.commit()
        true
    }.getOrDefault(false)
}

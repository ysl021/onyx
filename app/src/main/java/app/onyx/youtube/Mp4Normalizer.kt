package app.onyx.youtube

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * MediaMuxer writes the `mdat` box with a 64-bit size (box size 1 + largesize).
 * jaudiotagger then loses 8 bytes while walking the atoms and throws
 * NullBoxIdException, so titles and covers were silently never written.
 *
 * This rewrites such boxes to the standard 32-bit header and fixes the chunk
 * offset table (`stco`/`co64`) so the audio stays playable. After it runs,
 * ordinary MP4 taggers can read and edit the file.
 */
object Mp4Normalizer {

    private class Box(
        val offset: Int,
        val size: Long,
        val header: Int,
        val type: String,
    )

    fun normalize(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        val data = file.readBytes()
        val boxes = topLevel(data)
        if (boxes.isEmpty()) return false
        if (boxes.none { it.header == 16 || it.type == "moov" }) return false
        if (boxes.any { it.size > Int.MAX_VALUE }) return false

        val out = ByteArrayOutputStream(data.size)
        val removed = ArrayList<Pair<Int, Int>>()
        var changed = false
        for (box in boxes) {
            when {
                box.type == "moov" -> {
                    val rebuilt = rebuildMoov(data, box, removed)
                    out.write(rebuilt)
                    if (rebuilt.size != box.size.toInt()) changed = true
                }
                box.header == 16 -> {
                    val newSize = box.size - 8
                    out.write(intBytes(newSize.toInt()))
                    out.write(box.type.toByteArray(Charsets.ISO_8859_1))
                    out.write(data, box.offset + 16, (box.size - 16).toInt())
                    removed += box.offset to 8
                    changed = true
                }
                else -> out.write(data, box.offset, box.size.toInt())
            }
        }
        if (!changed) return false
        val rebuilt = out.toByteArray()
        adjustOffsets(rebuilt, removed)
        file.writeBytes(rebuilt)
        return true
    }

    /**
     * Copies a `moov` box without its `udta` child. MediaMuxer (Samsung's
     * writer in particular) puts QuickTime-only boxes there that jaudiotagger
     * cannot walk; the audio itself does not need them.
     */
    private fun rebuildMoov(
        data: ByteArray,
        moov: Box,
        removed: MutableList<Pair<Int, Int>>,
    ): ByteArray {
        val start = moov.offset + moov.header
        val end = moov.offset + moov.size.toInt()
        val children = ByteArrayOutputStream((moov.size - moov.header).toInt())
        var pos = start
        while (pos + 8 <= end) {
            val size32 = readInt(data, pos).toLong() and 0xFFFFFFFFL
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            var size = size32
            if (size32 == 1L) {
                if (pos + 16 > end) break
                size = readLong(data, pos + 8)
                header = 16
            } else if (size32 == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header || pos + size > end) break
            if (type == "udta") {
                removed += pos to size.toInt()
            } else if (header == 16) {
                children.write(intBytes((size - 8).toInt()))
                children.write(type.toByteArray(Charsets.ISO_8859_1))
                children.write(data, pos + 16, (size - 16).toInt())
                removed += pos to 8
            } else {
                children.write(data, pos, size.toInt())
            }
            pos += size.toInt()
        }
        val body = children.toByteArray()
        val out = ByteArrayOutputStream(body.size + 8)
        out.write(intBytes(body.size + 8))
        out.write("moov".toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return out.toByteArray()
    }

    private fun topLevel(data: ByteArray): List<Box> {
        val result = ArrayList<Box>()
        var pos = 0
        while (pos + 8 <= data.size) {
            val size32 = readInt(data, pos).toLong() and 0xFFFFFFFFL
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            var size = size32
            if (size32 == 1L) {
                if (pos + 16 > data.size) break
                size = readLong(data, pos + 8)
                header = 16
            } else if (size32 == 0L) {
                size = (data.size - pos).toLong()
            }
            if (size < header || pos + size > data.size) break
            result += Box(pos, size, header, type)
            pos += size.toInt()
        }
        return result
    }

    private fun adjustOffsets(data: ByteArray, removed: List<Pair<Int, Int>>) {
        fun shift(oldOffset: Long): Long {
            var value = oldOffset
            for ((at, bytes) in removed) {
                if (oldOffset > at) value -= bytes
            }
            return value
        }

        fun walk(start: Int, end: Int) {
            var pos = start
            while (pos + 8 <= end) {
                val size32 = readInt(data, pos).toLong() and 0xFFFFFFFFL
                val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
                var header = 8
                var size = size32
                if (size32 == 1L) {
                    if (pos + 16 > end) return
                    size = readLong(data, pos + 8)
                    header = 16
                } else if (size32 == 0L) {
                    size = (end - pos).toLong()
                }
                if (size < header || pos + size > end) return

                if (type == "stco") {
                    val count = readInt(data, pos + header + 4)
                    var entry = pos + header + 8
                    repeat(count.coerceAtLeast(0)) {
                        if (entry + 4 > end) return
                        val old = readInt(data, entry).toLong() and 0xFFFFFFFFL
                        writeInt(data, entry, shift(old).toInt())
                        entry += 4
                    }
                } else if (type == "co64") {
                    val count = readInt(data, pos + header + 4)
                    var entry = pos + header + 8
                    repeat(count.coerceAtLeast(0)) {
                        if (entry + 8 > end) return
                        val old = readLong(data, entry)
                        writeLong(data, entry, shift(old))
                        entry += 8
                    }
                } else if (type == "moov" || type == "trak" || type == "mdia" ||
                    type == "minf" || type == "stbl"
                ) {
                    walk(pos + header, pos + size.toInt())
                }
                pos += size.toInt()
            }
        }
        walk(0, data.size)
    }

    private fun readInt(data: ByteArray, pos: Int): Int =
        ((data[pos].toInt() and 0xFF) shl 24) or
            ((data[pos + 1].toInt() and 0xFF) shl 16) or
            ((data[pos + 2].toInt() and 0xFF) shl 8) or
            (data[pos + 3].toInt() and 0xFF)

    private fun readLong(data: ByteArray, pos: Int): Long =
        (readInt(data, pos).toLong() shl 32) or (readInt(data, pos + 4).toLong() and 0xFFFFFFFFL)

    private fun writeInt(data: ByteArray, pos: Int, value: Int) {
        data[pos] = (value ushr 24).toByte()
        data[pos + 1] = (value ushr 16).toByte()
        data[pos + 2] = (value ushr 8).toByte()
        data[pos + 3] = value.toByte()
    }

    private fun writeLong(data: ByteArray, pos: Int, value: Long) {
        writeInt(data, pos, (value ushr 32).toInt())
        writeInt(data, pos + 4, value.toInt())
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}

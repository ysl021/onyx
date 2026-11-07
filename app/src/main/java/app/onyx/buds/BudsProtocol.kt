package app.onyx.buds

/**
 * Samsung Galaxy Buds SPP framing (reverse engineered by the GalaxyBudsClient project, GPLv3).
 * Newer Buds (Buds+/Pro/2/3) use: FD | sizeLo sizeHi | msgId | payload | crcLo crcHi | DD
 * where size = payload.size + 3 and CRC is CRC-16/CCITT (XMODEM) over msgId + payload.
 */
object BudsProtocol {
    const val SOM = 0xFD
    const val EOM = 0xDD

    const val ID_EXTENDED_STATUS = 97
    const val ID_NOISE_UPDATE = 119
    const val ID_NOISE_CONTROLS = 120
    const val ID_SET_ANC_ONE_EAR = 111
    const val ID_ANC_LEVEL = 131
    const val ID_MANAGER_INFO = 136

    const val SPP_UUID = "2e73a4ad-332d-41fc-90e2-16bef06523f2"

    private val table = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) {
            c = if ((c and 0x8000) != 0) (c shl 1) xor 0x1021 else c shl 1
        }
        c and 0xFFFF
    }

    fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = (table[((crc shr 8) xor (b.toInt() and 0xFF)) and 0xFF] xor (crc shl 8)) and 0xFFFF
        }
        return crc and 0xFFFF
    }

    fun encode(id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val size = payload.size + 3
        val out = ByteArray(size + 4)
        out[0] = SOM.toByte()
        out[1] = (size and 0xFF).toByte()
        out[2] = ((size shr 8) and 0x03).toByte()
        out[3] = id.toByte()
        payload.copyInto(out, 4)
        val crc = crc16(byteArrayOf(id.toByte()) + payload)
        out[size + 1] = (crc and 0xFF).toByte()
        out[size + 2] = ((crc shr 8) and 0xFF).toByte()
        out[size + 3] = EOM.toByte()
        return out
    }

    data class Frame(val id: Int, val payload: ByteArray)

    /** Parses as many complete frames as possible. Returns frames + number of consumed bytes. */
    fun decode(data: ByteArray): Pair<List<Frame>, Int> {
        val frames = ArrayList<Frame>()
        var pos = 0
        var lastConsumed = 0
        while (pos < data.size) {
            if ((data[pos].toInt() and 0xFF) != SOM) {
                pos++
                continue
            }
            if (data.size - pos < 4) break
            val rawSize = (data[pos + 1].toInt() and 0xFF) or
                ((data[pos + 2].toInt() and 0x03) shl 8)
            if (rawSize < 3) {
                pos++
                continue
            }
            val total = rawSize + 4
            if (data.size - pos < total) break
            val eom = data[pos + total - 1].toInt() and 0xFF
            val id = data[pos + 3].toInt() and 0xFF
            val payloadLen = rawSize - 3
            val payload = data.copyOfRange(pos + 4, pos + 4 + payloadLen)
            val crcLo = data[pos + rawSize + 1].toInt() and 0xFF
            val crcHi = data[pos + rawSize + 2].toInt() and 0xFF
            if (eom != EOM) {
                pos++
                continue
            }
            val expected = crc16(byteArrayOf(id.toByte()) + payload)
            val actual = crcLo or (crcHi shl 8)
            if (expected == actual) {
                frames.add(Frame(id, payload))
                pos += total
                lastConsumed = pos
            } else {
                pos++
            }
        }
        // keep incomplete trailing data
        if (pos < data.size) {
            val remainingStart = findNextSom(data, pos)
            return frames to (remainingStart ?: data.size)
        }
        return frames to lastConsumed
    }

    private fun findNextSom(data: ByteArray, from: Int): Int? {
        for (i in from until data.size) {
            if ((data[i].toInt() and 0xFF) == SOM) return i
        }
        return null
    }
}

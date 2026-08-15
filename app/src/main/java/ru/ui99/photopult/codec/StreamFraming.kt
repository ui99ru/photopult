package ru.ui99.photopult.codec

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Framing for the H.264 preview carried over the Nearby STREAM payload (a plain byte pipe).
 *
 * Each encoded access unit is length-prefixed so the receiver can find frame boundaries and drop
 * stale frames down to the next keyframe when it falls behind. Header (big-endian):
 *
 * ```
 * [4] payload length (bytes that follow the header)
 * [8] presentation timestamp, microseconds
 * [1] flags: bit0 = keyframe, bit1 = codec config (SPS/PPS)
 * [N] H.264 payload (Annex B)
 * ```
 */
object StreamFraming {

    const val HEADER_SIZE = 13
    const val FLAG_KEYFRAME = 0x01
    const val FLAG_CONFIG = 0x02

    data class Frame(
        val ptsUs: Long,
        val keyframe: Boolean,
        val config: Boolean,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return ptsUs == other.ptsUs && keyframe == other.keyframe &&
                config == other.config && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = ptsUs.hashCode()
            result = 31 * result + keyframe.hashCode()
            result = 31 * result + config.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    fun writeFrame(
        out: OutputStream,
        data: ByteArray,
        offset: Int,
        size: Int,
        ptsUs: Long,
        keyframe: Boolean,
        config: Boolean,
    ) {
        val header = ByteArray(HEADER_SIZE)
        writeInt(header, 0, size)
        writeLong(header, 4, ptsUs)
        header[12] = ((if (keyframe) FLAG_KEYFRAME else 0) or (if (config) FLAG_CONFIG else 0)).toByte()
        out.write(header)
        out.write(data, offset, size)
    }

    /** Reads one frame, blocking until it's complete. Returns null at end of stream. */
    fun readFrame(input: InputStream): Frame? {
        val header = readFully(input, HEADER_SIZE) ?: return null
        val size = readInt(header, 0)
        val ptsUs = readLong(header, 4)
        val flags = header[12].toInt()
        require(size in 0..MAX_FRAME_BYTES) { "implausible frame size $size" }
        val payload = readFully(input, size) ?: throw EOFException("truncated frame payload")
        return Frame(
            ptsUs = ptsUs,
            keyframe = flags and FLAG_KEYFRAME != 0,
            config = flags and FLAG_CONFIG != 0,
            data = payload,
        )
    }

    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) return if (read == 0) null else throw EOFException("stream ended mid-frame")
            read += r
        }
        return buf
    }

    private fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun writeLong(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (56 - i * 8)).toByte()
    }

    private fun readLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}

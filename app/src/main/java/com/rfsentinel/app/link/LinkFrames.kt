package com.rfsentinel.app.link

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Messages over a Bluetooth GATT link: the link carries a byte stream in pieces of up to MTU - 3 bytes,
 * so every message goes as a 4-byte length and then its bytes, and the other side puts them back together.
 */
object LinkFrames {
    /** Far above any real batch: a bigger length means a broken stream. */
    const val MAX_FRAME = 512 * 1024

    fun frame(payload: ByteArray): ByteArray = ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()

    /** [frame] cut into pieces for writes / notifications of up to [size] bytes. */
    fun chunks(frame: ByteArray, size: Int): List<ByteArray> {
        val n = size.coerceAtLeast(20)
        return (frame.indices step n).map { frame.copyOfRange(it, minOf(it + n, frame.size)) }
    }

    /** Puts pieces back into whole messages. */
    class Reassembler {
        private val buf = ByteArrayOutputStream()

        /** The messages completed by [chunk]; throws on a stream that can't be valid (the link is then closed). */
        fun feed(chunk: ByteArray): List<ByteArray> {
            buf.write(chunk)
            val out = mutableListOf<ByteArray>()
            var data = buf.toByteArray()
            var pos = 0
            while (data.size - pos >= 4) {
                val len = ByteBuffer.wrap(data, pos, 4).int
                require(len in 1..MAX_FRAME) { "bad frame length $len" }
                if (data.size - pos - 4 < len) break
                out += data.copyOfRange(pos + 4, pos + 4 + len)
                pos += 4 + len
            }
            if (pos > 0) {
                data = data.copyOfRange(pos, data.size)
                buf.reset(); buf.write(data)
            }
            return out
        }
    }
}

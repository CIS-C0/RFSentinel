package com.rfsentinel.app.esp

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8ChunksTest {

    @Test
    fun aCharacterSplitAcrossReadsStaysWhole() {
        val bytes = "Café 📡\n".toByteArray(Charsets.UTF_8)
        // Every split point, including inside the 2-byte é and the 4-byte emoji.
        for (cut in 0..bytes.size) {
            val t = Utf8Chunks()
            val a = bytes.copyOfRange(0, cut); val b = bytes.copyOfRange(cut, bytes.size)
            assertEquals("cut at $cut", "Café 📡\n", t.decode(a, a.size) + t.decode(b, b.size))
        }
    }

    @Test
    fun plainTextPassesStraightThrough() {
        val t = Utf8Chunks()
        val buf = "MAC: AA:BB\nrest".toByteArray() + ByteArray(10)
        assertEquals("MAC: AA:BB\nrest", t.decode(buf, 15))
    }
}

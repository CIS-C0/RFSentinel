package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patches, radio events and data grants on the control channel; link control on a voice channel. */
class P25SdrTrunkTest {

    private fun tsbk(opcode: Int, args: Long, mfid: Int = 0) =
        P25Frame.Tsbk(ByteArray(10) { i -> when (i) { 0 -> (0x80 or opcode).toByte(); 1 -> mfid.toByte(); else -> (args ushr (8 * (9 - i))).toByte() } })

    @Test
    fun motorolaPatches() {
        // Supergroup 5000 carrying 100 and 200; the third slot repeats the supergroup (unused).
        assertEquals(P25Tsbk.Patch(5000, listOf(100, 200), true), P25Tsbk.parse(tsbk(0x00, 0x1388_0064_00C8_1388L, 0x90)))
        assertEquals(P25Tsbk.Patch(5000, listOf(100), false), P25Tsbk.parse(tsbk(0x01, 0x1388_0064_0000_0000L, 0x90)))
        // A patched call granted: emergency, channel 0x1005, supergroup 5000, radio 0x0A0B0C.
        assertEquals(P25Tsbk.Grant(0x1005, 5000, null, 0x0A0B0C, emergency = true, encrypted = false),
            P25Tsbk.parse(tsbk(0x02, (0x80L shl 56) or 0x1005_1388_0A0B0CL, 0x90)))
        assertEquals(P25Tsbk.GrantUpdate(listOf(0x1005 to 5000)), P25Tsbk.parse(tsbk(0x03, 0x1005_1388_0000_0000L, 0x90)))
        assertNull(P25Tsbk.parse(tsbk(0x00, 0L, 0x90))) // no supergroup
    }

    @Test
    fun radioEventsAndDataGrants() {
        assertEquals(P25Tsbk.RadioEvent("status update", 0x0A0B0C, 0x000102, 3), P25Tsbk.parse(tsbk(0x18, 0x0003_000102_0A0B0CL)))
        assertEquals(P25Tsbk.RadioEvent("message", 0x0A0B0C, 0x000102, 7), P25Tsbk.parse(tsbk(0x1C, 0x0007_000102_0A0B0CL)))
        assertEquals(P25Tsbk.RadioEvent("radio unit monitor", 0x000102, 0x0A0B0C), P25Tsbk.parse(tsbk(0x1D, 0x0000_000102_0A0B0CL)))
        assertEquals(P25Tsbk.RadioEvent("call alert", 0x0A0B0C, 0x000102), P25Tsbk.parse(tsbk(0x1F, 0x0000_000102_0A0B0CL)))
        assertEquals(P25Tsbk.RadioEvent("private call request", 0x0A0B0C, 0x000102), P25Tsbk.parse(tsbk(0x05, 0x0000_000102_0A0B0CL)))
        assertEquals(P25Tsbk.DataGrant(0x1005, 0x1006, 0x000102), P25Tsbk.parse(tsbk(0x14, 0x00_1005_1006_000102L)))
    }

    @Test
    fun logKeepsPatchesAndEvents() {
        val log = P25Log()
        log.add(P25Tsbk.Affiliation(0x000102, 300), 0)
        log.add(P25Tsbk.Patch(5000, listOf(100, 200), true), 0)
        log.add(P25Tsbk.Grant(0x1005, 5000, null, 0x0A0B0C, false, false), 1)
        assertEquals(listOf(100, 200), log.members(5000))
        assertEquals(listOf(100, 200), log.rows().first { it.tg == 5000 }.patch)
        log.add(P25Tsbk.Patch(5000, listOf(100), false), 2)
        assertEquals(listOf(200), log.members(5000))
        log.add(P25Tsbk.Patch(5000, emptyList(), false), 3)
        assertTrue(log.members(5000).isEmpty())
        // Repeated by the site: kept once; its target's talkgroup from the affiliation.
        repeat(3) { log.add(P25Tsbk.RadioEvent("call alert", 0x0A0B0C, 0x000102), 10L + it) }
        log.add(P25Tsbk.DataGrant(0x1005, 0x1006, 0x000102), 20)
        val e = log.events()
        assertEquals(listOf("data channel", "call alert"), e.map { it.kind })
        assertEquals(300, e[1].tg)
        assertEquals(0x0A0B0C, e[1].source)
        log.clear(30)
        assertTrue(log.events().isEmpty())
    }

    @Test
    fun linkControlNamesTalkerAndOtherCalls() {
        val other = ArrayList<Pair<Int, Int>>()
        val f = P25Follow(0.0, onOtherCall = { ch, tg -> other += ch to tg }) {}
        val user = intArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0x2C, 0x0A, 0x0B, 0x0C) // talkgroup 300, radio 0x0A0B0C
        f.linkControl(user); assertNull(f.talker) // once: not trusted yet
        f.linkControl(user); assertEquals(0x0A0B0C, f.talker)
        // Update: talkgroup 5000 on 0x1005, slot B unused.
        val update = intArrayOf(0x42, 0x10, 0x05, 0x13, 0x88, 0x00, 0x00, 0x00, 0x00)
        f.linkControl(update); assertTrue(other.isEmpty())
        f.linkControl(update); assertEquals(listOf(0x1005 to 5000), other)
        f.linkControl(intArrayOf(0xC2, 0x10, 0x05, 0x13, 0x88, 0, 0, 0, 0)) // protected: skipped
        f.linkControl(intArrayOf(0x02, 0x90, 0x05, 0x13, 0x88, 0, 0, 0, 0)) // a vendor's (Motorola) opcode 2: not an update
        assertEquals(1, other.size)
    }

    @Test
    fun ldu1LinkControlWords() {
        val lc = intArrayOf(0x02, 0x10, 0x05, 0x13, 0x88, 0x20, 0x01, 0x00, 0xC8)
        val words = IntArray(12) { w -> var v = 0; for (b in 0 until 6) { val bit = w * 6 + b; v = v shl 1 or (lc[bit / 8] shr (7 - bit % 8) and 1) }; v }
        val d = IntArray(900)
        val at = intArrayOf(176, 268, 360)
        for (i in 0 until 12) {
            val code = words[i] shl 4 or P25Voice.hexParity(words[i])
            for (k in 0 until 5) d[at[i / 4] + 5 * (i % 4) + k] = code shr (8 - 2 * k) and 3
        }
        d[at[1] + 2] = d[at[1] + 2] xor 1 // one bit wrong: corrected
        assertEquals(lc.toList(), P25Voice.lc(d)!!.toList())
    }
}

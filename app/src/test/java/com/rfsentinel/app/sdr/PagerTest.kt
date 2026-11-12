package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class PagerTest {

    private companion object {
        const val RATE = P25Demod.SAMPLE_RATE
        const val OFFSET = 250_000L
        /** The receiver's crystal: the carrier is this far from where the hunt thinks it is. */
        const val CARRIER_ERROR_HZ = 700.0
    }

    /** A POCSAG or FLEX transmitter as a list of (frequency deviation, symbol rate) runs. */
    private class Fsk {
        val hz = ArrayList<Double>()
        val perSymbol = ArrayList<Double>()
        fun add(deviation: Double, baud: Int) { hz += deviation; perSymbol += RATE.toDouble() / baud }
        val samples get() = perSymbol.sum().toInt()

        /** The signal as the dongle delivers it at [carrierHz] from the tuned centre, in noise. */
        fun iq(carrierHz: Double, amp: Double = 60.0, noise: Double = 3.0): ByteArray {
            val out = ByteArray(2 * samples)
            val rnd = java.util.Random(7)
            var phase = 0.0; var k = 0; var edge = 0.0; var t = 0
            for (s in hz.indices) {
                edge += perSymbol[s]
                val step = 2 * PI * (carrierHz + hz[s]) / RATE
                while (t < edge && k < out.size) {
                    phase += step; t++
                    out[k++] = (127.5 + amp * cos(phase) + rnd.nextGaussian() * noise).roundToInt().coerceIn(0, 255).toByte()
                    out[k++] = (127.5 + amp * sin(phase) + rnd.nextGaussian() * noise).roundToInt().coerceIn(0, 255).toByte()
                }
            }
            return out
        }
    }

    private fun decode(iq: ByteArray): Pair<List<PagerLog.Page>, Int> {
        val pages = ArrayList<PagerLog.Page>()
        val d = PagerDemod(OFFSET) { pages += it }
        val chunk = 16_384
        var at = 0
        while (at < iq.size) {
            val n = minOf(chunk, iq.size - at)
            d.feed(iq.copyOfRange(at, at + n), n / 2)
            at += n
        }
        return pages to d.frames
    }

    // POCSAG, as the standard lays it out.

    private fun pocsagWord(data21: Int): Int {
        val w = data21 shl 11
        val c = w or (Bch3121.syndrome(w) shl 1)
        return c or (Integer.bitCount(c) and 1)
    }

    private fun address(capcode: Int, function: Int) = pocsagWord(((capcode ushr 3) shl 2) or function)
    private fun message(bits20: Int) = pocsagWord((1 shl 20) or bits20)

    /** Message words from a bit list, padded out with [pad]. */
    private fun messageWords(bits: List<Int>, pad: Int = 0) = bits.chunked(20).map { c ->
        message((c + List(20 - c.size) { pad }).fold(0) { a, b -> a shl 1 or b })
    }

    private fun alphaBits(text: String) = (text + "\u0004").flatMap { ch -> List(7) { (ch.code ushr it) and 1 } }

    private fun numericBits(text: String) = text.flatMap { ch ->
        val v = "0123456789*U -)(".indexOf(ch)
        List(4) { (v ushr it) and 1 }
    }.let { b -> b + List((20 - b.size % 20) % 20) { i -> listOf(0, 0, 1, 1)[i % 4] } } // padded with spaces

    /** Preamble and sync, then [pages] (capcode, its words) each in its frame, in as many batches as they need. */
    private fun pocsag(pages: List<Pair<Int, List<Int>>>): List<Int> {
        val words = ArrayList<Int>()
        for ((capcode, msg) in pages) {
            while (words.size % 16 / 2 != capcode and 7 ) words += Pocsag.IDLE
            words += msg
            words += Pocsag.IDLE
        }
        while (words.size % 16 != 0) words += Pocsag.IDLE
        repeat(16) { words += Pocsag.IDLE }
        val out = ArrayList<Int>()
        words.forEachIndexed { i, w -> if (i % 16 == 0) out += Pocsag.SYNC; out += w }
        return out
    }

    private fun pocsagSignal(baud: Int, words: List<Int>, inverted: Boolean = false) = Fsk().apply {
        val high = if (inverted) -4500.0 else 4500.0
        repeat(576) { add(if (it % 2 == 0) high else -high, baud) }
        for (w in words) for (b in 31 downTo 0) add(if ((w ushr b) and 1 == 1) high else -high, baud)
        repeat(40) { add(0.0, baud) }
    }

    @Test fun `the standard's sync and idle words are clean codewords`() {
        assertEquals(0, Bch3121.syndrome(Pocsag.SYNC))
        assertEquals(0, Bch3121.syndrome(Pocsag.IDLE))
        assertEquals(Pocsag.IDLE, Bch3121.correct(Pocsag.IDLE))
    }

    @Test fun `up to two bit errors in the codeword are fixed and three are not passed off as good`() {
        val word = message(0x5A5A5)
        assertEquals(word, Bch3121.correct(word xor 1))
        for (i in 1..31) {
            assertEquals(word, Bch3121.correct(word xor (1 shl i)))
            for (j in i + 1..31) assertEquals("$i $j", word, Bch3121.correct(word xor (1 shl i) xor (1 shl j)))
        }
        // Odd error counts leave the parity wrong: never a word with a different message.
        for (i in 1..29) assertNull(Bch3121.correct(word xor (7 shl i)))
    }

    @Test fun `alpha and numeric pages at all three speeds, either polarity`() {
        val text = "Test message 42"
        for (baud in listOf(512, 1200, 2400)) for (inverted in listOf(false, true)) {
            val words = pocsag(listOf(
                1234567 to (listOf(address(1234567, 3)) + messageWords(alphaBits(text))),
                2000002 to (listOf(address(2000002, 0)) + messageWords(numericBits("555 1234"))),
                300004 to listOf(address(300004, 1))
            ))
            val (pages, frames) = decode(pocsagSignal(baud, words, inverted).iq(OFFSET + CARRIER_ERROR_HZ))
            assertEquals("$baud $inverted", listOf(
                PagerLog.Page("POCSAG $baud", 1234567, PagerLog.Kind.ALPHA, text),
                PagerLog.Page("POCSAG $baud", 2000002, PagerLog.Kind.NUMERIC, "555 1234"),
                PagerLog.Page("POCSAG $baud", 300004, PagerLog.Kind.TONE, "")
            ), pages)
            assertTrue(frames > 0)
        }
    }

    @Test fun `bit errors in the words are corrected and a word past fixing cuts the page off`() {
        val words = pocsag(listOf(1234560 to (listOf(address(1234560, 3)) + messageWords(alphaBits("Hello there, a longer page")))))
        val at = words.indexOfFirst { it == address(1234560, 3) }
        val hit = words.toMutableList()
        hit[at] = hit[at] xor 0x00100400
        hit[at + 1] = hit[at + 1] xor 0x40000002
        assertEquals("Hello there, a longer page", decode(pocsagSignal(1200, hit).iq(OFFSET.toDouble())).first.single().text)
        hit[at + 3] = hit[at + 3] xor 0x00015000
        val page = decode(pocsagSignal(1200, hit).iq(OFFSET.toDouble())).first.single()
        assertTrue(page.partial)
        assertTrue(page.text, page.text.startsWith("Hello"))
    }

    @Test fun `alpha sent on function 0 is still read as text`() {
        val (kind, text) = Pocsag.decode(0, messageWords(alphaBits("Call the office now")).map { (it ushr 11) and 0xFFFFF })
        assertEquals(PagerLog.Kind.ALPHA, kind)
        assertEquals("Call the office now", text)
    }

    @Test fun `noise makes no pages`() {
        val rnd = java.util.Random(3)
        val noise = ByteArray(2 * RATE * 8) { (127.5 + rnd.nextGaussian() * 20).roundToInt().coerceIn(0, 255).toByte() }
        val (pages, frames) = decode(noise)
        assertTrue(pages.toString(), pages.isEmpty())
        assertEquals(0, frames)
    }

    // FLEX, as demod_flex.c reads it.

    private fun flexWord(data21: Int) = Integer.reverse(pocsagWord(Integer.reverse(data21) ushr 11))

    private fun flexFiw(cycle: Int, frame: Int): Int {
        val rest = (cycle shl 4) or (frame shl 8)
        val sum = (rest ushr 4 and 0xF) + (rest ushr 8 and 0xF) + (rest ushr 12 and 0xF) + (rest ushr 16 and 0xF)
        return flexWord(rest or ((0xF - sum) and 0xF))
    }

    private fun flexAlpha(text: String): List<Int> {
        val codes = listOf(0x41) + text.map { it.code } // a signature character first
        val header = 3 shl 11                             // a whole page
        return listOf(header) + codes.chunked(3).map { c -> (c + List(3 - c.size) { 3 }).let { it[0] or (it[1] shl 7) or (it[2] shl 14) } }
    }

    /** Digits after two header bits; fill to the end of the word. */
    private fun flexNumeric(digits: String): List<Int> {
        val nibbles = digits.map { "0123456789 U -][".indexOf(it) }
        val bits = listOf(0, 0) + nibbles.flatMap { n -> List(4) { (n ushr it) and 1 } }
        val filled = bits + generateSequence(0) { (it + 1) % 4 }.map { (0xC ushr it) and 1 }.take((21 - bits.size % 21) % 21).toList()
        return filled.chunked(21).map { w -> w.foldIndexed(0) { i, a, b -> a or (b shl i) } }
    }

    /** Unused words: all-same fill would leave the signal with no zero crossings for 100 symbols, and the clock gives up. */
    private fun flexFill(i: Int) = if (i % 2 == 0) 0x1FFFFF else 0

    /** A phase: BIW, addresses, vectors, messages. Each page: capcode, type, words. */
    private fun flexPhase(pages: List<Triple<Long, Int, List<Int>>>): IntArray {
        val data = IntArray(88, ::flexFill)
        val vectors = 1 + pages.size
        data[0] = (vectors shl 10) // addresses at 1
        var at = vectors + pages.size
        pages.forEachIndexed { i, (capcode, type, words) ->
            data[1 + i] = (capcode + 0x8000).toInt()
            val len = if (type == 3) words.size - 1 else words.size
            data[vectors + i] = (type shl 4) or (at shl 7) or (len shl 14)
            words.forEach { data[at++] = it }
        }
        return IntArray(88) { flexWord(data[it]) }
    }

    /** One FLEX frame. Sync bits go out inverted relative to data bits, as the decoder reads them. */
    private fun flex(code: Int, baud: Int, levels: Int, phases: List<IntArray>, inverted: Boolean = false) = Fsk().apply {
        val sign = if (inverted) -1 else 1
        fun level(sym: Int) = sign * listOf(-4800.0, -1600.0, 1600.0, 4800.0)[sym]
        repeat(200) { add(level(if (it % 2 == 0) 0 else 3), 1600) }
        val sync = (code.toLong() shl 48) or (0xA6C6AAAAL shl 16) or (code.inv().toLong() and 0xFFFF)
        for (b in 63 downTo 0) add(level(if ((sync ushr b) and 1L == 1L) 0 else 3), 1600)
        repeat(16) { add(level(if (it % 2 == 0) 3 else 0), 1600) }
        val fiw = flexFiw(3, 42)
        for (b in 0..31) add(level(if ((fiw ushr b) and 1 == 1) 3 else 0), 1600)
        repeat(baud * 25 / 1000) { add(level(if (it % 2 == 0) 0 else 3), baud) }
        fun sym(a: Int, b: Int) = if (levels == 2) (if (a == 1) 3 else 0) else when { a == 1 && b == 1 -> 2; a == 1 -> 3; b == 1 -> 1; else -> 0 }
        fun bit(p: IntArray, c: Int) = (p[((c ushr 5) and 0xFFF8) or (c and 7)] ushr ((c ushr 3) and 31)) and 1
        for (c in 0 until 2816) {
            add(level(sym(bit(phases[0], c), bit(phases[1], c))), baud)
            if (baud == 3200) add(level(sym(bit(phases[2], c), bit(phases[3], c))), baud)
        }
        repeat(64) { add(level(if (it % 2 == 0) 0 else 3), 1600) }
    }

    @Test fun `flex frames at every speed, with alpha, numeric and tone pages`() {
        val idle = IntArray(88) { flexWord(flexFill(it)) }
        for ((code, baud, levels) in listOf(Triple(0x870C, 1600, 2), Triple(0xB068, 1600, 4), Triple(0x7B18, 3200, 2), Triple(0xDEA0, 3200, 4))) {
            val first = flexPhase(listOf(
                Triple(1234567L, 5, flexAlpha("FLEX test page")),
                Triple(1345678L, 3, flexNumeric("5551234")),
                Triple(345678L, 2, listOf(0))
            ))
            val second = if (levels == 4) flexPhase(listOf(Triple(777777L, 5, flexAlpha("Second phase")))) else idle
            val phases = listOf(first, second, idle, idle)
            for (inverted in listOf(false, true)) {
                val (pages, frames) = decode(flex(code, baud, levels, phases, inverted).iq(OFFSET + CARRIER_ERROR_HZ))
                val name = "FLEX $baud/$levels"
                assertEquals("$name $inverted", listOfNotNull(
                    PagerLog.Page(name, 1234567, PagerLog.Kind.ALPHA, "FLEX test page"),
                    PagerLog.Page(name, 1345678, PagerLog.Kind.NUMERIC, "5551234"),
                    PagerLog.Page(name, 345678, PagerLog.Kind.TONE, ""),
                    PagerLog.Page(name, 777777, PagerLog.Kind.ALPHA, "Second phase").takeIf { levels == 4 }
                ), pages)
                assertEquals(1, frames)
            }
        }
    }

    @Test fun `flex at 3200 reads the interleaved phase too`() {
        val idle = IntArray(88) { flexWord(flexFill(it)) }
        val a = flexPhase(listOf(Triple(1000L, 5, flexAlpha("Phase A"))))
        val c = flexPhase(listOf(Triple(2000L, 5, flexAlpha("Phase C"))))
        val pages = decode(flex(0x7B18, 3200, 2, listOf(a, idle, c, idle)).iq(OFFSET.toDouble())).first
        assertEquals(listOf("Phase A", "Phase C"), pages.map { it.text })
    }

    @Test fun `a garbled word drops only the page that uses it`() {
        val phase = flexPhase(listOf(Triple(1000L, 5, flexAlpha("Kept")), Triple(2000L, 5, flexAlpha("Lost"))))
        // Three bit errors: past fixing. One in an unused word, one in the second page's text.
        phase[80] = phase[80] xor (7 shl 5)
        val lost = 1 + 2 + 2 + flexAlpha("Kept").size + 1 // past the BIW, 2 addresses, 2 vectors, the first page and the second's header: its first text word
        phase[lost] = phase[lost] xor (7 shl 5)
        val idle = IntArray(88) { flexWord(flexFill(it)) }
        val pages = decode(flex(0x870C, 1600, 2, listOf(phase, idle, idle, idle)).iq(OFFSET + CARRIER_ERROR_HZ)).first
        assertEquals(listOf("Kept"), pages.map { it.text })
    }

    // The hunt.

    @Test fun `the scan finds a two-tone pager at its centre, not at one tone`() {
        val rnd = java.util.Random(3)
        val fsk = Fsk().apply { repeat(400) { add(if (rnd.nextBoolean()) 4800.0 else -4800.0, 1600) } }
        val iq = fsk.iq(300_000.0)
        val psd = RadioWatch.powerSpectrum(iq, iq.size / 2)
        val found = P25Hunt.strongest(P25Hunt.candidates(0, psd, RATE, centreHalfHz = PagerHunt.CENTRE_HALF_HZ), PagerHunt.MERGE_HZ)
        assertEquals(found.toString(), 1, found.size)
        assertTrue("${found[0].hz}", abs(found[0].hz - 300_000) < 1_500)
        // Found as sensitively as with P25's window: only the centring is wider.
        assertEquals(P25Hunt.candidates(0, psd, RATE).maxOf { it.snrDb }, found[0].snrDb, 0f)
        // P25's narrow window lands on a tone, the bug a tester's log showed (~4 kHz off, nothing decoded).
        assertTrue(P25Hunt.candidates(0, psd, RATE).none { abs(it.hz - 300_000) < 1_500 })
    }

    /** A dongle that hears [signal] on repeat at [carrierHz], plus noise. */
    private class FakeDongle(val carrierHz: Long, val signal: Fsk) : SdrDevice {
        override val label = "fake"
        override val sampleRate = RATE
        override val ctlErrors = 0
        override val lost = false
        override val tunerWriteFails = 0
        override val settleReads = 1
        @Volatile var reads = 0
        /** The next reads that fail. */
        var stall = 0
        private var tuned = 0L
        private var iq = ByteArray(0)
        private var at = 0
        private val rnd = java.util.Random(9)
        override fun open() = true
        override fun close() {}
        override fun setSampleRate(rate: Int) {}
        override fun tune(hz: Long): Boolean {
            tuned = hz
            val off = carrierHz - hz
            iq = if (abs(off) < 900_000) signal.iq(off.toDouble()) else ByteArray(0)
            at = 0
            return true
        }
        override fun setGain(tenthsDb: Int) {}
        override fun resetBuffer() {}
        override fun read(buf: ByteArray, len: Int): Int {
            reads++
            if (stall > 0) { stall--; return -1 }
            for (k in 0 until len) {
                buf[k] = if (iq.isEmpty()) (127.5 + rnd.nextGaussian() * 3).roundToInt().coerceIn(0, 255).toByte() else iq[at]
                if (iq.isNotEmpty()) at = (at + 1) % iq.size
            }
            return len
        }
    }

    @Test fun `the hunt finds the paging channel in its band and listen stops when told`() {
        val words = pocsag(listOf(1234567 to (listOf(address(1234567, 3)) + messageWords(alphaBits("Found you")))))
        val dongle = FakeDongle(929_612_500L, pocsagSignal(1200, words))
        val pages = ArrayList<PagerLog.Page>()
        val hunt = PagerHunt(dongle)
        val hz = hunt.hunt({ false }) { p, _ -> pages += p }
        assertNotNull(hz)
        assertTrue("$hz", abs(hz!! - 929_612_500L) < 3_000)
        val until = dongle.reads + 1_000
        assertEquals(false, hunt.listen(hz, { dongle.reads > until }) { p, _ -> pages += p })
        assertTrue(pages.any { it.text == "Found you" })
    }

    @Test fun `listen gives up on a stream that stays dead`() {
        val dongle = FakeDongle(929_612_500L, Fsk())
        dongle.stall = Int.MAX_VALUE
        val hunt = PagerHunt(dongle, log = {})
        assertTrue("left by itself, not by the test's stop", hunt.listen(929_612_500L, { dongle.reads > 1000 }) { _, _ -> })
    }

    @Test fun `listen reports a stall and when the samples come back`() {
        val dongle = FakeDongle(929_612_500L, Fsk())
        val stalls = ArrayList<Boolean>(); val logs = ArrayList<String>()
        val hunt = PagerHunt(dongle, onStall = { stalls += it }, log = { logs += it })
        dongle.stall = 9 // the settle read takes one, then one stall's worth
        hunt.listen(929_612_500L, { dongle.reads > 20 }) { _, _ -> }
        assertEquals(listOf(true, false), stalls)
        assertEquals(1, logs.count { !it.startsWith("Pager listen") })
    }

    @Test fun `the listen counters show syncs and a transmitter coming on`() {
        val words = pocsag(listOf(1234567 to listOf(address(1234567, 1)))).take(17)
        val iq = pocsagSignal(1200, words).iq(OFFSET + CARRIER_ERROR_HZ)
        val d = PagerDemod(OFFSET) { }
        val silence = ByteArray(768_000) { 128.toByte() } // 200 ms
        d.feed(silence, silence.size / 2)
        assertEquals(0, d.syncs)
        d.feed(iq, iq.size / 2)
        assertEquals(1, d.syncs)
        assertTrue("rose ${d.riseDb} dB", d.riseDb > 10)
    }

    @Test fun `a typed frequency logs its counters, and they name no frequency`() {
        val dongle = FakeDongle(929_612_500L, Fsk())
        val logs = ArrayList<String>()
        PagerHunt(dongle, log = { logs += it }).listen(929_612_500L, { dongle.reads > 20 }) { _, _ -> }
        val line = logs.single { it.startsWith("Pager listen") }
        assertFalse(line, "929612500" in line || "929.6" in line)
    }

    @Test fun `a lone batch counts as a frame, so the hunt stays on a channel of short pages`() {
        val words = pocsag(listOf(1234567 to listOf(address(1234567, 1)))).take(17)
        val (pages, frames) = decode(pocsagSignal(1200, words).iq(OFFSET + CARRIER_ERROR_HZ))
        assertEquals(listOf(PagerLog.Page("POCSAG 1200", 1234567, PagerLog.Kind.TONE, "")), pages)
        assertEquals(1, frames)
    }

    @Test fun `nothing on the air means the hunt gives up`() {
        val dongle = FakeDongle(100_000_000L, Fsk())
        assertNull(PagerHunt(dongle).hunt({ false }) { _, _ -> })
    }

    @Test fun `the log keeps the newest pages and clears`() {
        val log = PagerLog()
        repeat(PagerLog.MAX + 5) { log.add(PagerLog.Page("POCSAG 1200", it.toLong(), PagerLog.Kind.TONE, ""), 1L, it.toLong()) }
        assertEquals(PagerLog.MAX, log.rows().size)
        assertEquals((PagerLog.MAX + 4).toLong(), log.rows().first().page.capcode)
        log.clear()
        assertTrue(log.rows().isEmpty())
    }
}

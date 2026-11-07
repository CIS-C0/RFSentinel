package com.rfsentinel.app.sdr

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Keeps a read waiting on the dongle while the caller decodes the last one. The RTL-SDR's own buffer is small: with read,
 * decode, read, it overflows during each decode and samples go missing (a tester's log showed 85% arriving), which breaks
 * every POCSAG codeword that spans a gap. [read] returns what [SdrDevice.read] returned, in order.
 */
class ReadAhead(private val sdr: SdrDevice, size: Int, slots: Int = 8) : AutoCloseable {
    private class Chunk(val buf: ByteArray) { var n = 0 }
    private val free = ArrayBlockingQueue<Chunk>(slots)
    private val full = ArrayBlockingQueue<Chunk>(slots)
    @Volatile private var running = true
    private val thread: Thread

    init {
        repeat(slots) { free.add(Chunk(ByteArray(size))) }
        thread = Thread({
            try {
                while (running) {
                    val c = free.take()
                    c.n = sdr.read(c.buf)
                    full.put(c)
                }
            } catch (_: InterruptedException) {}
        }, "sdr-read-ahead").apply { isDaemon = true; start() }
    }

    /** The next read's bytes, copied into [into]: its byte count, or 0 when nothing came for 2 s. */
    fun read(into: ByteArray): Int {
        val c = full.poll(2, TimeUnit.SECONDS) ?: return 0
        val n = c.n
        if (n > 0) System.arraycopy(c.buf, 0, into, 0, n)
        free.put(c)
        return n
    }

    /** Waits for the read in flight (up to its USB timeout), so the caller can retune safely. */
    override fun close() {
        running = false
        thread.interrupt()
        thread.join(2_000)
    }
}

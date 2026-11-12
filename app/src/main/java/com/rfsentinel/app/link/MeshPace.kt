package com.rfsentinel.app.link

/**
 * Sentinel Link › Auto: the LoRa send interval, as fast as the channel allows. Devices waiting: one packet every
 * [FASTEST_MS]; nothing waiting: [IDLE_MS]. It slows down as the channel gets busy (Meshtastic itself holds back
 * sends above about 25 % channel use) or as the node's own air time nears the 10 % duty cycle some regions set,
 * and when the node's send queue is nearly full. Before the node reports its telemetry: [UNKNOWN_MS].
 */
object MeshPace {
    const val FASTEST_MS = 15_000L
    const val UNKNOWN_MS = 30_000L
    const val IDLE_MS = 60_000L
    const val SLOWEST_MS = 300_000L

    /** [channelUtil], [airUtilTx]: the node's own report, in %; null when unknown. [queueFree]: -1 when unknown. */
    fun intervalMs(channelUtil: Float?, airUtilTx: Float?, queueFree: Int, waiting: Boolean): Long {
        if (!waiting) return IDLE_MS
        val base = if (channelUtil == null && airUtilTx == null) UNKNOWN_MS else FASTEST_MS
        val busy = maxOf(1f, (channelUtil ?: 0f) / 12.5f, (airUtilTx ?: 0f) / 4f)
        val queue = if (queueFree in 0..2) 2 else 1
        return (base * busy * queue).toLong().coerceIn(FASTEST_MS, SLOWEST_MS)
    }
}

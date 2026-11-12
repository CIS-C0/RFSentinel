package com.rfsentinel.app.sdr

import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit

/**
 * Links a P25 radio heard keying up near you (its input, [SdrRadio.onP25Input]) with the Bluetooth / WiFi devices
 * around each time. A device that is there on several separate occasions, and with that radio at least two thirds of
 * the times it is near any radio, likely rides with it: the officer's phone, body cam or car router. Your own devices
 * are around for every radio, so that rule leaves them out once a few other radios have been heard. Pure: unit-tested.
 */
object P25Link {

    /** Separate occasions a device must share with a radio before it is linked. */
    const val MIN_TIMES = 3
    /** Keying up again within this is the same occasion. */
    private const val SAME_TIME_MS = 10 * 60_000L
    private const val CONFIDENCE = 60
    private const val KEEP_MS = 24 * 60 * 60_000L
    private const val PRUNE_EVERY = 200
    const val SOURCE = "P25 radio near you, the same device around each time"

    private class Shared(var times: Int = 0, var last: Long = Long.MIN_VALUE / 2)

    /** (radio, device) → shared occasions; device → occasions with any radio. */
    private val shared = HashMap<Int, HashMap<String, Shared>>()
    private val anyRadio = HashMap<String, Int>()
    private var calls = 0

    /** Radio [radio] keyed up near you at [now] with [macs] in range. */
    @Synchronized fun heard(radio: Int, macs: Collection<String>, now: Long) {
        val withRadio = shared.getOrPut(radio) { HashMap() }
        for (mac in macs) {
            val p = withRadio.getOrPut(mac) { Shared() }
            if (now - p.last < SAME_TIME_MS) { p.last = now; continue }
            p.times++; p.last = now
            anyRadio.merge(mac, 1, Int::plus)
        }
        // Every radio near you, times every device around: forget pairs not seen together for a day, or it only grows.
        if (++calls % PRUNE_EVERY == 0) prune(now)
    }

    @Synchronized internal fun prune(now: Long) {
        val radios = shared.values.iterator()
        while (radios.hasNext()) {
            val m = radios.next()
            val pairs = m.entries.iterator()
            while (pairs.hasNext()) {
                val (mac, p) = pairs.next()
                if (now - p.last <= KEEP_MS) continue
                pairs.remove()
                anyRadio.computeIfPresent(mac) { _, n -> (n - p.times).takeIf { it > 0 } }
            }
            if (m.isEmpty()) radios.remove()
        }
    }

    /** The radio [mac] rides with, and the occasions they shared, or null. */
    @Synchronized fun radioFor(mac: String): Pair<Int, Int>? {
        val all = anyRadio[mac] ?: return null
        return shared.entries.mapNotNull { (radio, m) -> m[mac]?.times?.let { radio to it } }
            .filter { (_, times) -> times >= MIN_TIMES && times * 3 >= all * 2 } // at least 2 of every 3 times with radios
            .maxByOrNull { it.second }
    }

    /** The link as a match on the device, for its classification. */
    fun hitFor(mac: String): Hit? = radioFor(mac)?.let { (radio, times) ->
        Hit(Category.PUBLIC_SAFETY, "Rides with P25 radio $radio", CONFIDENCE,
            "P25 radio $radio keyed up near you $times separate times, and this device was around each time - " +
                "more often than with any other radio. It may be that radio user's phone, body cam or vehicle.", SOURCE)
    }

    @Synchronized fun clear() { shared.clear(); anyRadio.clear() }
}

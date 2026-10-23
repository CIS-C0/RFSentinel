package com.rfsentinel.app.online

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * WiGLE results pinned on the map ("Show on map"), with the name or address that was searched.
 * They stay until removed. Kept only on this phone.
 */
object WiglePins {

    data class Pin(val net: Wigle.Net, val searched: String, val at: Long)

    private const val FILE = "wigle_pins.json"
    private const val MAX = 500

    private val gson = Gson()
    private var pins: MutableList<Pin>? = null

    /** Bumped on every change, so the map redraws only then. */
    @Volatile var version = 0; private set

    @Synchronized
    fun all(context: Context): List<Pin> = load(context).toList()

    /** Pins [nets] (an address pinned again moves to its new answer). */
    @Synchronized
    fun add(context: Context, nets: List<Wigle.Net>, searched: String, now: Long = System.currentTimeMillis()) {
        val list = load(context)
        val ids = nets.map { it.bssid }.toSet()
        list.removeAll { it.net.bssid in ids }
        nets.forEach { list += Pin(it, searched, now) }
        while (list.size > MAX) list.removeAt(0)
        save(context)
    }

    @Synchronized
    fun remove(context: Context, bssid: String) { if (load(context).removeAll { it.net.bssid == bssid }) save(context) }

    @Synchronized
    fun clear(context: Context) { load(context).clear(); save(context) }

    @Synchronized
    fun find(context: Context, bssid: String): Pin? = load(context).firstOrNull { it.net.bssid == bssid }

    private fun load(context: Context): MutableList<Pin> = pins ?: run {
        val list = runCatching {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) null else gson.fromJson<List<Pin>>(f.readText(), object : TypeToken<List<Pin>>() {}.type)
        }.getOrNull().orEmpty()
        @Suppress("SENSELESS_COMPARISON") // Gson can leave fields null
        pins = list.filter { it.net != null && it.net.bssid != null }.toMutableList()
        pins!!
    }

    private fun save(context: Context) {
        version++
        runCatching { File(context.filesDir, FILE).writeText(gson.toJson(pins.orEmpty())) }
    }
}

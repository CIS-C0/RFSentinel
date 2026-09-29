package com.rfsentinel.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * In-memory mirror of the whitelist table, so the scanner (many results per
 * second) and the live list can check it without a DB query each time.
 */
object WhitelistCache {

    @Volatile
    private var macs: Set<String> = emptySet()

    fun contains(mac: String): Boolean = mac in macs

    /** Starts mirroring the table for the lifetime of [scope]. */
    fun start(context: Context, scope: CoroutineScope) {
        scope.launch {
            AppDatabase.getInstance(context).whitelistDao().all().collect { list ->
                macs = list.mapTo(HashSet()) { it.mac }
            }
        }
    }
}

package com.rfsentinel.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import com.rfsentinel.app.detect.CellAnalyzer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Reads the cells the phone can see (passively - Android reports what the
 * modem already knows; nothing is transmitted) and feeds them to
 * [CellAnalyzer]. Needs only the location permission the app already has.
 */
class CellMonitor(private val context: Context) {

    /** Each cell's area as seen before (for the cloned-identity check), kept across sessions. */
    private val knownAreas: MutableMap<String, Int> = loadKnownAreas()
    private val analyzer = CellAnalyzer(knownAreas)
    private val tm = context.getSystemService(TelephonyManager::class.java)
    private val audio = context.getSystemService(android.media.AudioManager::class.java)
    private var lastCallAt = 0L
    private var checks = 0
    private val executor = Executors.newSingleThreadExecutor()
    private val fixes = ArrayDeque<Location>()

    /** Called by the scanner with every location fix, for the "standing still" test. */
    @Synchronized
    fun onLocation(loc: Location) {
        fixes.addLast(loc)
        while (fixes.size > 12) fixes.removeFirst()
    }

    @Synchronized
    private fun lastFix(): Location? = fixes.lastOrNull()

    /** The cells the phone sees right now, without any analysis (the cell towers screen). */
    suspend fun read(): List<CellAnalyzer.Cell> {
        val tm = tm ?: return emptyList()
        return fetch(tm)?.mapNotNull(::toCell).orEmpty()
    }

    /** Frees the reader thread of a monitor used only through [read]. */
    fun release() = executor.shutdown()

    /** True / false when GPS knows whether you've been still for ~2 minutes; null otherwise. */
    @Synchronized
    private fun stationary(now: Long): Boolean? {
        val recent = fixes.filter { now - it.time <= 3 * 60_000L }
        if (recent.size < 3 || now - recent.last().time > 60_000L) return null
        val first = recent.first()
        val span = recent.last().time - first.time
        if (span < 2 * 60_000L) return null
        val still = recent.all { (!it.hasSpeed() || it.speed < 1f) && first.distanceTo(it) < 50f }
        return still
    }

    /** One snapshot: returns any warning signs. */
    suspend fun check(): List<CellAnalyzer.Anomaly> {
        val tm = tm ?: return emptyList()
        val infos = fetch(tm) ?: return emptyList()
        val cells = infos.mapNotNull(::toCell)
        if (cells.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        CellTowerStore.record(context, cells, lastFix(), now)
        // A call (or one that just ended): networks without 4G calling drop to 2G/3G for it.
        // The audio mode tells without the phone-state permission.
        if (audio?.mode == android.media.AudioManager.MODE_IN_CALL) lastCallAt = now
        val ctx = CellAnalyzer.Context(
            time = now,
            simOperator = runCatching { tm.simOperator }.getOrNull()?.takeIf { it.length >= 5 },
            roaming = runCatching { tm.isNetworkRoaming }.getOrDefault(false),
            stationary = stationary(now),
            inCall = now - lastCallAt < CALL_GRACE_MS
        )
        return synchronized(knownAreas) {
            if (forgetCells) { knownAreas.clear(); forgetCells = false }
            analyzer.analyze(cells, ctx).also { if (++checks % SAVE_EVERY == 0) saveKnownAreas() }
        }
    }

    private val knownFile get() = java.io.File(context.filesDir, "known_cells.txt")

    /** "RAT mcc-mnc cellId<TAB>area" per line, oldest first. */
    private fun loadKnownAreas(): MutableMap<String, Int> = LinkedHashMap<String, Int>().also { map ->
        runCatching {
            knownFile.takeIf { it.exists() }?.forEachLine { line ->
                val tab = line.lastIndexOf('\t')
                if (tab > 0) line.substring(tab + 1).toIntOrNull()?.let { map[line.substring(0, tab)] = it }
            }
        }
    }

    private fun saveKnownAreas() = synchronized(knownAreas) {
        if (forgetCells) { knownAreas.clear(); forgetCells = false }
        runCatching {
            knownFile.writeText(knownAreas.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        }
    }

    /** Fresh cell info on Android 10+ (falls back to the cached list). */
    @SuppressLint("MissingPermission") // location permission is checked by the scanner before calling
    private suspend fun fetch(tm: TelephonyManager): List<CellInfo>? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine { cont ->
                    tm.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) { if (cont.isActive) cont.resume(cellInfo) }
                        override fun onError(errorCode: Int, detail: Throwable?) { if (cont.isActive) cont.resume(tm.allCellInfo) }
                    })
                }
            } ?: tm.allCellInfo
        } else {
            tm.allCellInfo
        }
    }.getOrNull()

    private fun Int.orNull() = takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE && it >= 0 }

    private fun toCell(info: CellInfo): CellAnalyzer.Cell? {
        val reg = info.isRegistered
        return when (info) {
            is CellInfoLte -> info.cellIdentity.let {
                CellAnalyzer.Cell(CellAnalyzer.Rat.LTE, reg, mcc(it.mccCompat()), mnc(it.mncCompat()), it.tac.orNull(), it.ci.orNull()?.toLong(),
                    info.cellSignalStrength.dbm, it.earfcn.orNull(), it.pci.orNull(), opName(it))
            }
            is CellInfoGsm -> info.cellIdentity.let {
                CellAnalyzer.Cell(CellAnalyzer.Rat.GSM, reg, mcc(it.mccCompat()), mnc(it.mncCompat()), it.lac.orNull(), it.cid.orNull()?.toLong(),
                    info.cellSignalStrength.dbm, it.arfcn.orNull(), it.bsic.orNull(), opName(it))
            }
            is CellInfoWcdma -> info.cellIdentity.let {
                CellAnalyzer.Cell(CellAnalyzer.Rat.WCDMA, reg, mcc(it.mccCompat()), mnc(it.mncCompat()), it.lac.orNull(), it.cid.orNull()?.toLong(),
                    info.cellSignalStrength.dbm, it.uarfcn.orNull(), it.psc.orNull(), opName(it))
            }
            is CellInfoCdma -> CellAnalyzer.Cell(CellAnalyzer.Rat.CDMA, reg, null, null,
                info.cellIdentity.networkId.orNull(), info.cellIdentity.basestationId.orNull()?.toLong(), info.cellSignalStrength.dbm)
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) newer(info, reg) else null
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun newer(info: CellInfo, reg: Boolean): CellAnalyzer.Cell? = when (info) {
        is CellInfoNr -> (info.cellIdentity as CellIdentityNr).let {
            CellAnalyzer.Cell(CellAnalyzer.Rat.NR, reg, it.mccString, it.mncString, it.tac.orNull(),
                it.nci.takeIf { n -> n != CellInfo.UNAVAILABLE_LONG && n >= 0 }, info.cellSignalStrength.dbm,
                it.nrarfcn.orNull(), it.pci.orNull(), opName(it))
        }
        is CellInfoTdscdma -> info.cellIdentity.let {
            CellAnalyzer.Cell(CellAnalyzer.Rat.TDSCDMA, reg, it.mccString, it.mncString, it.lac.orNull(), it.cid.orNull()?.toLong(),
                info.cellSignalStrength.dbm, it.uarfcn.orNull(), it.cpid.orNull(), opName(it))
        }
        else -> null
    }

    /** The network name the cell broadcasts (API 28+). */
    private fun opName(id: Any): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (id as? android.telephony.CellIdentity)
            ?.let { (it.operatorAlphaLong ?: it.operatorAlphaShort)?.toString()?.takeIf { s -> s.isNotBlank() } }
        else null

    // Operator codes: the String getters are API 28+; older phones only have the int ones.
    private fun mcc(v: String?) = v?.takeIf { it.isNotBlank() }
    private fun mnc(v: String?) = v?.takeIf { it.isNotBlank() }

    @Suppress("DEPRECATION")
    private fun android.telephony.CellIdentityLte.mccCompat() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mccString else mcc.orNull()?.let { "%03d".format(it) }
    @Suppress("DEPRECATION")
    private fun android.telephony.CellIdentityLte.mncCompat() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mncString else mnc.orNull()?.let { "%02d".format(it) }
    @Suppress("DEPRECATION")
    private fun android.telephony.CellIdentityGsm.mccCompat() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mccString else mcc.orNull()?.let { "%03d".format(it) }
    @Suppress("DEPRECATION")
    private fun android.telephony.CellIdentityGsm.mncCompat() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mncString else mnc.orNull()?.let { "%02d".format(it) }
    @Suppress("DEPRECATION")
    private fun android.telephony.CellIdentityWcdma.mccCompat() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mccString else mcc.orNull()?.let { "%03d".format(it) }
    @Suppress("DEPRECATION")
    private fun android.telephony.CellIdentityWcdma.mncCompat() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mncString else mnc.orNull()?.let { "%02d".format(it) }

    fun close() {
        saveKnownAreas()
        CellTowerStore.save(context)
        executor.shutdown()
    }

    companion object {
        /** Drops to 2G this long after a call are still put down to the call. */
        private const val CALL_GRACE_MS = 2 * 60_000L
        /** The cell memory is written every this many checks (~5 min), and when scanning stops. */
        private const val SAVE_EVERY = 20

        /** Set by "Forget device history": a running monitor empties its cell memory too. */
        @Volatile private var forgetCells = false

        /** Erases the remembered cells (which towers you were near) - with the device history. */
        fun forget(context: Context) {
            forgetCells = true
            runCatching { java.io.File(context.filesDir, "known_cells.txt").delete() }
        }

        /** Last warning sign raised (for the main screen), and when. */
        @Volatile var lastAnomaly: Pair<Long, CellAnalyzer.Anomaly>? = null
    }
}

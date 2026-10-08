package com.rfsentinel.app.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.online.WazePolice
import com.rfsentinel.app.online.WazeTiming
import com.rfsentinel.app.util.Prefs

/** Small helpers shared by the screens that offer "Check Waze now" and "Waze status". */
object WazeUi {

    /** Waze reports are switched on (the category, and the warning for the chosen source accepted). */
    fun on(context: Context): Boolean = Prefs.categoryEnabled(context, Category.POLICE_REPORT) && Prefs.wazeReady(context)

    /**
     * Asks the poller to check Waze right now and tells the user what happened. Returns true only
     * when a check was asked for; checks are spaced out (10 s on Waze direct, 60 s on OpenWeb Ninja).
     */
    fun checkNow(context: Context): Boolean {
        val (message, asked) = when {
            !on(context) -> "Turn on Waze reports in Settings first" to false
            Prefs.wazePaused(context) -> "Waze checks are paused - resume them in Waze status" to false
            !WazePolice.running -> "Waze is checked while scanning - start scanning first" to false
            else -> {
                val wait = WazePolice.requestCheckNow(WazeTiming.minGapS(Prefs.wazeBackend(context) == Prefs.WAZE_DIRECT))
                if (wait == null) "Checking Waze now..." to true else "Checked a moment ago - try again in $wait s" to false
            }
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        return asked
    }

    fun openStatus(context: Context) = context.startActivity(Intent(context, WazeStatusActivity::class.java))
}

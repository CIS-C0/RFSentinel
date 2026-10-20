package com.rfsentinel.app.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** What the screens tell the user after [MapSafety.recover] found that the app closed while the map was starting. */
internal object MapSafetyUi {

    const val OPENGL_NOTE = "The map closed the app last time. Trying it again with the compatibility renderer."
    const val OFF_TITLE = "The map is switched off"
    const val OFF_MESSAGE = "The app closed twice while starting the map on this phone, so the map was taken off the " +
        "main screen. Everything else keeps working. If it keeps happening, please send the crash log (Settings > Lists, " +
        "tools & about > Export crash log) so the cause can be found."

    /**
     * Tells the user; [onTryAgain] (the full screen map) offers to start the map anyway, otherwise the dialog just says
     * how it is. [onNotNow] runs when the user leaves it off.
     */
    fun tell(activity: AppCompatActivity, recovery: MapSafety.Recovery, onTryAgain: (() -> Unit)? = null, onNotNow: (() -> Unit)? = null) {
        when (recovery) {
            MapSafety.Recovery.NONE -> onTryAgain?.invoke()
            MapSafety.Recovery.OPENGL -> {
                Toast.makeText(activity, OPENGL_NOTE, Toast.LENGTH_LONG).show()
                onTryAgain?.invoke()
            }
            MapSafety.Recovery.MAP_OFF -> {
                val b = MaterialAlertDialogBuilder(activity).setTitle(OFF_TITLE).setCancelable(onTryAgain == null)
                if (onTryAgain == null) b.setMessage(OFF_MESSAGE).setPositiveButton("OK", null)
                else b.setMessage("$OFF_MESSAGE\n\nTry the map again now?")
                    .setPositiveButton("Try the map again") { _, _ -> onTryAgain() }
                    .setNegativeButton("Not now") { _, _ -> onNotNow?.invoke() }
                    .setOnCancelListener { onNotNow?.invoke() }
                b.show()
            }
        }
    }
}

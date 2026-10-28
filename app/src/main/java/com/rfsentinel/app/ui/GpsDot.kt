package com.rfsentinel.app.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.rfsentinel.app.service.ScanForegroundService

/**
 * The GPS dot on the main screen's status: red without a position (or one older than the scan's
 * [ScanForegroundService.gpsStaleMs]: 10 s while it asks for frequent fixes, longer on balanced),
 * yellow while it's rougher than [GOOD_M], green from [GOOD_M] down, with the accuracy beside it.
 */
object GpsDot {

    const val GOOD_M = 20f

    enum class Level(val color: Int, val words: String) {
        NONE(0xFFB3261E.toInt(), "no position"),
        ROUGH(0xFFE0A33A.toInt(), "rough"),
        GOOD(0xFF2E7D32.toInt(), "good")
    }

    fun level(at: Long, accuracyM: Float?, now: Long, staleMs: Long = 10_000L): Level = when {
        accuracyM == null || now - at > staleMs -> Level.NONE
        accuracyM > GOOD_M -> Level.ROUGH
        else -> Level.GOOD
    }

    /** "● GPS 8 m" (or "● GPS - no position") with the dot coloured, for a status line. */
    fun text(context: Context, demo: Boolean = false): CharSequence {
        val now = System.currentTimeMillis()
        val acc = if (demo) 5f else ScanForegroundService.gpsAccuracyM
        val lv = if (demo) Level.GOOD else level(ScanForegroundService.gpsAt, acc, now, ScanForegroundService.gpsStaleMs)
        val b = SpannableStringBuilder("●")
        b.setSpan(ForegroundColorSpan(ThemeManager.ink(context, lv.color)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.append(if (lv == Level.NONE) " GPS - no position" else " GPS ${acc!!.toInt()} m")
        return b
    }
}

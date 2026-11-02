package com.rfsentinel.app.sdr

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real calls recorded on the phone ([P25Record]), checked on this computer: set P25_REC_DIR to a folder of them.
 * Skipped otherwise. Recordings stay out of the repository: they are other people's radio calls.
 */
class P25RecordingsCheck {

    @Test
    fun decodeRecordedCalls() {
        val dir = System.getenv("P25_REC_DIR")?.let(::File)
        assumeTrue(dir?.isDirectory == true)
        for (f in dir!!.listFiles { x -> x.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }) {
            val out = P25Voice.decodeRecording(f.readBytes())
            val words = out.frames.flatMap { it.voice }
            val errors = words.sumOf { it.errors }
            println("${f.name}: ${out.syncs} syncs, ${out.frames.size} voice frames (" +
                "${out.frames.count { it.nid.duid == P25Voice.DUID_LDU1 }} LDU1), ${words.size} codewords, " +
                "%.2f bit errors each, algid ${out.frames.mapNotNull { it.algid }.distinct().joinToString { "%02X".format(it) }}"
                    .format(java.util.Locale.US, if (words.isEmpty()) 0.0 else errors.toDouble() / words.size))
        }
    }
}

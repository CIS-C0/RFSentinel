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
        for (f in dir!!.listFiles { x -> x.isFile && x.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }) {
            // Simulcast sites send LSM: whichever finds more frames.
            val out = listOf(P25Demod.Mod.C4FM, P25Demod.Mod.LSM).map { P25Voice.decodeRecording(f.readBytes(), it) }.maxBy { it.frames.size }
            val words = out.frames.flatMap { it.voice }
            val errors = words.sumOf { it.errors }
            println("${f.name}: ${out.syncs} syncs, ${out.frames.size} voice frames (" +
                "${out.frames.count { it.nid.duid == P25Voice.DUID_LDU1 }} LDU1), ${words.size} codewords, " +
                "%.2f".format(java.util.Locale.US, if (words.isEmpty()) 0.0 else errors.toDouble() / words.size) + " bit errors each, " +
                "algid ${out.frames.mapNotNull { it.algid }.distinct().joinToString { "%02X".format(it) }}, " +
                "Reed-Solomon passed: ${out.frames.count { it.lc != null }} LC, ${out.frames.count { it.algid != null }} ES")
            // The speech, to listen to: next to the recordings, still outside the repository.
            val speech = P25Voice.speechWav(out.frames)
            if (speech.isNotEmpty()) File(dir, "speech").apply { mkdirs() }.resolve(f.name).writeBytes(speech)
        }
    }
}

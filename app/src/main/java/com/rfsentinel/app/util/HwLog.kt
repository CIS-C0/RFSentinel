package com.rfsentinel.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * One piece of hardware's log, for testers to export from Settings › External hardware: recent lines in memory and
 * files/[file] (the previous one kept as [file].1), so it survives a restart. Privacy-safe lines only: no nearby
 * device's address or name, no server name or IP address ([redact]).
 */
class HwLog(val title: String, val file: String) {
    private val lines = ArrayDeque<String>()
    private val lastShape = ConcurrentHashMap<String, String>()

    fun log(line: String) {
        Log.d("HwLog", "$title: $line")
        synchronized(lines) {
            val now = Date()
            lines.addLast(SimpleDateFormat("HH:mm:ss", Locale.US).format(now) + "  " + line)
            while (lines.size > 300) lines.removeFirst()
            dir?.let { File(it, file) }?.let { f ->
                runCatching {
                    if (f.length() > MAX_BYTES) { val old = File(f.path + ".1"); old.delete(); f.renameTo(old) }
                    f.appendText(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(now) + "  " + line + "\n")
                }
            }
        }
    }

    /**
     * A status line, once per change: numbers in it (counts, levels) don't count as a change, so a live count doesn't
     * fill the log. An empty status logs "[who]: stopped" once.
     */
    fun logStatus(who: String, s: String) {
        // "1 node" and "12 nodes" are the same status.
        val shape = s.replace(Regex("\\d+(\\.\\d+)?"), "#").replace(Regex("(# \\w+?)s\\b"), "$1")
        val before = lastShape.put(who, shape)
        if (before == shape || (s.isEmpty() && before == null)) return
        log(s.ifEmpty { "$who: stopped" })
    }

    /** The lines in memory (this run only). */
    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    /** Whether there is anything to export. */
    fun isEmpty(context: Context): Boolean = synchronized(lines) { lines.isEmpty() } && files(context).isEmpty()

    private fun files(context: Context) =
        listOf(File(context.filesDir, "$file.1"), File(context.filesDir, file)).filter { it.exists() && it.length() > 0 }

    /** The text file to send: app, phone, [status], [extra] (the USB devices plugged in, say) and the log, oldest first. */
    fun report(context: Context, status: String, extra: String? = null): String {
        val sb = StringBuilder()
        sb.append("RF Sentinel - $title log\n")
        sb.append("App ${com.rfsentinel.app.BuildConfig.VERSION_NAME} (${com.rfsentinel.app.BuildConfig.VERSION_CODE})")
            .append(if (com.rfsentinel.app.BuildConfig.DEBUG) " debug\n" else "\n")
        sb.append("Phone ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("Exported ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n")
        sb.append("Status: ${status.ifBlank { "not running" }}\n\n")
        if (extra != null) sb.append(extra).append('\n')
        sb.append("Log, oldest first:\n")
        val files = files(context)
        if (files.isEmpty()) sb.append(text().ifBlank { "(empty - connect it and start a scan first)" }).append('\n')
        else synchronized(lines) { files.forEach { f -> sb.append(runCatching { f.readText() }.getOrDefault("")) } }
        return sb.toString()
    }

    companion object {
        private const val MAX_BYTES = 1_000_000L
        @Volatile private var dir: File? = null

        /** Where the log files go; set when the app starts. */
        fun init(context: Context) { dir = context.applicationContext.filesDir }

        /** An error for a log: its type, message and where it happened. */
        fun describe(e: Throwable): String =
            "${e.javaClass.simpleName}: ${e.message}" + (e.stackTrace.firstOrNull()?.let { " (${it.fileName}:${it.lineNumber})" } ?: "")

        /** [text] without a server's name or any IP address in it, for a log. */
        fun redact(text: String, host: String?): String =
            (if (host.isNullOrEmpty()) text else text.replace(host, "the server"))
                .replace(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b"), "an IP address")
                .replace(Regex("\\[?[0-9a-fA-F]{0,4}(:[0-9a-fA-F]{0,4}){2,7}\\]?"), "an IP address")
    }
}

/** Each piece of hardware's own log. */
object HwLogs {
    val usbWifi = HwLog("USB WiFi adapter", "usbwifi.log")
    val usbBt = HwLog("USB Bluetooth adapter", "usbbt.log")
    val sdr = HwLog("RTL-SDR / HackRF", "sdr.log")
    val esp = HwLog("ESP32 board", "esp32.log")
    val meshRadio = HwLog("Mesh radio (Meshtastic / MeshCore)", "meshradio.log")
    val ouiSpy = HwLog("OUI-SPY over Bluetooth", "ouispy.log")
    val meshNode = HwLog("Sentinel Link Meshtastic node", "meshnode.log")
    val link = HwLog("Sentinel Link Bluetooth", "link.log")
    val relay = HwLog("Sentinel Link internet relay", "relay.log")
    val all = listOf(usbWifi, usbBt, sdr, esp, meshRadio, ouiSpy, meshNode, link, relay)
}

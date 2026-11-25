// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.core

import android.content.Context
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * An error report the player chooses to send. Nothing is sent unless they ask: the app keeps a diary
 * of its last sessions on the headset, and this posts the end of it, with anything that could
 * identify the player or their network struck out first.
 */
object Report {
    /** Where reports are received. See tools/report-worker. */
    const val ENDPOINT = "https://reports.allbele.com/farsofa"

    private const val DIARY_TAIL = 150_000
    private const val SESSION_TAIL = 100_000

    /** What would be sent, so it can be shown or tested. */
    fun compose(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val header = "Farsofa ${info.versionName} | ${Build.MANUFACTURER} ${Build.MODEL} | ${Build.DISPLAY} | sdk ${Build.VERSION.SDK_INT}\n"
        return header +
            "\n--- diary ---\n" + redact(tail(Diagnostics.diaryFile(context), DIARY_TAIL)) +
            "\n--- last session, library ---\n" + redact(tail(File(context.filesDir, "session.log"), SESSION_TAIL))
    }

    /** Sends the report and returns the short code the receiver gave it. Throws when it cannot. Not for the main thread. */
    fun send(context: Context): String {
        val body = compose(context).toByteArray()
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.setRequestProperty("User-Agent", "Farsofa")
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) throw java.io.IOException("HTTP $status")
            return connection.inputStream.bufferedReader().use { it.readText() }.trim().take(16)
        } finally {
            connection.disconnect()
        }
    }

    private fun tail(file: File, bytes: Int): String = try {
        if (!file.exists()) "(none)\n" else {
            val length = file.length()
            file.inputStream().use { input ->
                if (length > bytes) input.skip(length - bytes)
                String(input.readBytes()).let { if (length > bytes) it.substringAfter('\n') else it }
            }
        }
    } catch (e: Exception) {
        "(unreadable: ${e.message})\n"
    }

    private val ADDRESS = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
    private val ADDRESS_V6 = Regex("""\b(?:[0-9a-fA-F]{1,4}:){4,7}[0-9a-fA-F]{1,4}\b""")
    private val HARDWARE = Regex("""\b[0-9a-fA-F]{2}(?:[:-][0-9a-fA-F]{2}){5}\b""")
    private val LONG_ID = Regex("""\b[0-9a-fA-F]{16,}\b""")
    private val SECRET = Regex("""(?i)(token|bearer|authorization|account[_ ]?id|nickname|regist[_ ]?key|morning|duid)\s*[=:]\s*\S+""")

    /** Strikes out network addresses, hardware ids, long identifiers and anything labelled as a secret. */
    fun redact(text: String): String = text
        .replace(SECRET) { it.groupValues[1] + "=[removed]" }
        .replace(HARDWARE, "[mac]")
        .replace(ADDRESS_V6, "[address]")
        .replace(ADDRESS, "[address]")
        .replace(LONG_ID, "[id]")
}

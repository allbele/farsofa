// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.psn

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore

/**
 * The certificate authorities the native code trusts when it talks to PSN.
 *
 * Android keeps its trusted authorities where only its own network stack looks. The library reaches
 * Sony's servers with its own copy of curl, which knows none of them and would refuse every secure
 * connection. So the system's list is written out as a file, at the one path that copy of curl was
 * built to read ([COMPILED_PATH], which must match CURL_CA_BUNDLE in app/build.gradle.kts).
 */
object CaBundle {
    const val COMPILED_PATH = "/data/user/0/app.farsofa.vr/files/ca.pem"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** Writes the file if it is missing or a week old. Not for the main thread. */
    @Synchronized
    fun ensure(context: Context) {
        val file = File(context.filesDir, "ca.pem")
        if (file.absolutePath != COMPILED_PATH && file.canonicalPath != File(COMPILED_PATH).canonicalPath) {
            Log.w(TAG, "The certificate file is not where the native code looks for it: ${file.absolutePath}")
        }
        if (file.length() > 0 && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) return
        try {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val pem = StringBuilder()
            var count = 0
            for (alias in store.aliases()) {
                val certificate = store.getCertificate(alias) ?: continue
                pem.append("-----BEGIN CERTIFICATE-----\n")
                pem.append(Base64.encodeToString(certificate.encoded, Base64.DEFAULT))
                pem.append("-----END CERTIFICATE-----\n")
                count++
            }
            val temporary = File(context.filesDir, "ca.pem.new")
            temporary.writeText(pem.toString())
            if (!temporary.renameTo(file)) temporary.copyTo(file, overwrite = true)
            Log.i(TAG, "Wrote $count certificate authorities for the native code")
        } catch (e: Exception) {
            Log.e(TAG, "Could not write the certificate authorities", e)
        }
    }

    private const val TAG = "FarsofaPsn"
}

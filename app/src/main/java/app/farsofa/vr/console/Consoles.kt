// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.console

import android.content.Context
import android.util.Base64
import app.farsofa.vr.core.SecureStore
import org.json.JSONArray
import org.json.JSONObject

/** A console seen on the local network right now. */
data class DiscoveredConsole(
    val id: String,
    val name: String,
    val host: String,
    val state: State,
    val ps5: Boolean,
    val runningApp: String?,
) {
    enum class State { READY, STANDBY, UNKNOWN }
}

/** A console this headset has paired with. [id] is the console MAC as upper-case hex. */
class RegisteredConsole(
    val id: String,
    val nickname: String,
    val registKey: ByteArray,
    val rpKey: ByteArray,
    val lastHost: String?,
) {
    /** Credential the console expects in a wake-up packet: the registration key read as a hex number. */
    val wakeCredential: Long?
        get() {
            val end = registKey.indexOf(0).let { if (it >= 0) it else registKey.size }
            return String(registKey, 0, end, Charsets.US_ASCII).toULongOrNull(16)?.toLong()
        }
}

fun ByteArray.toHexId(): String = joinToString("") { "%02X".format(it) }

class ConsoleStore(context: Context) {
    private val store = SecureStore(context, "consoles")

    fun all(): List<RegisteredConsole> {
        val json = store.get(KEY) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                RegisteredConsole(
                    id = item.getString("id"),
                    nickname = item.getString("nickname"),
                    registKey = Base64.decode(item.getString("registKey"), Base64.NO_WRAP),
                    rpKey = Base64.decode(item.getString("rpKey"), Base64.NO_WRAP),
                    lastHost = item.optString("lastHost").ifEmpty { null },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(console: RegisteredConsole) {
        write(all().filter { it.id != console.id } + console)
    }

    fun remove(id: String) {
        write(all().filter { it.id != id })
    }

    private fun write(consoles: List<RegisteredConsole>) {
        val array = JSONArray()
        consoles.forEach { console ->
            array.put(
                JSONObject()
                    .put("id", console.id)
                    .put("nickname", console.nickname)
                    .put("registKey", Base64.encodeToString(console.registKey, Base64.NO_WRAP))
                    .put("rpKey", Base64.encodeToString(console.rpKey, Base64.NO_WRAP))
                    .put("lastHost", console.lastHost ?: "")
            )
        }
        store.put(KEY, array.toString())
    }

    private companion object {
        const val KEY = "registered"
    }
}

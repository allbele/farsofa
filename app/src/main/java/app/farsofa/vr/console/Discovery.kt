// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.console

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import app.farsofa.vr.core.Native
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.InetAddress

/** Finds consoles on the local network while started. */
class Discovery(context: Context) : Native.DiscoveryListener {
    private val appContext = context.applicationContext
    private val multicastLock = (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("farsofa.discovery")
        .apply { setReferenceCounted(false) }

    private var nativePtr = 0L

    private val _consoles = MutableStateFlow<List<DiscoveredConsole>>(emptyList())
    val consoles: StateFlow<List<DiscoveredConsole>> = _consoles

    fun start() {
        if (nativePtr != 0L) return
        multicastLock.acquire()
        nativePtr = Native.discoveryStart(subnetBroadcastAddress(), this)
    }

    fun stop() {
        if (nativePtr == 0L) return
        Native.discoveryStop(nativePtr)
        nativePtr = 0L
        multicastLock.release()
        _consoles.value = emptyList()
    }

    fun wake(host: String, console: RegisteredConsole): Boolean {
        val credential = console.wakeCredential ?: return false
        return Native.wakeup(nativePtr, host, credential, true)
    }

    override fun onHosts(fields: Array<String?>) {
        _consoles.value = parseHosts(fields)
    }

    private fun subnetBroadcastAddress(): String? {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val properties = connectivity.getLinkProperties(connectivity.activeNetwork ?: return null) ?: return null
        val link = properties.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return broadcastAddress(link.address.address, link.prefixLength)
    }

    companion object {
        fun parseHosts(fields: Array<String?>): List<DiscoveredConsole> =
            fields.toList().chunked(Native.DISCOVERY_FIELDS).mapNotNull { host ->
                if (host.size < Native.DISCOVERY_FIELDS) return@mapNotNull null
                val address = host[1] ?: return@mapNotNull null
                val id = host[4] ?: return@mapNotNull null
                DiscoveredConsole(
                    id = id.uppercase(),
                    name = host[2] ?: host[3] ?: address,
                    host = address,
                    state = when (host[0]) {
                        "ready" -> DiscoveredConsole.State.READY
                        "standby" -> DiscoveredConsole.State.STANDBY
                        else -> DiscoveredConsole.State.UNKNOWN
                    },
                    ps5 = host[5] == "1",
                    runningApp = host[6]?.ifEmpty { null },
                )
            }

        /** Directed broadcast address for an IPv4 address and prefix length, or null if not applicable. */
        fun broadcastAddress(address: ByteArray, prefixLength: Int): String? {
            if (address.size != 4 || prefixLength !in 1..30) return null
            val ip = address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xff) }
            val hostMask = (1 shl (32 - prefixLength)) - 1
            val broadcast = ip or hostMask
            val bytes = ByteArray(4) { (broadcast ushr (24 - it * 8)).toByte() }
            return InetAddress.getByAddress(bytes).hostAddress
        }
    }
}

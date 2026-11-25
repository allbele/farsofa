// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.Sensor
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a diary of what the app did, so that a failure can be understood afterwards. It is written
 * to a file inside the app and, in test builds, to the system log as well. Nothing leaves the
 * headset unless the player sends an error report (see [Report]); the diary holds no account, no
 * console name and no network address.
 */
object Diagnostics {
    private const val TAG = "FarsofaDiag"
    const val STATS_INTERVAL_MS = 5000L

    private var lastFrames = 0.0
    private var lastLost = 0.0
    private var lastBytes = 0.0

    private const val MAX_BYTES = 256_000L
    private const val KEEP_BYTES = 128_000

    /** Test builds also write to the system log, which is read over USB. */
    private var toSystemLog = false
    private var file: File? = null
    private var writesSinceCheck = 0
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun diaryFile(context: Context): File = File(context.filesDir, "diary.log")

    /** Call once per activity; later calls change nothing. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        toSystemLog = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        file = diaryFile(context.applicationContext)
        trim()
    }

    @Synchronized
    fun event(message: String) {
        if (toSystemLog) Log.i(TAG, message)
        val target = file ?: return
        try {
            target.appendText(stamp.format(Date()) + " " + message + "\n")
            if (++writesSinceCheck >= 200) trim()
        } catch (e: Exception) {
            // a full disk must not take the game down with it
        }
    }

    /** Keeps the newer half once the diary has grown past its limit. */
    private fun trim() {
        writesSinceCheck = 0
        val target = file ?: return
        try {
            if (target.length() <= MAX_BYTES) return
            val text = target.readText()
            target.writeText(text.takeLast(KEEP_BYTES).substringAfter('\n'))
        } catch (e: Exception) {
            // keep writing to it as it is
        }
    }

    /** One block describing the headset, the network and every controller Android can see. */
    fun sessionStarted(context: Context) {
        init(context)
        lastFrames = 0.0
        lastLost = 0.0
        lastBytes = 0.0
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        event("session start app=${info.versionName} device=${Build.MANUFACTURER} ${Build.MODEL} os=${Build.DISPLAY} sdk=${Build.VERSION.SDK_INT}")
        event(wifi(context))
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.isVirtual) continue
            describe(device)
        }
    }

    fun streamStats(context: Context, stats: DoubleArray) {
        if (stats.size < 7) return
        val seconds = STATS_INTERVAL_MS / 1000.0
        val fps = (stats[3] - lastFrames) / seconds
        val lost = stats[4] - lastLost
        val mbit = (stats[6] - lastBytes) * 8 / 1e6 / seconds
        lastFrames = stats[3]
        lastLost = stats[4]
        lastBytes = stats[6]
        event(
            String.format(
                Locale.US,
                "stream fps=%.1f video=%.1fMbit/s measured=%.1fMbit/s loss=%.2f%% rtt=%.1fms lost=%.0f lostTotal=%.0f recoveredTotal=%.0f | %s",
                fps, mbit, stats[0], stats[1] * 100, stats[2], lost, stats[4], stats[5], wifi(context),
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun wifi(context: Context): String = try {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connection = manager.connectionInfo
        "wifi rssi=${connection.rssi}dBm link=${connection.linkSpeed}Mbps tx=${connection.txLinkSpeedMbps} rx=${connection.rxLinkSpeedMbps} freq=${connection.frequency}MHz"
    } catch (e: Exception) {
        "wifi unavailable: ${e.message}"
    }

    /** Everything a controller offers: this is how we learn what a DualSense exposes on this headset. */
    private fun describe(device: InputDevice) {
        val axes = device.motionRanges.joinToString(",") { android.view.MotionEvent.axisToString(it.axis).removePrefix("AXIS_") }
        val keys = intArrayOf(
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_1,
        )
        val present = device.hasKeys(*keys)
        val names = listOf("MODE", "SELECT", "START", "THUMBL", "THUMBR", "BUTTON_1")
            .filterIndexed { index, _ -> present[index] }.joinToString(",")
        var extras = ""
        if (Build.VERSION.SDK_INT >= 31) {
            val sensors = device.sensorManager.getSensorList(Sensor.TYPE_ALL).joinToString(",") { it.stringType.substringAfterLast('.') }
            extras = " vibrators=${device.vibratorManager.vibratorIds.size} sensors=[$sensors] lights=${device.lightsManager.lights.size}" +
                " battery=${device.batteryState.isPresent}"
        }
        event(
            "input id=${device.id} name=\"${device.name}\" vendor=${"%04x".format(device.vendorId)} product=${"%04x".format(device.productId)}" +
                " sources=${"%x".format(device.sources)} external=${device.isExternal} axes=[$axes] keys=[$names]$extras"
        )
    }
}

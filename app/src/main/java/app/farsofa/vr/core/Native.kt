// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.core

import android.view.Surface

/**
 * JNI surface of the protocol core (app/src/main/cpp/core/bridge.cpp).
 * Listener methods are called on native threads.
 */
object Native {
    init {
        System.loadLibrary("farsofa")
    }

    const val DISCOVERY_FIELDS = 7

    const val REGIST_SUCCESS = 0
    const val REGIST_FAILED = 1
    const val REGIST_CANCELED = 2

    const val RESOLUTION_720P = 3
    const val RESOLUTION_1080P = 4

    const val CODEC_H264 = 0
    const val CODEC_HEVC = 1

    interface DiscoveryListener {
        /** Flat array, [DISCOVERY_FIELDS] entries per console: state, address, name, type, id, ps5 flag, running app. */
        fun onHosts(fields: Array<String?>)
    }

    interface RegistListener {
        fun onRegistResult(status: Int, nickname: String?, mac: ByteArray?, registKey: ByteArray?, rpKey: ByteArray?)
    }

    interface SessionListener {
        fun onConnected()
        fun onLoginPinRequest(incorrect: Boolean)
        fun onQuit(reason: Int, isError: Boolean, name: String?, detail: String?)
        fun onRumble(left: Int, right: Int)

        /** Adaptive trigger effects: right then left trigger, a mode byte and ten parameters each. */
        fun onTriggerEffects(effects: ByteArray)

        /** [what] 0: light bar colour as 0xRRGGBB. [what] 1: player number from zero. */
        fun onPadLight(what: Int, value: Int)

        /** The console's strength setting, 0 off to 3 weak (1 is strong). [what] 0: vibration, 1: triggers. */
        fun onPadIntensity(what: Int, value: Int)
        fun onMotionReset()

        /** A packet of haptics, 16-bit stereo at 3 kHz. Only after [sessionForwardHaptics]. */
        fun onHaptics(pcm: ByteArray)
    }

    /** Where the library writes the log of the session in progress, replaced at each new session. */
    @JvmStatic external fun setLogDirectory(path: String)

    @JvmStatic external fun generateDuid(): String?

    @JvmStatic external fun discoveryStart(subnetBroadcast: String?, listener: DiscoveryListener): Long
    @JvmStatic external fun discoveryStop(ptr: Long)
    @JvmStatic external fun wakeup(discoveryPtr: Long, host: String, credential: Long, ps5: Boolean): Boolean

    @JvmStatic external fun registStart(host: String, broadcast: Boolean, accountId: ByteArray, pin: Int, listener: RegistListener): Long
    @JvmStatic external fun registFree(ptr: Long)

    @JvmStatic external fun sessionCreate(
        host: String, registKey: ByteArray, morning: ByteArray,
        resolutionPreset: Int, fps: Int, bitrateKbps: Int, codec: Int,
        listener: SessionListener,
    ): Long
    /** First element "ok" then "duid\tname\t1|0" per console of the account, or "error" and a reason. Blocks. */
    @JvmStatic external fun psnListDevices(token: String): Array<String>?

    /** A session for a console reached through PSN. Returns 0 on bad input. Call [psnConnect] before [sessionStart]. */
    @JvmStatic external fun psnSessionCreate(
        consoleDuid: String, token: String, accountId: ByteArray,
        resolutionPreset: Int, fps: Int, bitrateKbps: Int, codec: Int,
        listener: SessionListener,
    ): Long

    /** Opens the route to the console. Blocks for many seconds. Empty when it worked, else what failed. */
    @JvmStatic external fun psnConnect(ptr: Long): String
    @JvmStatic external fun psnCancel(ptr: Long)

    @JvmStatic external fun sessionStart(ptr: Long): Boolean
    @JvmStatic external fun sessionStop(ptr: Long)
    @JvmStatic external fun sessionFree(ptr: Long)
    @JvmStatic external fun sessionSetSurface(ptr: Long, surface: Surface?)
    @JvmStatic external fun sessionSetLoginPin(ptr: Long, pin: String)

    /** Bitrate (Mbit/s), packet loss (0..1), round trip (ms), frames, frames lost, frames recovered, video bytes. */
    @JvmStatic external fun sessionStats(ptr: Long): DoubleArray
    @JvmStatic external fun sessionSetController(
        ptr: Long, buttons: Int, l2: Int, r2: Int,
        leftX: Int, leftY: Int, rightX: Int, rightY: Int,
        touches: IntArray?, motion: FloatArray?,
    )

    /** One reading of the controller's motion sensors: rad/s, g and microseconds. */
    @JvmStatic external fun sessionSetMotion(
        ptr: Long, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, timeUs: Long, send: Boolean,
    )
    @JvmStatic external fun sessionClearMotion(ptr: Long)
    @JvmStatic external fun sessionForwardHaptics(ptr: Long, forward: Boolean)
}

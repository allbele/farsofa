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
    }

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
}

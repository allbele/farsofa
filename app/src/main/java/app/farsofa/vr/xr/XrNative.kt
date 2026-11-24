// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.xr

import android.app.Activity
import android.view.Surface

/** JNI surface of the immersive screen (app/src/main/cpp/xr/xr_screen.cpp). */
object XrNative {
    init {
        System.loadLibrary("farsofa")
    }

    /** Set in the Touch button state while the menu key is down; it is not a console button. */
    const val TOUCH_MENU = 1 shl 16

    /**
     * Floats per hand in [Listener.onPointer]: tracked (0 no, 1 holding a controller, 2 bare hand), origin
     * xyz relative to the centre of the scene, direction xyz, trigger or pinch, thumbstick y.
     */
    const val HAND_STRIDE = 9

    /** All methods are called on the native frame thread. */
    interface Listener {
        /**
         * The session is up. [video] is for the decoder; [bar] and [overlay] are drawn by the app.
         * [canCurve] and [canPassthrough] tell what this headset's runtime supports.
         */
        fun onSurfacesReady(video: Surface, bar: Surface, overlay: Surface, canCurve: Boolean, canPassthrough: Boolean)

        /** Touch controllers read as a gamepad, in PadState units plus [TOUCH_MENU]. */
        fun onTouchState(buttons: Int, l2: Int, r2: Int, leftX: Int, leftY: Int, rightX: Int, rightY: Int)

        /**
         * Controller rays, right hand then left, [HAND_STRIDE] floats each. Sent every frame while the
         * scene asks for the pointer. The array is reused, so copy what you keep.
         */
        fun onPointer(values: FloatArray)

        /**
         * Where the player was looking when the scene was set up, in radians: [heading] to the left of
         * straight ahead and [elevation] above the horizon. Sent once.
         */
        fun onGaze(heading: Float, elevation: Float)

        fun onXrError(message: String)

        /** The system ended the immersive session. */
        fun onXrExit()
    }

    @JvmStatic external fun start(
        activity: Activity, videoWidth: Int, videoHeight: Int,
        barWidth: Int, barHeight: Int, overlayWidth: Int, overlayHeight: Int, listener: Listener,
    ): Long

    /** Places every layer. Lengths in metres, angles in radians; see Scene.kt for the conventions. */
    @JvmStatic external fun setScene(
        ptr: Long, distance: Float, width: Float, curved: Boolean, passthrough: Boolean, screenY: Float, yaw: Float,
        pitch: Float,
        barVisible: Boolean, barDistance: Float, barElevation: Float, barWidth: Float, barHeight: Float,
        overlayVisible: Boolean, overlayMargin: Float, pointer: Boolean, follow: Boolean,
    )

    /**
     * Makes the player's current position the centre of the scene without moving the screen, and returns
     * the screen's new {yaw, pitch, distance} from there. Null until the head is tracked.
     */
    @JvmStatic external fun recentre(ptr: Long): FloatArray?

    @JvmStatic external fun stop(ptr: Long)
}

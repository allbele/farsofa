// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.input

import android.view.InputDevice
import android.view.MotionEvent

/**
 * The controller's touchpad. Android shows it as a separate pointing device next to the gamepad: left
 * alone it acts as a mouse, and once the window has captured the pointer it reports where each finger
 * is. Either way the click of the pad arrives as a mouse button.
 */
class PadTouch(private val onChanged: () -> Unit) {
    /** True while the pad is pressed down. */
    var clicked = false
        private set

    /** Two fingers as {id, x, y} each, in the console's 1920 x 1080 pad coordinates; id -1 is no finger. */
    val touches = intArrayOf(-1, 0, 0, -1, 0, 0)

    private val pointerIds = intArrayOf(-1, -1)
    private var nextId = 0

    /** Handles [event] if it comes from a controller's touchpad. Returns false for anything else. */
    fun onEvent(event: MotionEvent): Boolean {
        val pointing = event.isFromSource(InputDevice.SOURCE_TOUCHPAD) || event.isFromSource(InputDevice.SOURCE_MOUSE)
        val device = event.device
        if (!pointing || device == null) return false
        // The headset's own pointers are not part of a gamepad; those are left alone.
        if (!device.supportsSource(InputDevice.SOURCE_GAMEPAD) && !device.supportsSource(InputDevice.SOURCE_JOYSTICK)) return false

        val before = clicked
        val beforeTouches = touches.copyOf()
        when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> clicked = true
            MotionEvent.ACTION_BUTTON_RELEASE -> clicked = false
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clicked = event.buttonState != 0 && event.actionMasked != MotionEvent.ACTION_CANCEL
            else -> clicked = event.buttonState != 0
        }
        if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) readFingers(event, device)
        if (before != clicked || !beforeTouches.contentEquals(touches)) onChanged()
        return true
    }

    /** Lifts everything, for when the stream stops listening. */
    fun release() {
        clicked = false
        clearFingers()
    }

    private fun readFingers(event: MotionEvent, device: InputDevice) {
        val ended = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (ended) {
            clearFingers()
            return
        }
        val lifted = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.getPointerId(event.actionIndex) else -1
        val present = (0 until event.pointerCount).filter { event.getPointerId(it) != lifted }

        // Fingers that left give up their slot; new ones take a free slot and a fresh id.
        for (slot in 0 until 2) {
            if (pointerIds[slot] != -1 && present.none { event.getPointerId(it) == pointerIds[slot] }) {
                pointerIds[slot] = -1
                touches[slot * 3] = -1
            }
        }
        for (index in present) {
            val pointer = event.getPointerId(index)
            var slot = pointerIds.indexOf(pointer)
            if (slot < 0) {
                slot = pointerIds.indexOf(-1)
                if (slot < 0) continue
                pointerIds[slot] = pointer
                touches[slot * 3] = nextId
                nextId = (nextId + 1) and 0x7F
            }
            touches[slot * 3 + 1] = scaled(event.getX(index), device.getMotionRange(MotionEvent.AXIS_X, event.source), PAD_WIDTH)
            touches[slot * 3 + 2] = scaled(event.getY(index), device.getMotionRange(MotionEvent.AXIS_Y, event.source), PAD_HEIGHT)
        }
    }

    private fun clearFingers() {
        for (slot in 0 until 2) {
            pointerIds[slot] = -1
            touches[slot * 3] = -1
        }
    }

    companion object {
        const val PAD_WIDTH = 1920
        const val PAD_HEIGHT = 1080

        private fun scaled(value: Float, range: InputDevice.MotionRange?, size: Int): Int {
            if (range == null || range.range <= 0f) return 0
            return position((value - range.min) / range.range, size)
        }

        /** A position along the pad from 0 to 1 as one of [size] columns or rows. */
        fun position(fraction: Float, size: Int): Int = (fraction.coerceIn(0f, 1f) * (size - 1)).toInt()
    }
}

// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.input

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Controller state in the layout the console expects. */
class PadState {
    var buttons = 0
    var l2 = 0
    var r2 = 0
    var leftX = 0
    var leftY = 0
    var rightX = 0
    var rightY = 0

    companion object {
        const val CROSS = 1 shl 0
        const val CIRCLE = 1 shl 1
        const val SQUARE = 1 shl 2
        const val TRIANGLE = 1 shl 3
        const val DPAD_LEFT = 1 shl 4
        const val DPAD_RIGHT = 1 shl 5
        const val DPAD_UP = 1 shl 6
        const val DPAD_DOWN = 1 shl 7
        const val L1 = 1 shl 8
        const val R1 = 1 shl 9
        const val L3 = 1 shl 10
        const val R3 = 1 shl 11
        const val OPTIONS = 1 shl 12
        const val SHARE = 1 shl 13
        const val TOUCHPAD = 1 shl 14
        const val PS = 1 shl 15
        const val DPAD_MASK = DPAD_LEFT or DPAD_RIGHT or DPAD_UP or DPAD_DOWN
    }
}

/**
 * Translates Android gamepad events into a [PadState]. Works for any controller Android exposes as a
 * standard gamepad, which is how a DualSense paired over Bluetooth shows up.
 */
class GamepadInput(private val onChanged: (PadState) -> Unit) {
    val state = PadState()

    private var keyButtons = 0
    private var hatButtons = 0
    private var keyL2 = false
    private var keyR2 = false
    private var axisL2 = 0
    private var axisR2 = 0

    /** When the gamepad last sent anything, in SystemClock.uptimeMillis() time. */
    var lastInputTime = 0L
        private set

    /** Id of the device that last sent input, used to route rumble back to it. */
    var lastDeviceId = -1
        private set

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD) && !event.isFromSource(InputDevice.SOURCE_JOYSTICK)
            && !event.isFromSource(InputDevice.SOURCE_DPAD)
        ) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.deviceId != lastDeviceId) Log.i(TAG, "Input now from device ${event.deviceId} (${event.device?.name})")
        lastDeviceId = event.deviceId
        lastInputTime = event.eventTime

        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> keyL2 = down
            KeyEvent.KEYCODE_BUTTON_R2 -> keyR2 = down
            else -> {
                val mask = buttonMask(event.keyCode)
                if (mask == 0) {
                    if (down && event.repeatCount == 0) Log.d(TAG, "Unmapped key ${KeyEvent.keyCodeToString(event.keyCode)}")
                    return false
                }
                keyButtons = if (down) keyButtons or mask else keyButtons and mask.inv()
            }
        }
        publish()
        return true
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE) return false
        lastDeviceId = event.deviceId
        lastInputTime = event.eventTime

        state.leftX = stick(event.getAxisValue(MotionEvent.AXIS_X))
        state.leftY = stick(event.getAxisValue(MotionEvent.AXIS_Y))
        state.rightX = stick(event.getAxisValue(MotionEvent.AXIS_Z))
        state.rightY = stick(event.getAxisValue(MotionEvent.AXIS_RZ))

        // Controllers report triggers on either the TRIGGER or the BRAKE/GAS axis pair.
        axisL2 = trigger(max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)))
        axisR2 = trigger(max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)))

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        hatButtons = (if (hatX < -0.5f) PadState.DPAD_LEFT else 0) or
            (if (hatX > 0.5f) PadState.DPAD_RIGHT else 0) or
            (if (hatY < -0.5f) PadState.DPAD_UP else 0) or
            (if (hatY > 0.5f) PadState.DPAD_DOWN else 0)

        publish()
        return true
    }

    private fun publish() {
        state.buttons = keyButtons or hatButtons
        state.l2 = if (axisL2 > 0) axisL2 else if (keyL2) 255 else 0
        state.r2 = if (axisR2 > 0) axisR2 else if (keyR2) 255 else 0
        onChanged(state)
    }

    private fun buttonMask(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> PadState.CROSS
        KeyEvent.KEYCODE_BUTTON_B -> PadState.CIRCLE
        KeyEvent.KEYCODE_BUTTON_X -> PadState.SQUARE
        KeyEvent.KEYCODE_BUTTON_Y -> PadState.TRIANGLE
        KeyEvent.KEYCODE_BUTTON_L1 -> PadState.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> PadState.R1
        KeyEvent.KEYCODE_BUTTON_THUMBL -> PadState.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> PadState.R3
        KeyEvent.KEYCODE_BUTTON_START -> PadState.OPTIONS
        KeyEvent.KEYCODE_BUTTON_SELECT -> PadState.SHARE
        KeyEvent.KEYCODE_BUTTON_MODE -> PadState.PS
        KeyEvent.KEYCODE_DPAD_LEFT -> PadState.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> PadState.DPAD_RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> PadState.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> PadState.DPAD_DOWN
        else -> 0
    }

    companion object {
        private const val TAG = "FarsofaInput"
        private const val DEADZONE = 0.04f

        fun stick(value: Float): Int =
            if (abs(value) < DEADZONE) 0 else (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()

        fun trigger(value: Float): Int = (value.coerceIn(0f, 1f) * 255).roundToInt()
    }
}

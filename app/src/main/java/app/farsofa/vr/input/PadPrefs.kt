// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.input

import android.content.Context

/** What the player wants from a DualSense beyond buttons and sticks. */
class PadPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("controller", Context.MODE_PRIVATE)

    /** Adaptive triggers. */
    var triggers: Boolean
        get() = prefs.getBoolean(KEY_TRIGGERS, true)
        set(value) = prefs.edit().putBoolean(KEY_TRIGGERS, value).apply()

    /** Light bar and player lights follow the console. */
    var lights: Boolean
        get() = prefs.getBoolean(KEY_LIGHTS, true)
        set(value) = prefs.edit().putBoolean(KEY_LIGHTS, value).apply()

    /** The controller's own gyroscope and touchpad reach the game. */
    var motion: Boolean
        get() = prefs.getBoolean(KEY_MOTION, true)
        set(value) = prefs.edit().putBoolean(KEY_MOTION, value).apply()

    /** The detailed haptics of PS5 games, instead of plain rumble. Still being tuned. */
    var fineHaptics: Boolean
        get() = prefs.getBoolean(KEY_FINE_HAPTICS, false)
        set(value) = prefs.edit().putBoolean(KEY_FINE_HAPTICS, value).apply()

    /**
     * Frames are made up for a 30 fps game so it moves like more. 0 is off; 1 to 3 say how eagerly:
     * only where and while the picture moves, in calm scenes too, or the whole picture all the time.
     * Not a controller setting, but it lives here with the other things a player may want changed.
     */
    var smoothLevel: Int
        get() = if (prefs.contains(KEY_SMOOTH_LEVEL)) prefs.getInt(KEY_SMOOTH_LEVEL, 0).coerceIn(0, 3)
        else if (prefs.getBoolean(KEY_SMOOTH, false)) 1 else 0
        set(value) {
            val level = value.coerceIn(0, 3)
            val edit = prefs.edit().putInt(KEY_SMOOTH_LEVEL, level)
            if (level > 0) edit.putInt(KEY_SMOOTH_LAST, level)
            edit.apply()
        }

    /** The level smooth motion comes back at when it is switched on again. */
    val smoothLastLevel: Int get() = prefs.getInt(KEY_SMOOTH_LAST, 2).coerceIn(1, 3)

    val smoothMotion: Boolean get() = smoothLevel > 0

    /** A small readout of how many pictures a second are real and how many are made up. */
    var showFrameCount: Boolean
        get() = prefs.getBoolean(KEY_FRAME_COUNT, false)
        set(value) = prefs.edit().putBoolean(KEY_FRAME_COUNT, value).apply()

    /** True when any of it needs to talk back to the controller over Bluetooth. */
    val needsLink: Boolean get() = triggers || lights || fineHaptics

    private companion object {
        const val KEY_TRIGGERS = "triggers"
        const val KEY_LIGHTS = "lights"
        const val KEY_MOTION = "motion"
        const val KEY_FINE_HAPTICS = "fineHaptics"
        const val KEY_SMOOTH = "smoothMotion"
        const val KEY_SMOOTH_LEVEL = "smoothLevel"
        const val KEY_SMOOTH_LAST = "smoothLastLevel"
        const val KEY_FRAME_COUNT = "frameCount"
    }
}

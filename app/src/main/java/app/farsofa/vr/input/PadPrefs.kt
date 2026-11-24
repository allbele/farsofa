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

    /** True when any of it needs to talk back to the controller over Bluetooth. */
    val needsLink: Boolean get() = triggers || lights || fineHaptics

    private companion object {
        const val KEY_TRIGGERS = "triggers"
        const val KEY_LIGHTS = "lights"
        const val KEY_MOTION = "motion"
        const val KEY_FINE_HAPTICS = "fineHaptics"
    }
}

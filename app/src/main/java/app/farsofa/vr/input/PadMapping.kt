// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.input

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where one control of the console's controller comes from on the player's gamepad. */
data class PadSource(
    /** An Android key code, or 0 when the control is on an axis. */
    val key: Int = 0,
    /** An Android axis, or -1 when the control is a key. */
    val axis: Int = -1,
    /** Which way along the axis counts: +1 or -1. */
    val sign: Int = 1,
    /** Where the axis sits when untouched. Some triggers rest at -1 and reach +1 fully pressed. */
    val rest: Float = 0f,
) {
    /** How far the control is pushed, 0 at rest and 1 (or -1, for a stick) at the end of its travel. */
    fun amount(keyDown: (Int) -> Boolean, axisValue: (Int) -> Float): Float {
        if (key != 0) return if (keyDown(key)) 1f else 0f
        val travel = 1f - rest * sign
        return if (travel <= 0.01f) 0f else (axisValue(axis) - rest) * sign / travel
    }
}

/**
 * The player's own assignment of a gamepad's keys and axes, for a gamepad Android does not describe
 * the standard way. Controls left out keep their standard meaning.
 */
class PadMapping(val sources: Map<Int, PadSource>) {
    val isEmpty: Boolean get() = sources.isEmpty()

    /**
     * Applies the assignment over [state], which already holds the standard reading. [keyDown] says
     * whether a key is held, [axisValue] gives an axis as Android reports it.
     */
    fun applyTo(state: PadState, keyDown: (Int) -> Boolean, axisValue: (Int) -> Float) {
        for ((target, source) in sources) {
            val amount = source.amount(keyDown, axisValue)
            when (target) {
                in 0 until BUTTONS -> {
                    val mask = 1 shl target
                    state.buttons = if (amount > PRESS_AT) state.buttons or mask else state.buttons and mask.inv()
                }
                L2 -> state.l2 = (amount.coerceIn(0f, 1f) * 255).roundToInt()
                R2 -> state.r2 = (amount.coerceIn(0f, 1f) * 255).roundToInt()
                LEFT_X -> state.leftX = stick(amount)
                LEFT_Y -> state.leftY = stick(amount)
                RIGHT_X -> state.rightX = stick(amount)
                RIGHT_Y -> state.rightY = stick(amount)
            }
        }
    }

    fun toJson(): String = JSONObject().apply {
        for ((target, source) in sources) {
            put(target.toString(), JSONObject().put("key", source.key).put("axis", source.axis).put("sign", source.sign).put("rest", source.rest.toDouble()))
        }
    }.toString()

    companion object {
        /** The sixteen buttons use their bit number in [PadState.buttons] as their target. */
        const val BUTTONS = 16
        const val L2 = 16
        const val R2 = 17

        /** Sticks are learned by pushing right and down, the positive directions the console expects. */
        const val LEFT_X = 18
        const val LEFT_Y = 19
        const val RIGHT_X = 20
        const val RIGHT_Y = 21

        private const val PRESS_AT = 0.5f

        /** The order the player is asked for the controls. */
        val LEARN_ORDER = intArrayOf(
            0, 1, 2, 3, // cross, circle, square, triangle
            6, 7, 4, 5, // d-pad up, down, left, right
            8, 9, L2, R2, 10, 11, // L1, R1, L2, R2, L3, R3
            12, 13, 15, 14, // options, create, PS, touchpad
            LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y,
        )

        fun fromJson(text: String?): PadMapping? = try {
            if (text == null) null else {
                val json = JSONObject(text)
                val sources = mutableMapOf<Int, PadSource>()
                for (name in json.keys()) {
                    val item = json.getJSONObject(name)
                    sources[name.toInt()] = PadSource(item.optInt("key", 0), item.optInt("axis", -1), item.optInt("sign", 1), item.optDouble("rest", 0.0).toFloat())
                }
                PadMapping(sources)
            }
        } catch (e: Exception) {
            null
        }

        private fun stick(amount: Float): Int =
            if (abs(amount) < 0.04f) 0 else (amount.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
    }
}

/** The assignments the player has made, one per model of gamepad. */
class PadMappingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("padmap", Context.MODE_PRIVATE)

    fun load(deviceKey: String): PadMapping? = PadMapping.fromJson(prefs.getString(deviceKey, null))?.takeUnless { it.isEmpty }

    fun save(deviceKey: String, mapping: PadMapping) = prefs.edit().putString(deviceKey, mapping.toJson()).apply()

    fun clear(deviceKey: String) = prefs.edit().remove(deviceKey).apply()

    companion object {
        /** Two gamepads of the same make and model share an assignment. */
        fun deviceKey(vendorId: Int, productId: Int): String = "%04x:%04x".format(vendorId, productId)
    }
}

/**
 * Walks the player through their gamepad one control at a time and records what they press. Fed with
 * every key and axis event while it runs; free of Android views so it can be tested.
 */
class PadLearner {
    private val learned = mutableMapOf<Int, PadSource>()
    private val rest = mutableMapOf<Int, Float>()
    private val held = mutableSetOf<Int>()
    private var step = 0
    private var lastDistance = 0f

    // After each answer the gamepad must come back to rest, or one long press would answer twice.
    private var waitingForRest = false

    /** The control being asked for, or -1 when all have been. */
    val target: Int get() = PadMapping.LEARN_ORDER.getOrElse(step) { -1 }
    val finished: Boolean get() = step >= PadMapping.LEARN_ORDER.size
    val progress: Int get() = step
    val result: PadMapping get() = PadMapping(learned.toMap())

    /** Leaves the current control with its standard meaning. */
    fun skip() {
        if (!finished) step++
        waitingForRest = true
    }

    fun onKey(keyCode: Int, down: Boolean): Boolean {
        if (down) held += keyCode else held -= keyCode
        // A gamepad with no axes never reports them, so letting go of every key is rest enough.
        if (!down && held.isEmpty() && lastDistance < 0.25f) waitingForRest = false
        if (finished || !down) return false
        if (waitingForRest) return false
        // A stick cannot be a key.
        if (target >= PadMapping.LEFT_X) return false
        return accept(PadSource(key = keyCode))
    }

    /** [values] holds every axis the gamepad has, by axis number. */
    fun onAxes(values: Map<Int, Float>): Boolean {
        if (rest.isEmpty()) rest.putAll(values)
        val moved = values.maxByOrNull { abs(it.value - (rest[it.key] ?: 0f)) }
        val distance = if (moved == null) 0f else abs(moved.value - (rest[moved.key] ?: 0f))
        lastDistance = distance
        if (waitingForRest) {
            if (distance < 0.25f && held.isEmpty()) waitingForRest = false
            return false
        }
        if (finished || moved == null || distance < 0.6f) return false
        val sign = if (moved.value - (rest[moved.key] ?: 0f) > 0f) 1 else -1
        return accept(PadSource(axis = moved.key, sign = sign, rest = (rest[moved.key] ?: 0f).let { if (abs(it) > 0.5f) it else 0f }))
    }

    private fun accept(source: PadSource): Boolean {
        learned[target] = source
        step++
        waitingForRest = true
        return true
    }
}

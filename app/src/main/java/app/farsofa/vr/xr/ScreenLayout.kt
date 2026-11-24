// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.xr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Where the virtual screen sits and what is behind it. Lengths are in metres. */
data class ScreenLayout(
    val width: Float = 3.2f,
    val distance: Float = 2.4f,
    val curved: Boolean = false,
    val passthrough: Boolean = true,
) {
    /** The same layout with every value pulled into the range the headset can show comfortably. */
    fun clamped(): ScreenLayout = copy(
        width = width.coerceIn(MIN_WIDTH, maxWidthAt(distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE))),
        distance = distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE),
    )

    /** Resizes as if a corner were dragged: [factor] above 1 grows the screen, below 1 shrinks it. */
    fun scaled(factor: Float): ScreenLayout = copy(width = width * factor).clamped()

    fun moved(deltaDistance: Float): ScreenLayout = copy(distance = distance + deltaDistance).clamped()

    fun toJson(): JSONObject = JSONObject()
        .put("width", width.toDouble())
        .put("distance", distance.toDouble())
        .put("curved", curved)
        .put("passthrough", passthrough)

    companion object {
        const val MIN_WIDTH = 0.8f
        const val MIN_DISTANCE = 0.8f
        const val MAX_DISTANCE = 8f

        /** A flat screen wider than about 2.4 times its distance runs past the edge of what the eyes can take in. */
        fun maxWidthAt(distance: Float): Float = distance * 2.4f

        fun fromJson(json: JSONObject): ScreenLayout = ScreenLayout(
            width = json.optDouble("width", 3.2).toFloat(),
            distance = json.optDouble("distance", 2.4).toFloat(),
            curved = json.optBoolean("curved", false),
            passthrough = json.optBoolean("passthrough", true),
        ).clamped()
    }
}

data class ScreenPreset(val name: String, val layout: ScreenLayout)

/** Pure list operations for presets, kept apart from storage so they can be tested on their own. */
object ScreenPresets {
    const val MAX_PRESETS = 6

    val defaults = listOf(
        ScreenPreset("Sofa", ScreenLayout(width = 3.2f, distance = 2.4f)),
        ScreenPreset("Cinema", ScreenLayout(width = 7f, distance = 4.5f, curved = true, passthrough = false)),
        ScreenPreset("Desk", ScreenLayout(width = 1.4f, distance = 1f)),
    )

    /** Saves [layout] under [name], replacing a preset with the same name; the oldest goes when the list is full. */
    fun save(presets: List<ScreenPreset>, name: String, layout: ScreenLayout): List<ScreenPreset> {
        val trimmed = name.trim().take(24)
        if (trimmed.isEmpty()) return presets
        val preset = ScreenPreset(trimmed, layout.clamped())
        val index = presets.indexOfFirst { it.name.equals(trimmed, ignoreCase = true) }
        if (index >= 0) return presets.toMutableList().also { it[index] = preset }
        return (presets + preset).takeLast(MAX_PRESETS)
    }

    fun remove(presets: List<ScreenPreset>, name: String): List<ScreenPreset> =
        presets.filterNot { it.name.equals(name.trim(), ignoreCase = true) }

    fun encode(presets: List<ScreenPreset>): String = JSONArray().apply {
        presets.forEach { put(it.layout.toJson().put("name", it.name)) }
    }.toString()

    fun decode(text: String?): List<ScreenPreset>? = try {
        if (text == null) null else JSONArray(text).let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                ScreenPreset(item.getString("name"), ScreenLayout.fromJson(item))
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** Remembers the last screen layout and the player's presets between sessions. */
class ScreenLayoutStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("screen", Context.MODE_PRIVATE)

    var current: ScreenLayout
        get() = try {
            prefs.getString(KEY_CURRENT, null)?.let { ScreenLayout.fromJson(JSONObject(it)) } ?: ScreenLayout()
        } catch (e: Exception) {
            ScreenLayout()
        }
        set(value) = prefs.edit().putString(KEY_CURRENT, value.clamped().toJson().toString()).apply()

    var presets: List<ScreenPreset>
        get() = ScreenPresets.decode(prefs.getString(KEY_PRESETS, null)) ?: ScreenPresets.defaults
        set(value) = prefs.edit().putString(KEY_PRESETS, ScreenPresets.encode(value)).apply()

    /** Whether the screen keeps its place around the player as they walk, instead of staying put in the room. */
    var follow: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW, false)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOW, value).apply()

    private companion object {
        const val KEY_FOLLOW = "follow"
        const val KEY_CURRENT = "current"
        const val KEY_PRESETS = "presets"
    }
}

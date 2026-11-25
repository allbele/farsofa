// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.xr

import app.farsofa.vr.input.PadState
import kotlin.math.abs

/** What a control on the bar does. */
sealed interface BarAction {
    data class ApplyPreset(val index: Int) : BarAction
    data object SavePreset : BarAction
    data class RemovePreset(val index: Int) : BarAction

    /** Puts the original presets back. Asks to be pressed twice. */
    data object ResetPresets : BarAction
    data object Smaller : BarAction
    data object Larger : BarAction
    data object Closer : BarAction
    data object Farther : BarAction
    data object ToggleCurve : BarAction
    data object ToggleBackground : BarAction
    data object ToggleFollow : BarAction

    /** A digit of the console's login passcode. */
    data class Digit(val value: Int) : BarAction
    data object Backspace : BarAction
    data object Leave : BarAction
    data object Close : BarAction

    /** Switches the bar to the controller and picture options, and back. */
    data object OpenOptions : BarAction
    data object CloseOptions : BarAction

    /** Flips one of the options, by its place in [OptionsPad.OPTIONS]. */
    data class ToggleOption(val index: Int) : BarAction

    /** Taps a console button the Touch controllers have no key for. */
    data class Press(val buttons: Int) : BarAction

    /** Held controls keep stepping; everything else fires once per press. */
    val repeats: Boolean get() = this is Smaller || this is Larger || this is Closer || this is Farther
}

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

data class BarItem(val action: BarAction, val row: Int, val box: Box)

/** Read-outs between the minus and plus buttons; they show a value and cannot be pressed. */
data class BarReadout(val kind: Kind, val box: Box) {
    enum class Kind { SIZE, DISTANCE }
}

/** A set of controls laid out on the bar's surface, reachable with a pointer or with gamepad focus. */
abstract class Panel {
    abstract val items: List<BarItem>

    fun itemAt(x: Float, y: Float): BarItem? = items.firstOrNull { it.box.contains(x, y) }

    fun find(action: BarAction): BarItem? = items.firstOrNull { it.action == action }

    /**
     * The control a gamepad lands on when moving from [from] by one step. [dx] moves along the row and
     * [dy] to the control closest above (-1) or below (+1); a move with nowhere to go stays put.
     */
    fun neighbour(from: BarItem, dx: Int, dy: Int): BarItem {
        if (dx != 0) {
            val row = items.filter { it.row == from.row }
            return row.getOrNull(row.indexOf(from) + dx) ?: from
        }
        val row = items.filter { it.row == from.row + dy }
        return row.minByOrNull { abs(it.box.centerX - from.box.centerX) } ?: from
    }
}

/**
 * Layout of the control bar in the pixel space of its surface. Three rows: the player's presets, the
 * screen adjustments, and the console buttons that the Touch controllers lack.
 */
class ControlBar(presets: List<ScreenPreset>, layout: ScreenLayout) : Panel() {
    override val items: List<BarItem>
    val readouts: List<BarReadout>

    /** Index of the preset the current layout matches, or -1 when it has been changed by hand. */
    val activePreset: Int = presets.indexOfFirst { same(it.layout, layout) }

    init {
        val built = mutableListOf<BarItem>()
        val shown = mutableListOf<BarReadout>()

        // Row 0: one chip per preset, then a single slot that saves the current screen or removes the active preset.
        val lastSlot: BarAction = if (activePreset >= 0) BarAction.RemovePreset(activePreset) else BarAction.SavePreset
        val chips = presets.indices.map<Int, BarAction> { BarAction.ApplyPreset(it) } + lastSlot + BarAction.ResetPresets
        split(List(chips.size) { 1f }).forEachIndexed { index, (left, right) ->
            built += BarItem(chips[index], 0, Box(left, ROW_TOPS[0], right, ROW_TOPS[0] + ROW_HEIGHTS[0]))
        }

        // Row 1: size and distance steppers, shape, background, leave.
        val top = ROW_TOPS[1]
        val bottom = top + ROW_HEIGHTS[1]
        val groups = split(listOf(4.2f, 4.2f, 2.5f, 2.5f, 2.9f, 2.4f))
        fun stepper(group: Pair<Float, Float>, down: BarAction, up: BarAction, kind: BarReadout.Kind) {
            val button = 96f
            built += BarItem(down, 1, Box(group.first, top, group.first + button, bottom))
            shown += BarReadout(kind, Box(group.first + button, top, group.second - button, bottom))
            built += BarItem(up, 1, Box(group.second - button, top, group.second, bottom))
        }
        stepper(groups[0], BarAction.Smaller, BarAction.Larger, BarReadout.Kind.SIZE)
        stepper(groups[1], BarAction.Closer, BarAction.Farther, BarReadout.Kind.DISTANCE)
        built += BarItem(BarAction.ToggleCurve, 1, Box(groups[2].first, top, groups[2].second, bottom))
        built += BarItem(BarAction.ToggleBackground, 1, Box(groups[3].first, top, groups[3].second, bottom))
        built += BarItem(BarAction.ToggleFollow, 1, Box(groups[4].first, top, groups[4].second, bottom))
        built += BarItem(BarAction.Leave, 1, Box(groups[5].first, top, groups[5].second, bottom))

        // Row 2: console buttons, then close.
        val console = listOf(
            PadState.DPAD_LEFT, PadState.DPAD_UP, PadState.DPAD_DOWN, PadState.DPAD_RIGHT,
            PadState.SHARE, PadState.TOUCHPAD, PadState.PS, PadState.OPTIONS,
        ).map<Int, BarAction> { BarAction.Press(it) } + BarAction.OpenOptions + BarAction.Close
        split(listOf(1f, 1f, 1f, 1f, 1.7f, 1.7f, 1.7f, 1.7f, 1.7f, 1.7f)).forEachIndexed { index, (left, right) ->
            built += BarItem(console[index], 2, Box(left, ROW_TOPS[2], right, ROW_TOPS[2] + ROW_HEIGHTS[2]))
        }

        items = built
        readouts = shown
    }

    companion object {
        const val PADDING = 24f
        const val GAP = 16f
        val ROW_HEIGHTS = floatArrayOf(104f, 150f, 104f)
        val ROW_TOPS = floatArrayOf(
            PADDING,
            PADDING + ROW_HEIGHTS[0] + GAP + 10f,
            PADDING + ROW_HEIGHTS[0] + GAP + 10f + ROW_HEIGHTS[1] + GAP + 10f,
        )

        const val SIZE_STEP = 1.05f
        const val DISTANCE_STEP = 0.1f

        fun same(a: ScreenLayout, b: ScreenLayout): Boolean =
            abs(a.width - b.width) < 0.01f && abs(a.distance - b.distance) < 0.01f
                && a.curved == b.curved && a.passthrough == b.passthrough

        /** Cuts the bar's inner width into spans proportional to [weights], separated by [GAP]. */
        private fun split(weights: List<Float>): List<Pair<Float, Float>> {
            val inner = Scene.BAR_PX_WIDTH - 2 * PADDING - GAP * (weights.size - 1)
            val total = weights.sum()
            var left = PADDING
            return weights.map { weight ->
                val width = inner * weight / total
                Pair(left, left + width).also { left += width + GAP }
            }
        }

        /** The first free name of the form "[base] N" among [presets]. */
        fun nextPresetName(presets: List<ScreenPreset>, base: String): String {
            var number = 1
            while (presets.any { it.name.equals("$base $number", ignoreCase = true)}) number++
            return "$base $number"
        }
    }
}

/**
 * The options a player may want to change without leaving the game, drawn where the control bar goes:
 * a line of explanation, one switch per option, and the way back.
 */
class OptionsPad : Panel() {
    override val items: List<BarItem>

    val prompt = Box(ControlBar.PADDING, ControlBar.ROW_TOPS[0], Scene.BAR_PX_WIDTH - ControlBar.PADDING, ControlBar.ROW_TOPS[0] + ControlBar.ROW_HEIGHTS[0])

    init {
        val built = mutableListOf<BarItem>()
        val inner = Scene.BAR_PX_WIDTH - 2 * ControlBar.PADDING
        val width = (inner - ControlBar.GAP * (OPTIONS - 1)) / OPTIONS
        val top = ControlBar.ROW_TOPS[1]
        for (index in 0 until OPTIONS) {
            val left = ControlBar.PADDING + index * (width + ControlBar.GAP)
            built += BarItem(BarAction.ToggleOption(index), 1, Box(left, top, left + width, top + ControlBar.ROW_HEIGHTS[1]))
        }
        val bottomTop = ControlBar.ROW_TOPS[2]
        built += BarItem(
            BarAction.CloseOptions, 2,
            Box(ControlBar.PADDING, bottomTop, Scene.BAR_PX_WIDTH - ControlBar.PADDING, bottomTop + ControlBar.ROW_HEIGHTS[2]),
        )
        items = built
    }

    companion object {
        /** Triggers, lights, motion, detailed haptics, smooth motion, frame counter: in that order. */
        const val OPTIONS = 6
        const val SMOOTH_MOTION = 4
        const val FRAME_COUNT = 5
    }
}

/**
 * The pad for the console's four-digit login passcode, drawn where the control bar goes: a line that
 * says what is being asked, the ten digits, and delete or disconnect.
 */
class PinPad : Panel() {
    override val items: List<BarItem>

    /** Where the prompt and the entered digits are shown. */
    val prompt = Box(ControlBar.PADDING, ControlBar.ROW_TOPS[0], Scene.BAR_PX_WIDTH - ControlBar.PADDING, ControlBar.ROW_TOPS[0] + ControlBar.ROW_HEIGHTS[0])

    init {
        val built = mutableListOf<BarItem>()
        val inner = Scene.BAR_PX_WIDTH - 2 * ControlBar.PADDING
        val digitWidth = (inner - ControlBar.GAP * 9) / 10f
        val top = ControlBar.ROW_TOPS[1]
        for (index in 0 until 10) {
            val left = ControlBar.PADDING + index * (digitWidth + ControlBar.GAP)
            built += BarItem(BarAction.Digit((index + 1) % 10), 1, Box(left, top, left + digitWidth, top + ControlBar.ROW_HEIGHTS[1]))
        }
        val half = (inner - ControlBar.GAP) / 2f
        val bottomTop = ControlBar.ROW_TOPS[2]
        val bottom = bottomTop + ControlBar.ROW_HEIGHTS[2]
        built += BarItem(BarAction.Backspace, 2, Box(ControlBar.PADDING, bottomTop, ControlBar.PADDING + half, bottom))
        built += BarItem(BarAction.Leave, 2, Box(ControlBar.PADDING + half + ControlBar.GAP, bottomTop, Scene.BAR_PX_WIDTH - ControlBar.PADDING, bottom))
        items = built
    }

    companion object {
        const val LENGTH = 4
    }
}

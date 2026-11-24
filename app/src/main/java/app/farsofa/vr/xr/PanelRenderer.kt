// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.xr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.Surface
import app.farsofa.vr.R
import app.farsofa.vr.input.PadState
import java.util.Locale

/**
 * Draws the control bar and the resize handles onto the surfaces the compositor shows as layers.
 * Plain Canvas drawing: the app has no renderer of its own, and these panels change only on input.
 */
class PanelRenderer(private val context: Context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val path = Path()
    private val softwareOnly = HashSet<Surface>()

    fun drawBar(
        surface: Surface,
        bar: ControlBar,
        presets: List<ScreenPreset>,
        layout: ScreenLayout,
        follow: Boolean,
        confirmingReset: Boolean,
        highlighted: BarItem?,
        pressed: Boolean,
        cursor: Pair<Float, Float>?,
    ) = draw(surface, Scene.BAR_PX_WIDTH, Scene.BAR_PX_HEIGHT) { canvas ->
        panel(canvas, 0f, 0f, Scene.BAR_PX_WIDTH.toFloat(), Scene.BAR_PX_HEIGHT.toFloat())

        for (item in bar.items) {
            val action = item.action
            val isHighlighted = item == highlighted
            val isActive = action is BarAction.ApplyPreset && action.index == bar.activePreset
            fill.color = when {
                isHighlighted && pressed -> ACCENT
                action == BarAction.ResetPresets && confirmingReset -> DANGER_DIM
                isActive -> ACCENT_DIM
                isHighlighted -> CONTROL_HOVER
                else -> CONTROL
            }
            rect.set(item.box.left, item.box.top, item.box.right, item.box.bottom)
            canvas.drawRoundRect(rect, CORNER, CORNER, fill)
            if (isHighlighted) {
                stroke.color = ACCENT
                stroke.strokeWidth = 4f
                canvas.drawRoundRect(rect, CORNER, CORNER, stroke)
            }

            val color = if (action == BarAction.Leave) DANGER else TEXT
            when (action) {
                BarAction.Smaller, BarAction.Closer -> label(canvas, item.box, "−", 60f, color)
                BarAction.Larger, BarAction.Farther -> label(canvas, item.box, "+", 60f, color)
                BarAction.ToggleCurve -> captioned(
                    canvas, item.box, string(R.string.bar_shape),
                    string(if (layout.curved) R.string.bar_shape_curved else R.string.bar_shape_flat),
                )
                BarAction.ToggleBackground -> captioned(
                    canvas, item.box, string(R.string.bar_background),
                    string(if (layout.passthrough) R.string.bar_background_room else R.string.bar_background_dark),
                )
                BarAction.ToggleFollow -> captioned(
                    canvas, item.box, string(R.string.bar_position),
                    string(if (follow) R.string.bar_position_follow else R.string.bar_position_fixed),
                )
                BarAction.ResetPresets -> label(
                    canvas, item.box,
                    string(if (confirmingReset) R.string.bar_reset_confirm else R.string.bar_reset_presets),
                    if (confirmingReset) 34f else 28f, color,
                )
                else -> label(canvas, item.box, title(action, presets), 34f, color)
            }
        }

        for (readout in bar.readouts) {
            when (readout.kind) {
                BarReadout.Kind.SIZE -> captioned(canvas, readout.box, string(R.string.bar_size), metres(layout.width))
                BarReadout.Kind.DISTANCE ->
                    captioned(canvas, readout.box, string(R.string.bar_distance), metres(layout.distance))
            }
        }

        if (cursor != null) cursor(canvas, cursor.first, cursor.second)
    }

    /** The passcode pad: what is being asked, how many digits are in, and the keys. */
    fun drawPin(surface: Surface, pad: PinPad, entered: Int, incorrect: Boolean, highlighted: BarItem?, pressed: Boolean, cursor: Pair<Float, Float>?) =
        draw(surface, Scene.BAR_PX_WIDTH, Scene.BAR_PX_HEIGHT) { canvas ->
            panel(canvas, 0f, 0f, Scene.BAR_PX_WIDTH.toFloat(), Scene.BAR_PX_HEIGHT.toFloat())

            val message = string(if (incorrect) R.string.stream_login_pin_incorrect else R.string.stream_login_pin_title)
            text.textSize = 36f
            text.color = if (incorrect) DANGER else TEXT
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(message, pad.prompt.left + 12f, pad.prompt.centerY + 13f, text)
            text.textAlign = Paint.Align.CENTER
            // One ring per digit, filled as they are typed; the digits themselves are never shown.
            for (index in 0 until PinPad.LENGTH) {
                val x = pad.prompt.right - 40f - (PinPad.LENGTH - 1 - index) * 64f
                if (index < entered) {
                    fill.color = TEXT
                    canvas.drawCircle(x, pad.prompt.centerY, 17f, fill)
                } else {
                    stroke.color = MUTED
                    stroke.strokeWidth = 4f
                    canvas.drawCircle(x, pad.prompt.centerY, 16f, stroke)
                }
            }

            for (item in pad.items) {
                val isHighlighted = item == highlighted
                fill.color = if (isHighlighted && pressed) ACCENT else if (isHighlighted) CONTROL_HOVER else CONTROL
                rect.set(item.box.left, item.box.top, item.box.right, item.box.bottom)
                canvas.drawRoundRect(rect, CORNER, CORNER, fill)
                if (isHighlighted) {
                    stroke.color = ACCENT
                    stroke.strokeWidth = 4f
                    canvas.drawRoundRect(rect, CORNER, CORNER, stroke)
                }
                when (val action = item.action) {
                    is BarAction.Digit -> label(canvas, item.box, action.value.toString(), 56f, TEXT)
                    BarAction.Backspace -> label(canvas, item.box, string(R.string.bar_backspace), 34f, TEXT)
                    else -> label(canvas, item.box, string(R.string.bar_leave), 34f, DANGER)
                }
            }
            if (cursor != null) cursor(canvas, cursor.first, cursor.second)
        }

    /** A single line of help shown where the bar would be. */
    fun drawHint(surface: Surface, message: String) = draw(surface, Scene.BAR_PX_WIDTH, Scene.BAR_PX_HEIGHT) { canvas ->
        text.textSize = 40f
        val width = (text.measureText(message) + 120f).coerceAtMost(Scene.BAR_PX_WIDTH.toFloat())
        val left = (Scene.BAR_PX_WIDTH - width) / 2f
        val top = Scene.BAR_PX_HEIGHT / 2f - 56f
        panel(canvas, left, top, left + width, top + 112f)
        label(canvas, Box(left, top, left + width, top + 112f), message, 40f, TEXT)
    }

    /**
     * The frame around the screen with a handle at each corner. Only the pointer dot is ever drawn
     * over the picture itself; the handles sit in the margin outside it.
     */
    fun drawOverlay(surface: Surface, layout: ScreenLayout, zone: ScreenZone?, grabbing: Boolean, hit: ScreenHit?) =
        draw(surface, OVERLAY_PX_WIDTH, OVERLAY_PX_HEIGHT) { canvas ->
            val left = OVERLAY_PX_MARGIN
            val top = OVERLAY_PX_MARGIN
            val right = OVERLAY_PX_WIDTH - OVERLAY_PX_MARGIN
            val bottom = OVERLAY_PX_HEIGHT - OVERLAY_PX_MARGIN
            val moving = grabbing && zone == ScreenZone.GRIP

            stroke.color = if (moving) ACCENT else FRAME
            stroke.strokeWidth = if (moving) 5f else 3f
            canvas.drawRect(left - 4f, top - 4f, right + 4f, bottom + 4f, stroke)

            for (cornerX in intArrayOf(-1, 1)) {
                for (cornerY in intArrayOf(-1, 1)) {
                    val pointed = zone == ScreenZone.CORNER && hit != null
                        && (hit.x >= 0f) == (cornerX > 0) && (hit.y >= 0f) == (cornerY < 0)
                    val x = if (cornerX < 0) left - HANDLE_OFFSET else right + HANDLE_OFFSET
                    val y = if (cornerY < 0) top - HANDLE_OFFSET else bottom + HANDLE_OFFSET
                    path.rewind()
                    path.moveTo(x, y - cornerY * HANDLE_ARM)
                    path.lineTo(x, y)
                    path.lineTo(x - cornerX * HANDLE_ARM, y)
                    stroke.color = if (pointed) ACCENT else TEXT
                    stroke.strokeWidth = if (pointed && grabbing) 16f else if (pointed) 13f else 9f
                    stroke.strokeJoin = Paint.Join.ROUND
                    canvas.drawPath(path, stroke)
                }
            }

            // The grip: a short rounded bar below the picture, like the one under a system window.
            val gripHalf = (right - left) * Scene.GRIP_WIDTH
            val gripY = bottom + OVERLAY_PX_MARGIN * 0.55f
            stroke.color = if (zone == ScreenZone.GRIP) ACCENT else TEXT
            stroke.strokeWidth = if (moving) 20f else if (zone == ScreenZone.GRIP) 17f else 12f
            canvas.drawLine(OVERLAY_PX_WIDTH / 2f - gripHalf, gripY, OVERLAY_PX_WIDTH / 2f + gripHalf, gripY, stroke)

            if (hit != null && zone != null) {
                val scale = (right - left) / layout.width
                cursor(canvas, OVERLAY_PX_WIDTH / 2f + hit.x * scale, OVERLAY_PX_HEIGHT / 2f - hit.y * scale)
            }
        }

    private fun title(action: BarAction, presets: List<ScreenPreset>): String = when (action) {
        is BarAction.ApplyPreset -> presets.getOrNull(action.index)?.name.orEmpty()
        is BarAction.RemovePreset ->
            context.getString(R.string.bar_remove_preset, presets.getOrNull(action.index)?.name.orEmpty())
        BarAction.SavePreset -> string(R.string.bar_save_preset)
        BarAction.Leave -> string(R.string.bar_leave)
        BarAction.Close -> string(R.string.bar_close)
        is BarAction.Press -> when (action.buttons) {
            PadState.DPAD_LEFT -> "◀"
            PadState.DPAD_UP -> "▲"
            PadState.DPAD_DOWN -> "▼"
            PadState.DPAD_RIGHT -> "▶"
            PadState.SHARE -> "Create"
            PadState.TOUCHPAD -> "Touchpad"
            PadState.PS -> "Home"
            else -> "Options"
        }
        else -> ""
    }

    private fun string(id: Int): String = context.getString(id)

    private fun metres(value: Float): String = String.format(Locale.getDefault(), "%.1f m", value)

    private fun panel(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        fill.color = PANEL
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, 40f, 40f, fill)
    }

    private fun label(canvas: Canvas, box: Box, value: String, size: Float, color: Int) {
        text.textSize = size
        text.color = color
        val fitted = TextUtils.ellipsize(value, text, box.right - box.left - 28f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(fitted, box.centerX, box.centerY - (text.ascent() + text.descent()) / 2f, text)
    }

    /** A small caption above a larger value, the way a setting and its current choice read together. */
    private fun captioned(canvas: Canvas, box: Box, caption: String, value: String) {
        text.textSize = 25f
        text.color = MUTED
        canvas.drawText(caption, box.centerX, box.top + 48f, text)
        text.textSize = 40f
        text.color = TEXT
        val fitted = TextUtils.ellipsize(value, text, box.right - box.left - 20f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(fitted, box.centerX, box.top + 108f, text)
    }

    private fun cursor(canvas: Canvas, x: Float, y: Float) {
        fill.color = Color.WHITE
        canvas.drawCircle(x, y, 9f, fill)
        stroke.color = 0x99000000.toInt()
        stroke.strokeWidth = 3f
        canvas.drawCircle(x, y, 10.5f, stroke)
    }

    private inline fun draw(surface: Surface, width: Int, height: Int, block: (Canvas) -> Unit) {
        if (!surface.isValid) return
        val canvas = lock(surface) ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.scale(canvas.width / width.toFloat(), canvas.height / height.toFloat())
            block(canvas)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /** Hardware canvas when the surface allows it; a surface that refuses once stays on software. */
    private fun lock(surface: Surface): Canvas? {
        if (surface !in softwareOnly) {
            try {
                return surface.lockHardwareCanvas()
            } catch (e: Exception) {
                softwareOnly += surface
            }
        }
        return try {
            surface.lockCanvas(null)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** Pixel size of the handle overlay: a 1200 px wide picture area with a margin all around. */
        const val OVERLAY_PX_MARGIN = 72f
        const val OVERLAY_PX_WIDTH = 1344
        const val OVERLAY_PX_HEIGHT = 819

        private const val CORNER = 26f
        private const val HANDLE_OFFSET = 18f
        private const val HANDLE_ARM = 64f

        private const val PANEL = 0xF212151C.toInt()
        private const val CONTROL = 0xFF1F2430.toInt()
        private const val CONTROL_HOVER = 0xFF2B3345.toInt()
        private const val ACCENT = 0xFF5B8CFF.toInt()
        private const val ACCENT_DIM = 0xFF2F4A8C.toInt()
        private const val TEXT = 0xFFF2F4F8.toInt()
        private const val MUTED = 0xFF9AA3B5.toInt()
        private const val DANGER = 0xFFFF8A80.toInt()
        private const val DANGER_DIM = 0xFF7A2E2A.toInt()
        private const val FRAME = 0x66FFFFFF
    }
}

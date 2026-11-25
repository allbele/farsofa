// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.xr

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Geometry of the immersive scene, free of any Android or OpenXR type so it can be tested on its own.
 *
 * Everything is expressed in the "rig" frame: the viewer at the origin looking down -Z, +Y up, lengths
 * in metres. The whole rig (screen and control bar) turns around the vertical axis by a yaw angle when
 * the player drags the screen sideways; [Ray.turned] brings a controller ray into the rig frame.
 */
data class Ray(val ox: Float, val oy: Float, val oz: Float, val dx: Float, val dy: Float, val dz: Float) {
    /** This ray seen from a rig turned [yaw] radians to the left and then tipped [pitch] radians upwards. */
    fun turned(yaw: Float, pitch: Float = 0f): Ray {
        val c = cos(yaw)
        val s = sin(yaw)
        val x = ox * c - oz * s
        val z = ox * s + oz * c
        val vx = dx * c - dz * s
        val vz = dx * s + dz * c
        val pc = cos(pitch)
        val ps = sin(pitch)
        return Ray(x, oy * pc + z * ps, -oy * ps + z * pc, vx, dy * pc + vz * ps, -dy * ps + vz * pc)
    }

    /** Horizontal heading in radians, positive to the left of straight ahead. */
    val heading: Float get() = atan2(-dx, -dz)

    /** How far the ray points above the horizon, in radians. */
    val elevation: Float get() = atan2(dy, hypot(dx, dz))
}

/** A point on the screen surface, in metres from the screen's centre (x along the surface, y up). */
data class ScreenHit(val x: Float, val y: Float) {
    val radius: Float get() = hypot(x, y)
}

/** The parts of the screen's frame a pointer can take hold of: a corner to resize, the grip below to move. */
enum class ScreenZone { CORNER, GRIP }

/** Where the control bar sits and how far the screen moves up to make room for it. */
data class BarPlacement(
    /** Straight-line distance from the viewer to the centre of the bar. */
    val distance: Float,
    /** Elevation of the bar's centre in radians; negative is below eye level. The bar faces the viewer. */
    val elevation: Float,
    val width: Float,
    val height: Float,
    /** How far the screen's centre rises above eye level while the bar is shown. */
    val screenLift: Float,
) {
    val halfAngle: Float get() = atan(height / 2f / distance)
}

object Scene {
    const val ASPECT = 16f / 9f

    /** Pixel size of the control bar's surface; its physical shape follows this ratio. */
    const val BAR_PX_WIDTH = 1840
    const val BAR_PX_HEIGHT = 458

    /** The overlay that carries the resize handles extends past the screen by this share of its width. */
    const val OVERLAY_MARGIN = 0.06f

    /** The screen can be moved this far above or below eye level, in radians. */
    const val MAX_PITCH = 1.1f

    private const val DEG = (Math.PI / 180.0).toFloat()
    private const val BAR_REST_ELEVATION = -24f * DEG
    private const val BAR_LOWEST_ELEVATION = -50f * DEG
    private const val BAR_GAP = 4.5f * DEG
    private const val GENTLE_LIFT = 7f * DEG

    fun screenHeight(layout: ScreenLayout): Float = layout.width / ASPECT

    /**
     * Places the control bar under the screen so the two never overlap from where the viewer sits.
     * With [allowLift] the screen first rises a little, then the bar drops, and only a screen too big
     * for both rises further. Without it only the bar moves (used for the short hint after connecting).
     */
    fun placeBar(layout: ScreenLayout, allowLift: Boolean = true): BarPlacement {
        val distance = (layout.distance * 0.8f).coerceIn(0.7f, 1.5f)
        val width = distance * 0.95f
        val height = width * BAR_PX_HEIGHT / BAR_PX_WIDTH
        val half = atan(height / 2f / distance)

        val screenHalfHeight = screenHeight(layout) / 2f
        var screenBottom = atan2(-screenHalfHeight, layout.distance)
        var elevation = BAR_REST_ELEVATION
        // The gap leaves room for the grip that hangs below the screen.
        val gap = atan(layout.width * OVERLAY_MARGIN / layout.distance) + BAR_GAP
        var missing = elevation + half + gap - screenBottom
        if (missing > 0f) {
            if (allowLift) {
                val lift = min(missing, GENTLE_LIFT)
                screenBottom += lift
                missing -= lift
            }
            val drop = min(missing, elevation - BAR_LOWEST_ELEVATION)
            elevation -= drop
            missing -= drop
            if (allowLift) screenBottom += missing
        }
        val lift = if (allowLift) layout.distance * tan(screenBottom) + screenHalfHeight else 0f
        return BarPlacement(distance, elevation, width, height, max(0f, lift))
    }

    /**
     * Where [ray] (already in the rig frame) meets the screen surface, extended without limit so a drag
     * can run past the edges. [screenY] is the height of the screen's centre.
     */
    fun hitScreen(layout: ScreenLayout, screenY: Float, ray: Ray): ScreenHit? {
        if (!layout.curved) {
            if (ray.dz > -1e-4f) return null
            val t = (-layout.distance - ray.oz) / ray.dz
            if (t <= 0f) return null
            return ScreenHit(ray.ox + t * ray.dx, ray.oy + t * ray.dy - screenY)
        }
        // The curved screen is part of a vertical cylinder around the viewer; the ray starts inside it.
        val a = ray.dx * ray.dx + ray.dz * ray.dz
        if (a < 1e-8f) return null
        val b = 2f * (ray.ox * ray.dx + ray.oz * ray.dz)
        val c = ray.ox * ray.ox + ray.oz * ray.oz - layout.distance * layout.distance
        val discriminant = b * b - 4f * a * c
        if (discriminant < 0f) return null
        val t = (-b + sqrt(discriminant)) / (2f * a)
        if (t <= 0f) return null
        val x = ray.ox + t * ray.dx
        val z = ray.oz + t * ray.dz
        val angle = atan2(x, -z)
        if (angle < -2.6f || angle > 2.6f) return null
        return ScreenHit(angle * layout.distance, ray.oy + t * ray.dy - screenY)
    }

    /** Which handle [hit] is on, or null when it is on the picture or away from the screen. */
    fun zoneAt(layout: ScreenLayout, hit: ScreenHit): ScreenZone? {
        val halfWidth = layout.width / 2f
        val halfHeight = screenHeight(layout) / 2f
        val margin = layout.width * OVERLAY_MARGIN
        val x = kotlin.math.abs(hit.x)
        val y = kotlin.math.abs(hit.y)
        if (x > halfWidth + margin || y > halfHeight + margin * 1.5f) return null
        val reach = max(layout.width * 0.08f, 0.1f)
        if (hypot(x - halfWidth, y - halfHeight) <= reach) return ScreenZone.CORNER
        // The grip is the strip just below the picture, around its middle.
        val below = -hit.y - halfHeight
        if (below > 0f && x <= max(layout.width * GRIP_WIDTH, 0.3f)) return ScreenZone.GRIP
        return null
    }

    /**
     * Whether [hit] is close enough to the screen's edge for its handles to show. The middle of the
     * picture does not count, so a controller that merely points at the game leaves the frame hidden.
     */
    fun nearFrame(layout: ScreenLayout, hit: ScreenHit): Boolean {
        val halfWidth = layout.width / 2f
        val halfHeight = screenHeight(layout) / 2f
        val margin = layout.width * OVERLAY_MARGIN
        val x = kotlin.math.abs(hit.x)
        val y = kotlin.math.abs(hit.y)
        if (x > halfWidth + margin || y > halfHeight + margin * 1.5f) return false
        return x > halfWidth * 0.75f || y > halfHeight * 0.65f
    }

    /** Half the width of the grip below the screen, as a share of the screen's width. */
    const val GRIP_WIDTH = 0.14f

    /** Where [ray] (in the rig frame) meets the control bar, as pixel coordinates on its surface. */
    fun hitBar(bar: BarPlacement, ray: Ray): Pair<Float, Float>? {
        val c = cos(bar.elevation)
        val s = sin(bar.elevation)
        // Centre of the bar, its up vector and its normal (pointing back at the viewer).
        val py = bar.distance * s
        val pz = -bar.distance * c
        val denominator = ray.dy * -s + ray.dz * c
        if (denominator > -1e-4f) return null
        val t = ((py - ray.oy) * -s + (pz - ray.oz) * c) / denominator
        if (t <= 0f) return null
        val hx = ray.ox + t * ray.dx
        val hy = ray.oy + t * ray.dy - py
        val hz = ray.oz + t * ray.dz - pz
        val u = hx / bar.width + 0.5f
        val v = 0.5f - (hy * c + hz * s) / bar.height
        if (u < 0f || u > 1f || v < 0f || v > 1f) return null
        return Pair(u * BAR_PX_WIDTH, v * BAR_PX_HEIGHT)
    }
}

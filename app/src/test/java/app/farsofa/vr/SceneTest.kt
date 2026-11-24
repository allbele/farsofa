// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr

import app.farsofa.vr.xr.BarAction
import app.farsofa.vr.xr.ControlBar
import app.farsofa.vr.xr.Ray
import app.farsofa.vr.xr.Scene
import app.farsofa.vr.xr.ScreenLayout
import app.farsofa.vr.xr.ScreenPreset
import app.farsofa.vr.xr.ScreenPresets
import app.farsofa.vr.xr.ScreenZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTest {
    private fun rayTo(x: Float, y: Float, z: Float) = Ray(0f, 0f, 0f, x, y, z)

    @Test
    fun theBarNeverReachesTheScreenWhateverItsSizeOrDistance() {
        for (curved in listOf(false, true)) {
            var distance = ScreenLayout.MIN_DISTANCE
            while (distance <= ScreenLayout.MAX_DISTANCE) {
                var share = 0.05f
                while (share <= 1f) {
                    val layout = ScreenLayout(ScreenLayout.maxWidthAt(distance) * share, distance, curved).clamped()
                    val bar = Scene.placeBar(layout)
                    val screenBottom = atan2(bar.screenLift - Scene.screenHeight(layout) / 2f, layout.distance)
                    val barTop = bar.elevation + bar.halfAngle
                    assertTrue("bar overlaps screen for $layout", barTop < screenBottom - 0.03f)
                    assertTrue("bar too low for $layout", bar.elevation >= -0.88f)
                    share += 0.05f
                }
                distance += 0.3f
            }
        }
    }

    @Test
    fun aSmallScreenStaysWhereItIsWhenTheBarOpens() {
        val bar = Scene.placeBar(ScreenLayout(width = 1.2f, distance = 2.4f))
        assertEquals(0f, bar.screenLift, 0.0001f)
    }

    @Test
    fun theHintNeverMovesTheScreen() {
        assertEquals(0f, Scene.placeBar(ScreenLayout(width = 7f, distance = 4.5f), allowLift = false).screenLift, 0f)
    }

    @Test
    fun aRayAtTheCornerOfAFlatScreenLandsOnThatCorner() {
        val layout = ScreenLayout(width = 3.2f, distance = 2.4f)
        val hit = Scene.hitScreen(layout, 0.3f, rayTo(1.6f, 0.3f + 0.9f, -2.4f))!!
        assertEquals(1.6f, hit.x, 0.001f)
        assertEquals(0.9f, hit.y, 0.001f)
        assertEquals(ScreenZone.CORNER, Scene.zoneAt(layout, hit))
        // The picture itself is not a handle; the strip just below its middle is the grip.
        assertNull(Scene.zoneAt(layout, Scene.hitScreen(layout, 0f, rayTo(0.2f, 0.1f, -2.4f))!!))
        assertEquals(ScreenZone.GRIP, Scene.zoneAt(layout, Scene.hitScreen(layout, 0f, rayTo(0.1f, -0.9f - 0.1f, -2.4f))!!))
        assertNull(Scene.zoneAt(layout, Scene.hitScreen(layout, 0f, rayTo(3f, 0f, -2.4f))!!))
        assertNull(Scene.hitScreen(layout, 0f, rayTo(0f, 0f, 1f)))
    }

    @Test
    fun aCurvedScreenMeasuresAlongItsSurface() {
        val layout = ScreenLayout(width = 3.2f, distance = 2f, curved = true)
        val angle = 0.5f
        val hit = Scene.hitScreen(layout, 0f, rayTo(sin(angle), 0.25f, -cos(angle)))!!
        assertEquals(angle * 2f, hit.x, 0.001f)
        assertEquals(0.5f, hit.y, 0.001f)
    }

    @Test
    fun turningTheSceneTurnsTheRayBack() {
        val yaw = 0.4f
        // A ray aimed at the centre of a screen that was dragged 0.4 rad to the left.
        val ray = rayTo(-sin(yaw), 0f, -cos(yaw))
        assertEquals(yaw, ray.heading, 0.0001f)
        val hit = Scene.hitScreen(ScreenLayout(), 0f, ray.turned(yaw))!!
        assertEquals(0f, hit.x, 0.001f)
    }

    @Test
    fun tippingTheSceneUpTipsTheRayBack() {
        val pitch = 0.5f
        // A ray aimed at the centre of a screen that was raised 0.5 rad above eye level.
        val ray = rayTo(0f, sin(pitch), -cos(pitch))
        assertEquals(pitch, ray.elevation, 0.0001f)
        val hit = Scene.hitScreen(ScreenLayout(), 0f, ray.turned(0f, pitch))!!
        assertEquals(0f, hit.x, 0.001f)
        assertEquals(0f, hit.y, 0.001f)
    }

    @Test
    fun aRayAtTheBarLandsOnThePixelItPointsAt() {
        val bar = Scene.placeBar(ScreenLayout())
        val c = cos(bar.elevation)
        val s = sin(bar.elevation)
        // Centre of the bar, then a point a quarter of its width to the right and a quarter of its height up.
        val centre = Scene.hitBar(bar, rayTo(0f, bar.distance * s, -bar.distance * c))!!
        assertEquals(Scene.BAR_PX_WIDTH / 2f, centre.first, 0.5f)
        assertEquals(Scene.BAR_PX_HEIGHT / 2f, centre.second, 0.5f)
        val up = bar.height / 4f
        val offCentre = Scene.hitBar(
            bar, rayTo(bar.width / 4f, bar.distance * s + up * c, -bar.distance * c + up * s),
        )!!
        assertEquals(Scene.BAR_PX_WIDTH * 0.75f, offCentre.first, 0.5f)
        assertEquals(Scene.BAR_PX_HEIGHT * 0.25f, offCentre.second, 0.5f)
        assertNull(Scene.hitBar(bar, rayTo(0f, 0f, -1f)))
    }

    @Test
    fun barControlsFitInsideTheBarAndDoNotOverlap() {
        val presets = (1..ScreenPresets.MAX_PRESETS).map { ScreenPreset("P$it", ScreenLayout(width = 1f + it * 0.2f)) }
        val bar = ControlBar(presets, ScreenLayout())
        val boxes = bar.items.map { it.box } + bar.readouts.map { it.box }
        for (box in boxes) {
            assertTrue(box.left >= 0f && box.right <= Scene.BAR_PX_WIDTH + 0.01f && box.left < box.right)
            assertTrue(box.top >= 0f && box.bottom <= Scene.BAR_PX_HEIGHT && box.top < box.bottom)
        }
        for (a in boxes) for (b in boxes) {
            if (a !== b) {
                val apart = a.right <= b.left + 0.01f || b.right <= a.left + 0.01f || a.bottom <= b.top || b.bottom <= a.top
                assertTrue("$a overlaps $b", apart)
            }
        }
    }

    @Test
    fun theLastPresetSlotSavesOrRemoves() {
        val presets = ScreenPresets.defaults
        val onPreset = ControlBar(presets, presets[1].layout)
        assertEquals(1, onPreset.activePreset)
        assertNotNull(onPreset.find(BarAction.RemovePreset(1)))
        assertNull(onPreset.find(BarAction.SavePreset))

        val changed = ControlBar(presets, presets[1].layout.scaled(0.8f))
        assertEquals(-1, changed.activePreset)
        assertNotNull(changed.find(BarAction.SavePreset))
        assertEquals("Tela 2", ControlBar.nextPresetName(presets + ScreenPreset("tela 1", ScreenLayout()), "Tela"))
    }

    @Test
    fun gamepadFocusMovesAlongAndAcrossRows() {
        val bar = ControlBar(ScreenPresets.defaults, ScreenLayout())
        val smaller = bar.find(BarAction.Smaller)!!
        assertEquals(BarAction.Larger, bar.neighbour(smaller, 1, 0).action)
        assertEquals(smaller, bar.neighbour(smaller, -1, 0))
        assertEquals(0, bar.neighbour(smaller, 0, -1).row)
        assertEquals(2, bar.neighbour(smaller, 0, 1).row)
        val leave = bar.find(BarAction.Leave)!!
        assertEquals(BarAction.Close, bar.neighbour(leave, 0, 1).action)
        assertEquals(leave, bar.neighbour(bar.neighbour(leave, 0, 1), 0, 1).let { bar.neighbour(it, 0, -1) })
    }

    @Test
    fun thePasscodePadHasEveryDigitOnceAndFitsTheBar() {
        val pad = app.farsofa.vr.xr.PinPad()
        val digits = pad.items.mapNotNull { (it.action as? BarAction.Digit)?.value }
        assertEquals((0..9).toList(), digits.sorted())
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0), digits)
        for (item in pad.items) {
            assertTrue(item.box.left >= 0f && item.box.right <= Scene.BAR_PX_WIDTH + 0.01f)
            assertTrue(item.box.top >= pad.prompt.bottom && item.box.bottom <= Scene.BAR_PX_HEIGHT)
        }
        val five = pad.find(BarAction.Digit(5))!!
        assertEquals(BarAction.Digit(6), pad.neighbour(five, 1, 0).action)
        assertEquals(BarAction.Backspace, pad.neighbour(pad.find(BarAction.Digit(2))!!, 0, 1).action)
    }
}

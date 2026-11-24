// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr

import app.farsofa.vr.stream.StreamSession
import app.farsofa.vr.xr.ScreenLayout
import app.farsofa.vr.xr.ScreenPreset
import app.farsofa.vr.xr.ScreenPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLayoutTest {
    @Test
    fun scalingStopsAtTheLimits() {
        val layout = ScreenLayout(width = 3f, distance = 2f)
        assertEquals(3.3f, layout.scaled(1.1f).width, 0.001f)
        assertEquals(ScreenLayout.maxWidthAt(2f), layout.scaled(10f).width, 0.001f)
        assertEquals(ScreenLayout.MIN_WIDTH, layout.scaled(0.01f).width, 0.001f)
    }

    @Test
    fun movingCloserShrinksAScreenThatNoLongerFits() {
        val moved = ScreenLayout(width = 6f, distance = 4f).moved(-3f)
        assertEquals(1f, moved.distance, 0.001f)
        assertEquals(ScreenLayout.maxWidthAt(1f), moved.width, 0.001f)
        assertEquals(ScreenLayout.MAX_DISTANCE, ScreenLayout().moved(100f).distance, 0.001f)
    }

    @Test
    fun savingReplacesAPresetWithTheSameName() {
        val start = listOf(ScreenPreset("Sofa", ScreenLayout()), ScreenPreset("Cinema", ScreenLayout(curved = true)))
        val saved = ScreenPresets.save(start, " sofa ", ScreenLayout(width = 2f))
        assertEquals(listOf("sofa", "Cinema"), saved.map { it.name })
        assertEquals(2f, saved[0].layout.width, 0.001f)
    }

    @Test
    fun savingDropsTheOldestWhenFullAndIgnoresBlankNames() {
        var presets = emptyList<ScreenPreset>()
        repeat(ScreenPresets.MAX_PRESETS + 2) { presets = ScreenPresets.save(presets, "P$it", ScreenLayout()) }
        assertEquals(ScreenPresets.MAX_PRESETS, presets.size)
        assertEquals("P2", presets.first().name)
        assertEquals(presets, ScreenPresets.save(presets, "   ", ScreenLayout()))
        assertTrue(ScreenPresets.remove(presets, "p2").none { it.name == "P2" })
    }

    @Test
    fun strongerAxisWinsWhenTwoControllersDisagree() {
        assertEquals(-900, StreamSession.stronger(200, -900))
        assertEquals(500, StreamSession.stronger(500, 0))
    }
}

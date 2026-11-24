// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr

import app.farsofa.vr.input.DualSenseLink
import app.farsofa.vr.input.DualSenseReport
import app.farsofa.vr.input.PadTouch
import app.farsofa.vr.input.TriggerStrength
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.zip.CRC32

class DualSenseTest {
    private fun ByteArray.at(index: Int): Int = this[index].toInt() and 0xFF

    @Test
    fun stateMessageHasItsFixedShape() {
        val report = DualSenseReport.keepAlive()
        assertEquals(78, report.size)
        assertEquals(0x31, report.at(0))
        assertEquals(0x10, report.at(2))
        // Nothing is marked as valid, so the controller changes nothing.
        assertEquals(0, report.at(3))
        assertEquals(0, report.at(4))
        assertEquals(0, report.at(41))
    }

    @Test
    fun triggersGoRightThenLeftWithTheirValidBits() {
        val effects = ByteArray(22) { (it + 1).toByte() }
        val report = DualSenseReport.state(DualSenseReport.State(triggers = effects))
        assertEquals(0x0C, report.at(3))
        assertEquals(0, report.at(4))
        assertArrayEquals(effects, report.copyOfRange(13, 35))
    }

    @Test
    fun lightsDoNotTouchTheTriggers() {
        val report = DualSenseReport.state(DualSenseReport.State(colour = 0x123456, playerLeds = 0x04, brightness = 1))
        assertEquals(0, report.at(3))
        assertEquals(0x04 or 0x10, report.at(4))
        assertEquals(0x01, report.at(41))
        assertEquals(1, report.at(45))
        assertEquals(0x04, report.at(46))
        assertEquals(0x12, report.at(47))
        assertEquals(0x34, report.at(48))
        assertEquals(0x56, report.at(49))
    }

    @Test
    fun motorsAreWrittenRightFirst() {
        val report = DualSenseReport.state(DualSenseReport.State(motors = Pair(10, 200)))
        assertEquals(0x02, report.at(3))
        assertEquals(0x04, report.at(41))
        assertEquals(200, report.at(5))
        assertEquals(10, report.at(6))
    }

    @Test
    fun releasedTriggersUseTheNeutralMode() {
        val released = DualSenseReport.releasedTriggers()
        assertEquals(0x05, released.at(0))
        assertEquals(0x05, released.at(11))
        assertEquals(0, released.drop(1).take(10).sumOf { it.toInt() })
    }

    @Test
    fun sealNumbersTheMessageAndAddsTheChecksum() {
        val report = DualSenseReport.state(DualSenseReport.State(colour = 0xFF0000))
        DualSenseReport.seal(report, 0x1B)
        assertEquals(0xB0, report.at(1))

        // The checksum covers the marker of an outgoing message, which is not itself sent.
        val crc = CRC32()
        crc.update(byteArrayOf(0xA2.toByte()) + report.copyOfRange(0, 74))
        val expected = crc.value
        val stored = (0 until 4).fold(0L) { sum, index -> sum or (report.at(74 + index).toLong() shl (8 * index)) }
        assertEquals(expected, stored)
    }

    @Test
    fun hapticsMessageCarriesThirtyTwoFrames() {
        val samples = ByteArray(64) { it.toByte() }
        val report = DualSenseReport.haptics(samples, 0x1FF)
        assertEquals(142, report.size)
        assertEquals(0x32, report.at(0))
        assertEquals(0xFF, report.at(10))
        assertArrayEquals(samples, report.copyOfRange(13, 77))
        assertEquals(0, report.copyOfRange(77, 138).sumOf { it.toInt() })
    }

    @Test
    fun fullStrengthLeavesAnEffectAlone() {
        val effect = byteArrayOf(0x01, 3, 200.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        assertSame(effect, TriggerStrength.scaled(effect, TriggerStrength.factor(1)))
    }

    @Test
    fun simpleEffectsAreWeakenedButStayOn() {
        val feedback = byteArrayOf(0x01, 3, 200.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(100, TriggerStrength.scaled(feedback, 0.5f).at(2))
        val faint = byteArrayOf(0x02, 3, 5, 1, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(1, TriggerStrength.scaled(faint, 0.33f).at(3))
        // A weapon effect keeps its start and end positions.
        assertEquals(3, TriggerStrength.scaled(faint, 0.33f).at(1))
        assertEquals(5, TriggerStrength.scaled(faint, 0.33f).at(2))
    }

    @Test
    fun zonedEffectsScaleEachZone() {
        // Ten zones, all at force 8 (stored as 7): 30 bits set across four bytes.
        val effect = byteArrayOf(0x21, 0xFF.toByte(), 0x03, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x3F, 0, 0, 0, 0)
        val half = TriggerStrength.scaled(effect, 0.5f)
        // Force 8 halves to 4, stored as 3 in every zone.
        var bits = 0L
        for (index in 0 until 4) bits = bits or (half.at(3 + index).toLong() shl (8 * index))
        for (zone in 0 until 10) assertEquals(3, ((bits shr (3 * zone)) and 7).toInt())
        // The zones that are active do not change.
        assertEquals(0xFF, half.at(1))
        assertEquals(0x03, half.at(2))
    }

    @Test
    fun unknownEffectsPassThrough() {
        val effect = byteArrayOf(0x7F, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        assertArrayEquals(effect, TriggerStrength.scaled(effect, 0.33f))
    }

    @Test
    fun hapticsSamplesReachFullScaleAtHalfTheInputRange() {
        assertEquals(0, DualSenseLink.eightBit(0, 1f).toInt())
        assertEquals(127, DualSenseLink.eightBit(16384, 1f).toInt())
        assertEquals(127, DualSenseLink.eightBit(32767, 1f).toInt())
        assertEquals(-127, DualSenseLink.eightBit(-32768, 1f).toInt())
        assertEquals(63, DualSenseLink.eightBit(16384, 0.5f).toInt())
        assertEquals(0, DualSenseLink.eightBit(16384, 0f).toInt())
    }

    @Test
    fun messagesTravelAsUppercaseHex() {
        assertEquals("0031A2FF", DualSenseLink.hex(byteArrayOf(0x00, 0x31, 0xA2.toByte(), 0xFF.toByte())))
    }

    @Test
    fun touchpadPositionsCoverThePad() {
        assertEquals(0, PadTouch.position(0f, PadTouch.PAD_WIDTH))
        assertEquals(1919, PadTouch.position(1f, PadTouch.PAD_WIDTH))
        assertEquals(1079, PadTouch.position(1.5f, PadTouch.PAD_HEIGHT))
        assertEquals(959, PadTouch.position(0.5f, PadTouch.PAD_WIDTH))
    }
}

class PadMappingTest {
    @Test
    fun aLearnedButtonReplacesTheStandardOne() {
        val mapping = app.farsofa.vr.input.PadMapping(mapOf(1 to app.farsofa.vr.input.PadSource(key = 96)))
        val state = app.farsofa.vr.input.PadState().apply { buttons = 1 }
        mapping.applyTo(state, { it == 96 }, { 0f })
        assertEquals(1 or 2, state.buttons)
        mapping.applyTo(state, { false }, { 0f })
        assertEquals(1, state.buttons)
    }

    @Test
    fun aTriggerThatRestsAtMinusOneCoversItsWholeTravel() {
        val source = app.farsofa.vr.input.PadSource(axis = 5, sign = 1, rest = -1f)
        val mapping = app.farsofa.vr.input.PadMapping(mapOf(app.farsofa.vr.input.PadMapping.R2 to source))
        val state = app.farsofa.vr.input.PadState()
        mapping.applyTo(state, { false }, { -1f })
        assertEquals(0, state.r2)
        mapping.applyTo(state, { false }, { 0f })
        assertEquals(128, state.r2)
        mapping.applyTo(state, { false }, { 1f })
        assertEquals(255, state.r2)
    }

    @Test
    fun assignmentsSurviveBeingStored() {
        val mapping = app.farsofa.vr.input.PadMapping(mapOf(18 to app.farsofa.vr.input.PadSource(axis = 2, sign = -1)))
        // org.json is not available to plain unit tests, so only the shape of the data is checked here.
        assertEquals(-1, mapping.sources.getValue(18).sign)
    }

    @Test
    fun theLearnerTakesOneAnswerPerPress() {
        val learner = app.farsofa.vr.input.PadLearner()
        val first = learner.target
        learner.onKey(100, true)
        // Still held: the next control must not be answered by the same press.
        learner.onKey(101, true)
        assertEquals(1, learner.progress)
        learner.onKey(100, false)
        learner.onKey(101, false)
        learner.onKey(102, true)
        assertEquals(2, learner.progress)
        assertEquals(100, learner.result.sources.getValue(first).key)
    }

    @Test
    fun theLearnerReadsAStickFromItsAxis() {
        val learner = app.farsofa.vr.input.PadLearner()
        repeat(18) { learner.skip() }
        assertEquals(app.farsofa.vr.input.PadMapping.LEFT_X, learner.target)
        learner.onAxes(mapOf(0 to 0f, 1 to 0f))
        learner.onAxes(mapOf(0 to -0.9f, 1 to 0.1f))
        val source = learner.result.sources.getValue(app.farsofa.vr.input.PadMapping.LEFT_X)
        assertEquals(0, source.axis)
        assertEquals(-1, source.sign)
    }
}

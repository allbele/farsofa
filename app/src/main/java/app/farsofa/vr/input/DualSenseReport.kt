// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.input

import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The messages a DualSense accepts over Bluetooth, as plain byte arrays. Nothing here touches
 * Android, so the layouts can be checked by tests.
 *
 * The layout of the state message is the one the Linux kernel driver uses (hid-playstation,
 * `dualsense_output_report_bt`). The controller applies a part of the message only when the matching
 * "valid" bit is set, which is what lets the lights be rewritten without restarting a trigger effect.
 */
object DualSenseReport {
    const val STATE_ID = 0x31
    const val STATE_SIZE = 78
    const val HAPTICS_ID = 0x32
    const val HAPTICS_SIZE = 142

    /** Bytes of sound in one haptics message: 32 stereo frames, one signed byte per channel. */
    const val HAPTICS_PAYLOAD = 64

    /** A trigger effect: one byte of mode and ten of parameters. */
    const val TRIGGER_SIZE = 11

    /** The mode that lets a trigger move freely again. */
    const val TRIGGER_RELEASED = 0x05

    private const val OUTPUT_TAG = 0x10
    private const val VALID0 = 3
    private const val VALID1 = 4
    private const val MOTOR_RIGHT = 5
    private const val MOTOR_LEFT = 6
    private const val MUTE = 12
    private const val TRIGGERS = 13
    private const val POWER_REDUCTION = 39
    private const val VALID2 = 41
    private const val HAPTIC_FILTER = 42
    private const val LIGHTBAR_SETUP = 44
    private const val LED_BRIGHTNESS = 45
    private const val PLAYER_LEDS = 46
    private const val COLOUR = 47

    private const val V0_HAPTICS_SELECT = 0x02
    private const val V0_TRIGGER_RIGHT = 0x04
    private const val V0_TRIGGER_LEFT = 0x08
    private const val V1_MUTE = 0x02
    private const val V1_LIGHTBAR = 0x04
    private const val V1_PLAYER_LEDS = 0x10
    private const val V1_HAPTIC_FILTER = 0x20
    private const val V1_POWER_REDUCTION = 0x40
    private const val V2_LED_BRIGHTNESS = 0x01
    private const val V2_LIGHTBAR_SETUP = 0x02
    private const val V2_COMPATIBLE_VIBRATION = 0x04

    /** What one state message changes. A null part is left as the controller has it. */
    class State(
        /** Right then left trigger, [TRIGGER_SIZE] bytes each. */
        val triggers: ByteArray? = null,
        /** Light bar colour as 0xRRGGBB. */
        val colour: Int? = null,
        /** Which of the five player lights are on, one bit each. */
        val playerLeds: Int? = null,
        /** 0 bright, 1 medium, 2 dim. */
        val brightness: Int? = null,
        /** Strength of the two rumble motors, 0..255, as (left, right). */
        val motors: Pair<Int, Int>? = null,
    )

    fun state(change: State): ByteArray {
        val report = blankState()
        change.triggers?.let {
            require(it.size == 2 * TRIGGER_SIZE)
            report[VALID0] = (report[VALID0].toInt() or V0_TRIGGER_RIGHT or V0_TRIGGER_LEFT).toByte()
            it.copyInto(report, TRIGGERS)
        }
        change.motors?.let { (left, right) ->
            report[VALID0] = (report[VALID0].toInt() or V0_HAPTICS_SELECT).toByte()
            report[VALID2] = (report[VALID2].toInt() or V2_COMPATIBLE_VIBRATION).toByte()
            report[MOTOR_LEFT] = left.coerceIn(0, 255).toByte()
            report[MOTOR_RIGHT] = right.coerceIn(0, 255).toByte()
        }
        change.colour?.let {
            report[VALID1] = (report[VALID1].toInt() or V1_LIGHTBAR).toByte()
            report[COLOUR] = (it shr 16).toByte()
            report[COLOUR + 1] = (it shr 8).toByte()
            report[COLOUR + 2] = it.toByte()
        }
        change.playerLeds?.let {
            report[VALID1] = (report[VALID1].toInt() or V1_PLAYER_LEDS).toByte()
            report[PLAYER_LEDS] = (it and 0x1F).toByte()
        }
        change.brightness?.let {
            report[VALID2] = (report[VALID2].toInt() or V2_LED_BRIGHTNESS).toByte()
            report[LED_BRIGHTNESS] = it.coerceIn(0, 2).toByte()
        }
        return report
    }

    /** Changes nothing; sent so the link to the controller does not go to sleep. */
    fun keepAlive(): ByteArray = blankState()

    /** Takes the light bar away from the controller's own start-up animation, once per connection. */
    fun releaseLightbar(): ByteArray = blankState().also {
        it[VALID2] = V2_LIGHTBAR_SETUP.toByte()
        it[LIGHTBAR_SETUP] = 0x02
    }

    /**
     * Full strength, nothing muted and the smoothing filter on: the state the haptics need, whatever
     * the last program to use the controller left behind.
     */
    fun hapticsSetup(): ByteArray = blankState().also {
        it[VALID1] = (V1_MUTE or V1_HAPTIC_FILTER or V1_POWER_REDUCTION).toByte()
        it[MUTE] = 0
        it[POWER_REDUCTION] = 0
        it[HAPTIC_FILTER] = 0x01
    }

    /** Both triggers moving freely. */
    fun releasedTriggers(): ByteArray = ByteArray(2 * TRIGGER_SIZE).also {
        it[0] = TRIGGER_RELEASED.toByte()
        it[TRIGGER_SIZE] = TRIGGER_RELEASED.toByte()
    }

    /** The lights for a player number counted from zero, as the console itself shows them. */
    fun playerLeds(player: Int): Int = when (player) {
        0 -> 0x04
        1 -> 0x0A
        2 -> 0x15
        3 -> 0x1B
        else -> 0x1F
    }

    /** The light bar colour the console uses for a player when the game has not chosen one. */
    fun playerColour(player: Int): Int = when (player) {
        1 -> 0xFF0000
        2 -> 0x00FF00
        3 -> 0xFF00C0
        else -> 0x0000FF
    }

    /**
     * One block of haptics sound: [HAPTICS_PAYLOAD] bytes of signed 8-bit stereo at 3 kHz.
     * [block] counts these messages and wraps at 256.
     */
    fun haptics(samples: ByteArray, block: Int): ByteArray {
        require(samples.size == HAPTICS_PAYLOAD)
        val report = ByteArray(HAPTICS_SIZE)
        report[0] = HAPTICS_ID.toByte()
        report[2] = 0x91.toByte()
        report[3] = 0x07
        report[4] = 0xFE.toByte()
        // How much the controller queues before it plays: enough to cover jitter, not enough to be felt as delay.
        for (index in 5..9) report[index] = 16
        report[10] = block.toByte()
        report[11] = 0x92.toByte()
        report[12] = 0x40
        samples.copyInto(report, 13)
        return report
    }

    /**
     * Numbers and seals a message just before it is sent. [sequence] counts every message to the
     * controller, of both kinds, and only its low four bits are used.
     */
    fun seal(report: ByteArray, sequence: Int) {
        report[1] = ((report[1].toInt() and 0x0F) or ((sequence and 0x0F) shl 4)).toByte()
        val crc = CRC32()
        crc.update(0xA2) // the channel marker of an outgoing message; counted but not sent
        crc.update(report, 0, report.size - 4)
        val value = crc.value
        for (index in 0 until 4) report[report.size - 4 + index] = (value shr (8 * index)).toByte()
    }

    private fun blankState(): ByteArray = ByteArray(STATE_SIZE).also {
        it[0] = STATE_ID.toByte()
        it[2] = OUTPUT_TAG.toByte()
    }
}

/**
 * Weakens a trigger effect to the strength chosen in the console's settings. The effect arrives at
 * full strength whatever the setting is, so the client has to do this itself.
 */
object TriggerStrength {
    /** The factor for the console's setting: 1 strong, 2 medium, 3 weak. Zero (off) has none. */
    fun factor(setting: Int): Float = when (setting) {
        2 -> 0.5f
        3 -> 0.33f
        else -> 1f
    }

    /** [effect] is one trigger: a mode byte and ten parameters. Returns a weakened copy. */
    fun scaled(effect: ByteArray, factor: Float): ByteArray {
        if (factor >= 1f || effect.size != DualSenseReport.TRIGGER_SIZE) return effect
        val out = effect.copyOf()
        when (effect[0].toInt() and 0xFF) {
            0x01, 0x06, 0x11 -> out[2] = whole(effect[2], factor)
            0x02, 0x12 -> out[3] = whole(effect[3], factor)
            // Ten zones of three bits each, holding the force minus one.
            0x21, 0x26 -> packed(out, 3, 4, 10, factor, offsetByOne = true)
            0x22 -> packed(out, 3, 2, 2, factor, offsetByOne = true)
            0x25 -> packed(out, 3, 1, 1, factor, offsetByOne = true)
            0x27 -> packed(out, 3, 1, 2, factor, offsetByOne = false)
        }
        return out
    }

    // A force that was on stays on, however weak.
    private fun whole(value: Byte, factor: Float): Byte {
        val force = value.toInt() and 0xFF
        return if (force == 0) 0 else max(1, (force * factor).roundToInt()).toByte()
    }

    private fun packed(effect: ByteArray, at: Int, bytes: Int, fields: Int, factor: Float, offsetByOne: Boolean) {
        var bits = 0L
        for (index in 0 until bytes) bits = bits or ((effect[at + index].toLong() and 0xFF) shl (8 * index))
        for (field in 0 until fields) {
            val shift = 3 * field
            val stored = ((bits shr shift) and 7).toInt()
            val scaled = if (offsetByOne) {
                max(1, ((stored + 1) * factor).roundToInt()) - 1
            } else {
                if (stored == 0) 0 else max(1, (stored * factor).roundToInt())
            }
            bits = (bits and (7L shl shift).inv()) or (scaled.toLong() shl shift)
        }
        for (index in 0 until bytes) effect[at + index] = (bits shr (8 * index)).toByte()
    }
}

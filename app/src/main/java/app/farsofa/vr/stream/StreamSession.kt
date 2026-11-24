// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.stream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import app.farsofa.vr.console.RegisteredConsole
import app.farsofa.vr.core.Diagnostics
import app.farsofa.vr.core.Native
import app.farsofa.vr.input.DualSenseLink
import app.farsofa.vr.input.GamepadInput
import app.farsofa.vr.input.PadMotion
import app.farsofa.vr.input.PadPrefs
import app.farsofa.vr.input.PadState
import app.farsofa.vr.input.PadTouch
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * One connection to a console, independent of where its video is shown. The owning activity supplies
 * the surface and forwards input; everything here runs on the main thread unless noted.
 */
class StreamSession(private val activity: Activity, intent: Intent, private val callbacks: Callbacks) :
    Native.SessionListener {

    interface Callbacks {
        fun onStreamConnected()
        fun onLoginPinRequest(incorrect: Boolean)

        /** The connection is over. [error] is null when it ended normally. */
        fun onStreamEnded(error: String?)

        /** The player held the leave combination. */
        fun onLeaveRequested()

        /** The player tapped the PS button, which opens the app's own controls. */
        fun onMenuToggle()

        /** Gamepad buttons while [menuOpen]; they steer the app's controls instead of the game. */
        fun onMenuPad(buttons: Int)
    }

    @Volatile private var ptr = 0L
    private var started = false
    private val handler = Handler(Looper.getMainLooper())
    private val teardown = Executors.newSingleThreadExecutor()
    private val wifiLock = (activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "farsofa.stream")
        .apply { setReferenceCounted(false) }

    private val gamepad = GamepadInput(app.farsofa.vr.input.PadMappingStore(activity)) { publish() }
    private val touch = PadState()

    // What a DualSense offers beyond buttons and sticks.
    private val padPrefs = PadPrefs(activity)
    private val padLink = DualSenseLink(activity)
    private val padTouch = PadTouch { publish() }
    private var lastMotionSent = 0L
    private val padMotion = PadMotion(
        activity,
        onSample = { gx, gy, gz, ax, ay, az, timeUs ->
            val session = ptr
            if (session != 0L) {
                // Every reading feeds the orientation; the console gets one every few milliseconds.
                val now = SystemClock.uptimeMillis()
                val send = now - lastMotionSent >= MOTION_SEND_MS && !menuOpen
                if (send) lastMotionSent = now
                Native.sessionSetMotion(session, gx, gy, gz, ax, ay, az, timeUs, send)
            }
        },
        onLost = { val session = ptr; if (session != 0L) Native.sessionClearMotion(session) },
    )
    private var rumbleSetting = 1
    private var lastRumble = 0
    private var lastRumbleTime = 0L
    private val rumbleWatchdog = Runnable { lastRumble = 0; stopRumble() }

    /** The options a player can change mid-game, in the order the options pad shows them. */
    fun options(): BooleanArray =
        booleanArrayOf(padPrefs.triggers, padPrefs.lights, padPrefs.motion, padPrefs.fineHaptics, padPrefs.smoothMotion)

    /** Flips one option and applies it at once where that is possible (smooth motion waits for the next game). */
    fun toggleOption(index: Int) {
        when (index) {
            0 -> padPrefs.triggers = !padPrefs.triggers
            1 -> padPrefs.lights = !padPrefs.lights
            2 -> padPrefs.motion = !padPrefs.motion
            3 -> padPrefs.fineHaptics = !padPrefs.fineHaptics
            4 -> padPrefs.smoothMotion = !padPrefs.smoothMotion
        }
        Diagnostics.event("option $index toggled")
        val session = ptr
        if (session == 0L) return
        padLink.configure(triggers = padPrefs.triggers, lights = padPrefs.lights, haptics = padPrefs.fineHaptics)
        handler.removeCallbacks(padTick)
        handler.post(padTick)
        if (padPrefs.motion) {
            padMotion.start()
        } else {
            padMotion.stop()
            padTouch.release()
            Native.sessionClearMotion(session)
            publish()
        }
    }

    /** Whether the controller's touchpad fingers are wanted, which needs the window to capture the pointer. */
    val wantsPointerCapture: Boolean get() = padPrefs.motion

    private val leaveRunnable = Runnable { callbacks.onLeaveRequested() }
    private var leaveDelay = 0L

    // A short PS press belongs to the app; holding it passes the button on to the console.
    private var psDown = false
    private var psForwarded = false
    private val psHold = Runnable { psForwarded = true; publish() }
    private val menuToggle = Runnable { callbacks.onMenuToggle() }

    /** Buttons pressed on the player's behalf by [tap]. */
    private var injected = 0

    // Input still held when the controls close must not leak into the game as a fresh press.
    private var mutedPad = 0
    private var touchMuted = false

    /** While true the Touch controllers are busy moving the screen and the game does not see them. */
    var touchBusy = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) touchMuted = touch.buttons != 0 || touch.l2 != 0 || touch.r2 != 0
            publish()
        }

    /** While true the game receives a resting controller and the gamepad drives the app's controls. */
    var menuOpen = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                mutedPad = gamepad.state.buttons
                touchMuted = touch.buttons != 0 || touch.l2 != 0 || touch.r2 != 0
            }
            publish()
        }

    val isOpen: Boolean get() = ptr != 0L

    /** When the gamepad was last touched, in SystemClock.uptimeMillis() time. */
    val lastGamepadInputTime: Long get() = gamepad.lastInputTime

    // The haptics go to the controller as sound only while there is a controller that can play them.
    private val padTick = object : Runnable {
        override fun run() {
            val session = ptr
            if (session == 0L) return
            Native.sessionForwardHaptics(session, padLink.playsHaptics)
            handler.postDelayed(this, 1000)
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val session = ptr
            if (session == 0L) return
            Diagnostics.streamStats(activity, Native.sessionStats(session))
            handler.postDelayed(this, Diagnostics.STATS_INTERVAL_MS)
        }
    }

    init {
        val host = intent.getStringExtra(EXTRA_HOST)
        val registKey = intent.getByteArrayExtra(EXTRA_REGIST_KEY)
        val rpKey = intent.getByteArrayExtra(EXTRA_RP_KEY)
        if (host != null && registKey != null && rpKey != null) {
            ptr = Native.sessionCreate(
                host, registKey, rpKey,
                Native.RESOLUTION_1080P, 60, LAN_BITRATE_KBPS, Native.CODEC_HEVC, this,
            )
        }
        if (ptr != 0L) {
            wifiLock.acquire()
            Diagnostics.sessionStarted(activity)
            handler.postDelayed(statsTick, Diagnostics.STATS_INTERVAL_MS)
            if (padPrefs.needsLink) {
                padLink.start(triggers = padPrefs.triggers, lights = padPrefs.lights, haptics = padPrefs.fineHaptics)
                handler.post(padTick)
            }
            if (padPrefs.motion) padMotion.start()
        }
    }

    /** Points the decoder at [surface] and connects on the first call. Returns false if connecting failed. */
    fun attachSurface(surface: Surface): Boolean {
        val session = ptr
        if (session == 0L) return false
        Native.sessionSetSurface(session, surface)
        if (!started) {
            started = true
            return Native.sessionStart(session)
        }
        return true
    }

    fun setLoginPin(pin: String) {
        val session = ptr
        if (session != 0L) Native.sessionSetLoginPin(session, pin)
    }

    fun onKeyEvent(event: KeyEvent): Boolean = gamepad.onKeyEvent(event)

    fun onMotionEvent(event: MotionEvent): Boolean = padTouch.onEvent(event) || gamepad.onMotionEvent(event)

    /** Events that arrive while the window has captured the pointer: the fingers on the controller's touchpad. */
    fun onCapturedPointerEvent(event: MotionEvent): Boolean = padTouch.onEvent(event)

    fun setTouchState(buttons: Int, l2: Int, r2: Int, leftX: Int, leftY: Int, rightX: Int, rightY: Int) {
        touch.buttons = buttons and 0xFFFF
        touch.l2 = l2
        touch.r2 = r2
        touch.leftX = leftX
        touch.leftY = leftY
        touch.rightX = rightX
        touch.rightY = rightY
        if (touch.buttons == 0 && l2 == 0 && r2 == 0) touchMuted = false
        publish()
    }

    /** Presses [buttons] on the console for a moment, for keys the player's controller lacks. */
    fun tap(buttons: Int) {
        injected = injected or buttons
        publish()
        handler.postDelayed({ injected = injected and buttons.inv(); publish() }, TAP_MS)
    }

    /**
     * Frees the native session on a worker thread, since joining its threads can take a moment.
     * [afterFree] runs on that worker once nothing writes to the surface any more.
     */
    fun release(afterFree: (() -> Unit)? = null) {
        handler.removeCallbacksAndMessages(null)
        val session = ptr
        ptr = 0L
        if (session != 0L) {
            stopRumble()
            wifiLock.release()
        }
        padMotion.stop()
        padTouch.release()
        teardown.execute {
            // Before the session goes: this lets go of the triggers, which would otherwise stay stiff.
            padLink.stop()
            if (session != 0L) Native.sessionFree(session)
            afterFree?.invoke()
        }
        teardown.shutdown()
    }

    /** Sends the gamepad and Touch controllers to the console as one controller. */
    private fun publish() {
        val pad = gamepad.state
        watchPsButton(pad.buttons)
        mutedPad = mutedPad and pad.buttons
        val padButtons = (pad.buttons and PadState.PS.inv() and mutedPad.inv()) or (if (psForwarded) PadState.PS else 0)
        val session = ptr
        if (session != 0L) {
            if (menuOpen) {
                Native.sessionSetController(session, injected, 0, 0, 0, 0, 0, 0, null, null)
            } else {
                val useTouch = !touchMuted && !touchBusy
                Native.sessionSetController(
                    session,
                    padButtons or injected or (if (useTouch) touch.buttons else 0) or (if (padTouch.clicked) PadState.TOUCHPAD else 0),
                    maxOf(pad.l2, if (useTouch) touch.l2 else 0), maxOf(pad.r2, if (useTouch) touch.r2 else 0),
                    stronger(pad.leftX, touch.leftX), stronger(pad.leftY, touch.leftY),
                    stronger(pad.rightX, touch.rightX), stronger(pad.rightY, touch.rightY),
                    if (padPrefs.motion) padTouch.touches else null, null,
                )
            }
        }
        if (menuOpen) callbacks.onMenuPad(pad.buttons and PadState.PS.inv())
        watchLeaveCombo(pad.buttons)
    }

    private fun watchPsButton(buttons: Int) {
        val down = buttons and PadState.PS != 0
        if (down == psDown) return
        psDown = down
        handler.removeCallbacks(psHold)
        when {
            down -> handler.postDelayed(psHold, PS_HOLD_MS)
            psForwarded -> psForwarded = false
            else -> handler.post(menuToggle)
        }
    }

    /**
     * L1 + R1 + Options held for a second and a half. Not Create + Options: the headset's own system
     * uses that pair to switch its gamepad cursor on and off.
     */
    private fun watchLeaveCombo(padButtons: Int) {
        val combo = PadState.L1 or PadState.R1 or PadState.OPTIONS
        val wanted = if (padButtons and combo == combo) 1500L else 0L
        if (wanted == leaveDelay) return
        handler.removeCallbacks(leaveRunnable)
        leaveDelay = wanted
        if (wanted > 0L) handler.postDelayed(leaveRunnable, wanted)
    }

    // --- Session events (native threads) ---

    override fun onConnected() = activity.runOnUiThread {
        Diagnostics.event("stream connected")
        if (isOpen) callbacks.onStreamConnected()
    }

    override fun onLoginPinRequest(incorrect: Boolean) =
        activity.runOnUiThread { if (isOpen) callbacks.onLoginPinRequest(incorrect) }

    override fun onQuit(reason: Int, isError: Boolean, name: String?, detail: String?) = activity.runOnUiThread {
        Diagnostics.event("stream quit reason=$reason error=$isError name=$name detail=$detail")
        if (isOpen) callbacks.onStreamEnded(if (isError) detail ?: name ?: reason.toString() else null)
    }

    override fun onRumble(left: Int, right: Int) = activity.runOnUiThread {
        if (rumblesLogged < 20) {
            rumblesLogged++
            Diagnostics.event("rumble left=$left right=$right device=${gamepad.lastDeviceId} link=${padLink.connected}")
        }
        if (!isOpen) return@runOnUiThread
        val factor = if (rumbleSetting == 0) 0f else app.farsofa.vr.input.TriggerStrength.factor(rumbleSetting)
        val scaledLeft = (left * factor).toInt()
        val scaledRight = (right * factor).toInt()
        // While the controller plays the haptics as sound, a motor command would switch that off.
        if (padPrefs.fineHaptics && padLink.connected) return@runOnUiThread
        if (padLink.connected) {
            // One writer only: the system's vibration and our own messages to the controller undo each other.
            padLink.setMotors(scaledLeft, scaledRight)
            return@runOnUiThread
        }
        // The console repeats a rumble while it lasts and says nothing when it ends.
        handler.removeCallbacks(rumbleWatchdog)
        if (scaledLeft != 0 || scaledRight != 0) handler.postDelayed(rumbleWatchdog, RUMBLE_TIMEOUT_MS)
        // A steady rumble is renewed with the same strengths; it is restarted only often enough to
        // outlast the vibration it started, because restarting it every time is felt as pulsing.
        val key = (scaledLeft shl 8) or scaledRight
        val now = SystemClock.uptimeMillis()
        if (key == lastRumble && now - lastRumbleTime < RUMBLE_MS / 2) return@runOnUiThread
        lastRumble = key
        lastRumbleTime = now
        rumble(scaledLeft, scaledRight)
    }

    override fun onTriggerEffects(effects: ByteArray) {
        if (triggersLogged < 5) {
            triggersLogged++
            Diagnostics.event("trigger effects right=${effects[0]} left=${effects[11]} link=${padLink.connected}")
        }
        padLink.setTriggers(effects)
    }

    override fun onPadLight(what: Int, value: Int) {
        Diagnostics.event("pad light what=$what value=${"%x".format(value)}")
        if (what == 0) padLink.setColour(value) else padLink.setPlayer(value)
    }

    override fun onPadIntensity(what: Int, value: Int) {
        Diagnostics.event("pad intensity what=$what value=$value")
        if (what == 0) {
            rumbleSetting = value
            padLink.setRumbleSetting(value)
        } else {
            padLink.setTriggerSetting(value)
        }
    }

    override fun onMotionReset() = Diagnostics.event("motion reset")

    override fun onHaptics(pcm: ByteArray) = padLink.pushHaptics(pcm)

    private var rumblesLogged = 0
    private var triggersLogged = 0

    private fun rumble(left: Int, right: Int) {
        if (Build.VERSION.SDK_INT < 31) return
        val manager = InputDevice.getDevice(gamepad.lastDeviceId)?.vibratorManager ?: return
        val ids = manager.vibratorIds
        if (ids.isEmpty()) return
        if (left == 0 && right == 0) {
            manager.cancel()
            return
        }
        val combined = CombinedVibration.startParallel()
        val strengths = if (ids.size >= 2) listOf(left, right) else listOf(maxOf(left, right))
        strengths.forEachIndexed { index, strength ->
            if (strength > 0) {
                combined.addVibrator(ids[index], VibrationEffect.createOneShot(RUMBLE_MS, strength.coerceIn(1, 255)))
            }
        }
        manager.vibrate(combined.combine())
    }

    private fun stopRumble() {
        if (Build.VERSION.SDK_INT < 31) return
        InputDevice.getDevice(gamepad.lastDeviceId)?.vibratorManager?.cancel()
    }

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_REGIST_KEY = "registKey"
        private const val EXTRA_RP_KEY = "rpKey"
        private const val RUMBLE_MS = 2000L
        private const val RUMBLE_TIMEOUT_MS = 400L
        private const val MOTION_SEND_MS = 4L
        private const val TAP_MS = 150L

        /**
         * 1080p is the most the console sends. The library's default for it is 15 Mbit/s, tuned for
         * the internet; on a local network there is room for much more, which is what removes the
         * blockiness in fast scenes.
         */
        private const val LAN_BITRATE_KBPS = 40000
        private const val PS_HOLD_MS = 400L

        fun <T : Activity> intent(context: Context, target: Class<T>, host: String, console: RegisteredConsole): Intent =
            Intent(context, target)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_REGIST_KEY, console.registKey)
                .putExtra(EXTRA_RP_KEY, console.rpKey)

        /** Of two readings of the same axis, the one pushed further from centre. */
        fun stronger(a: Int, b: Int): Int = if (abs(a) >= abs(b)) a else b
    }
}

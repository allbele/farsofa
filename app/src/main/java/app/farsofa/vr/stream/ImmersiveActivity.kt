// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.stream

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import app.farsofa.vr.R
import app.farsofa.vr.core.Diagnostics
import app.farsofa.vr.input.PadState
import app.farsofa.vr.ui.MainActivity
import app.farsofa.vr.xr.BarAction
import app.farsofa.vr.xr.BarItem
import app.farsofa.vr.xr.ControlBar
import app.farsofa.vr.xr.Panel
import app.farsofa.vr.xr.PanelRenderer
import app.farsofa.vr.xr.PinPad
import app.farsofa.vr.xr.Ray
import app.farsofa.vr.xr.Scene
import app.farsofa.vr.xr.ScreenHit
import app.farsofa.vr.xr.ScreenLayout
import app.farsofa.vr.xr.ScreenLayoutStore
import app.farsofa.vr.xr.ScreenPreset
import app.farsofa.vr.xr.ScreenPresets
import app.farsofa.vr.xr.ScreenZone
import app.farsofa.vr.xr.XrNative
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams one console onto a virtual screen in an OpenXR session, and runs the controls around it:
 * a bar below the screen and handles on its corners. Nothing is ever drawn over the picture except
 * the pointer dot while the controls are open.
 */
class ImmersiveActivity : ComponentActivity(), StreamSession.Callbacks, XrNative.Listener {
    private enum class Mode { PLAYING, HINT, CONTROLS }

    /** What the pointer's trigger is holding. */
    private sealed interface Grab {
        data object Item : Grab
        data class Corner(val startWidth: Float, val startRadius: Float, val centerY: Float) : Grab
        data object Move : Grab
    }

    private lateinit var stream: StreamSession
    private lateinit var store: ScreenLayoutStore
    private lateinit var renderer: PanelRenderer
    private val handler = Handler(Looper.getMainLooper())
    private var xr = 0L
    private var leaving = false
    private var demo = false
    private var demoFrame = false

    private var layout = ScreenLayout()
    private var presets = emptyList<ScreenPreset>()
    private var yaw = 0f
    private var pitch = 0f
    private var follow = false

    /** Distance change made with the thumbstick during the current move, as a multiplier. */
    private var stickScale = 1f

    /** Where the screen and the hand were when the current move took hold; set a few frames into the move. */
    private class MoveStart(
        val yaw: Float, val heading: Float, val pitch: Float, val elevation: Float, val distance: Float, val hand: Ray,
    )
    private var moveStart: MoveStart? = null
    private var moveSettle = 0
    private var canCurve = true
    private var canPassthrough = true
    private var barSurface: Surface? = null
    private var overlaySurface: Surface? = null

    private var mode = Mode.PLAYING
    private var bar = ControlBar(presets, layout)

    // While the console waits for its login passcode, the pad takes the bar's place.
    private var pinPad: PinPad? = null
    private var pinDigits = ""
    private var pinIncorrect = false
    private val panel: Panel get() = pinPad ?: bar
    private var placement = Scene.placeBar(layout, allowLift = false)

    // Gamepad focus and pointer hover both highlight a control; the pointer wins while it is on the bar.
    private var focus: BarItem? = null
    private var hover: BarItem? = null
    private var held: BarAction? = null
    private var lastPadButtons = 0

    private val pointerValues = FloatArray(2 * XrNative.HAND_STRIDE)
    private val pointerPending = AtomicBoolean(false)
    private var pointerSeen = false
    private var activeHand = 0
    private var triggerDown = false
    private var grab: Grab? = null
    private var barCursor: Pair<Float, Float>? = null
    private var screenHit: ScreenHit? = null
    private var screenZone: ScreenZone? = null
    private var nearFrame = false
    private var lastPointerTime = 0L

    /** The reset button was pressed once and is waiting for the second press that confirms it. */
    private var confirmingReset = false
    private val cancelReset = Runnable { confirmingReset = false; drawPanels() }

    private var touchMenuDown = false
    private var touchMenuLeft = false
    private val touchMenuHold = Runnable { touchMenuLeft = true; leave(null) }
    private val hideHint = Runnable { if (mode == Mode.HINT) { mode = Mode.PLAYING; pushScene() } }
    private val repeatHeld = object : Runnable {
        override fun run() {
            val action = held ?: return
            perform(action)
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The view takes focus so the window is a target for gamepad events.
        setContentView(View(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            // With the pointer captured, the controller's touchpad reports fingers instead of moving a cursor.
            setOnCapturedPointerListener { _, event -> ::stream.isInitialized && !leaving && stream.onCapturedPointerEvent(event) }
            focusView = this
        })

        store = ScreenLayoutStore(this)
        renderer = PanelRenderer(this)
        layout = store.current
        follow = store.follow
        presets = store.presets
        bar = ControlBar(presets, layout)

        stream = StreamSession(this, intent, this)
        // Test builds can open the screen with a picture of our own instead of a console, which is how
        // the store screenshots are taken without showing anyone else's game.
        demo = intent.getBooleanExtra(EXTRA_DEMO, false) && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (demo) {
            // A staged shot: the layout comes from the launch command and is not remembered afterwards.
            layout = ScreenLayout(
                width = intent.getFloatExtra("width", layout.width),
                distance = intent.getFloatExtra("distance", layout.distance),
                curved = intent.getBooleanExtra("curved", false),
                passthrough = intent.getBooleanExtra("passthrough", false),
            ).clamped()
            follow = false
        }
        if (!stream.isOpen && !demo) {
            leave(getString(R.string.stream_failed_start))
            return
        }
        xr = XrNative.start(
            this, VIDEO_WIDTH, VIDEO_HEIGHT, Scene.BAR_PX_WIDTH, Scene.BAR_PX_HEIGHT,
            PanelRenderer.OVERLAY_PX_WIDTH, PanelRenderer.OVERLAY_PX_HEIGHT,
            app.farsofa.vr.input.PadPrefs(this).smoothMotion, this,
        )
        pushScene()
    }

    override fun onDestroy() {
        super.onDestroy()
        leave(null, returnToMenu = false)
    }

    /**
     * Tears down in the only safe order: the decoder stops first, then the surface it wrote to goes
     * away with the immersive session, and only then does the panel come back.
     */
    private fun leave(notice: String?, returnToMenu: Boolean = true) {
        if (leaving) return
        leaving = true
        Diagnostics.event("immersive leave notice=$notice layout=$layout pitch=$pitch yaw=$yaw")
        handler.removeCallbacksAndMessages(null)
        if (!demo) store.current = layout
        // Drawing happens on this thread, so dropping the surfaces here means nothing touches them again.
        barSurface = null
        overlaySurface = null
        val session = xr
        xr = 0L
        stream.release {
            if (session != 0L) XrNative.stop(session)
            if (returnToMenu) runOnUiThread {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_NOTICE, notice)
                )
                finishAndRemoveTask()
            }
        }
    }

    private fun drawDemoPicture(surface: Surface) {
        try {
            val picture = assets.open("demo.png").use { BitmapFactory.decodeStream(it) } ?: return
            val canvas = surface.lockHardwareCanvas()
            canvas.drawBitmap(picture, null, Rect(0, 0, canvas.width, canvas.height), null)
            surface.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            Diagnostics.event("demo picture unavailable: $e")
        }
    }

    // --- Scene ---

    /** Sends the current placement of the screen, the bar and the handles to the frame loop. */
    private fun pushScene() {
        val session = xr
        if (session == 0L) return
        val controls = mode == Mode.CONTROLS
        placement = Scene.placeBar(layout, allowLift = controls)
        XrNative.setScene(
            session, layout.distance, layout.width, layout.curved, layout.passthrough,
            if (controls) placement.screenLift else 0f, yaw, pitch,
            mode != Mode.PLAYING && barSurface != null,
            placement.distance, placement.elevation, placement.width, placement.height,
            frameVisible && overlaySurface != null, Scene.OVERLAY_MARGIN, barSurface != null, follow,
        )
    }

    /** The frame with its handles shows while a pointer is on it, and for as long as the controls are open. */
    private val frameVisible: Boolean
        get() = demoFrame || (pointerSeen && (mode == Mode.CONTROLS || nearFrame || (grab != null && grab != Grab.Item)))

    private fun drawPanels() {
        val pad = pinPad
        if (mode == Mode.CONTROLS && pad != null) barSurface?.let {
            renderer.drawPin(it, pad, pinDigits.length, pinIncorrect, hover ?: focus, held != null || grab == Grab.Item, barCursor)
        } else if (mode == Mode.CONTROLS) barSurface?.let {
            renderer.drawBar(
                it, bar, presets, layout, follow, confirmingReset, hover ?: focus, held != null || grab == Grab.Item, barCursor,
            )
        }
        if (frameVisible) overlaySurface?.let {
            renderer.drawOverlay(it, layout, screenZone, grab != null && grab != Grab.Item, screenHit)
        }
    }

    /** Applies a new layout everywhere it shows. */
    private fun update(next: ScreenLayout) {
        layout = fit(next)
        val focused = focus?.action
        bar = ControlBar(presets, layout)
        // The save slot turns into a remove slot and back, so the focused control may no longer exist.
        focus = focused?.let { panel.find(it) ?: if (pinPad == null) bar.items.last { item -> item.row == 0 } else null }
        pushScene()
        drawPanels()
    }

    private fun fit(next: ScreenLayout): ScreenLayout =
        next.copy(curved = next.curved && canCurve, passthrough = next.passthrough && canPassthrough).clamped()

    private fun openControls() {
        if (leaving || barSurface == null) return
        handler.removeCallbacks(hideHint)
        Diagnostics.event("controls opened")
        mode = Mode.CONTROLS
        lastPadButtons = 0.inv() // buttons already down when the controls open do not count as presses
        stream.menuOpen = true
        focus = null
        hover = null
        update(layout)
    }

    /** The console asked for its login passcode: show the pad until four digits are in. */
    private fun openPin(incorrect: Boolean) {
        if (leaving || barSurface == null) return
        Diagnostics.event("passcode requested incorrect=$incorrect")
        pinPad = PinPad()
        pinDigits = ""
        pinIncorrect = incorrect
        if (mode == Mode.CONTROLS) update(layout) else openControls()
    }

    private fun closeControls() {
        if (pinPad != null && pinDigits.length < PinPad.LENGTH) return // the console is still waiting
        pinPad = null
        Diagnostics.event("controls closed layout=$layout")
        stopHolding()
        mode = Mode.PLAYING
        grab = null
        triggerDown = false
        if (!demo) store.current = layout
        stream.menuOpen = false
        pushScene()
    }

    private fun toggleControls() = if (mode == Mode.CONTROLS) closeControls() else openControls()

    private fun perform(action: BarAction) {
        Diagnostics.event("bar action $action")
        // Any other press cancels a reset that was waiting to be confirmed.
        if (action != BarAction.ResetPresets && confirmingReset) {
            confirmingReset = false
            handler.removeCallbacks(cancelReset)
        }
        when (action) {
            is BarAction.Digit -> {
                if (pinDigits.length < PinPad.LENGTH) pinDigits += action.value
                if (pinDigits.length == PinPad.LENGTH) {
                    stream.setLoginPin(pinDigits)
                    closeControls()
                } else {
                    drawPanels()
                }
            }
            BarAction.Backspace -> {
                pinDigits = pinDigits.dropLast(1)
                drawPanels()
            }
            BarAction.ResetPresets -> {
                handler.removeCallbacks(cancelReset)
                if (confirmingReset) {
                    confirmingReset = false
                    presets = ScreenPresets.defaults
                    store.presets = presets
                } else {
                    confirmingReset = true
                    handler.postDelayed(cancelReset, RESET_CONFIRM_MS)
                }
                update(layout)
            }
            is BarAction.ApplyPreset -> presets.getOrNull(action.index)?.let { update(it.layout) }
            BarAction.SavePreset -> {
                val name = ControlBar.nextPresetName(presets, getString(R.string.bar_preset_base))
                presets = ScreenPresets.save(presets, name, layout)
                store.presets = presets
                update(layout)
            }
            is BarAction.RemovePreset -> presets.getOrNull(action.index)?.let {
                presets = ScreenPresets.remove(presets, it.name)
                store.presets = presets
                update(layout)
            }
            BarAction.Smaller -> update(layout.scaled(1f / ControlBar.SIZE_STEP))
            BarAction.Larger -> update(layout.scaled(ControlBar.SIZE_STEP))
            BarAction.Closer -> update(layout.moved(-ControlBar.DISTANCE_STEP))
            BarAction.Farther -> update(layout.moved(ControlBar.DISTANCE_STEP))
            BarAction.ToggleCurve -> update(layout.copy(curved = !layout.curved))
            BarAction.ToggleBackground -> update(layout.copy(passthrough = !layout.passthrough))
            BarAction.ToggleFollow -> {
                follow = !follow
                store.follow = follow
                update(layout)
            }
            is BarAction.Press -> stream.tap(action.buttons)
            BarAction.Close -> closeControls()
            BarAction.Leave -> leave(null)
        }
    }

    /** Fires [action] now and, for the stepping controls, keeps firing while it stays held. */
    private fun startHolding(action: BarAction) {
        stopHolding()
        held = action
        if (action.repeats) handler.postDelayed(repeatHeld, REPEAT_DELAY_MS)
        perform(action)
        if (mode == Mode.CONTROLS && !leaving) drawPanels() else held = null
    }

    private fun stopHolding() {
        handler.removeCallbacks(repeatHeld)
        if (held == null) return
        held = null
        if (!demo) store.current = layout
        drawPanels()
    }

    // --- Pointer (Touch controllers) ---

    private fun onPointerUpdate() {
        val values = FloatArray(pointerValues.size)
        synchronized(pointerValues) { pointerValues.copyInto(values) }
        pointerPending.set(false)
        if (leaving || barSurface == null) return
        val controls = mode == Mode.CONTROLS
        val lift = if (controls) placement.screenLift else 0f

        val now = SystemClock.uptimeMillis()
        val elapsed = ((now - lastPointerTime).coerceIn(0L, 50L)) / 1000f
        lastPointerTime = now

        // Bare hands holding the gamepad are not pointing at anything, so they are ignored while it is in use.
        val gamepadBusy = now - stream.lastGamepadInputTime < GAMEPAD_BUSY_MS
        val hands = intArrayOf(0, XrNative.HAND_STRIDE).filter {
            values[it] > 0.5f && !(values[it] > 1.5f && gamepadBusy && grab == null)
        }
        val hand = when {
            grab != null && activeHand in hands -> activeHand
            else -> hands.firstOrNull { values[it + 7] > PRESS } ?: activeHand.takeIf { it in hands } ?: hands.firstOrNull()
        }
        val hadPointer = pointerSeen
        pointerSeen = hand != null
        val oldCursor = barCursor
        val oldHit = screenHit
        val oldZone = screenZone
        val oldHover = hover
        val oldGrab = grab
        val frameWasVisible = frameVisible

        if (hand == null) {
            endGrab()
            triggerDown = false
            hover = null
            barCursor = null
            screenHit = null
            screenZone = null
            nearFrame = false
        } else {
            activeHand = hand
            val worldRay = Ray(values[hand + 1], values[hand + 2], values[hand + 3], values[hand + 4], values[hand + 5], values[hand + 6])
            val ray = worldRay.turned(yaw, pitch)
            val pressed = values[hand + 7] > if (triggerDown) RELEASE else PRESS
            val stick = values[hand + 8]

            val onBar = if (controls && (grab == null || grab == Grab.Item)) Scene.hitBar(placement, ray) else null
            barCursor = onBar
            val onScreen = if (onBar == null) Scene.hitScreen(layout, lift, ray) else null
            hover = onBar?.let { panel.itemAt(it.first, it.second) }

            when (val current = grab) {
                null -> {
                    screenHit = onScreen
                    screenZone = onScreen?.let { Scene.zoneAt(layout, it) }
                    // A controller shows the frame as it nears the edge; a bare hand only on a handle itself,
                    // because hands wander past the screen all the time.
                    val bare = values[hand] > 1.5f
                    nearFrame = onScreen != null && if (bare) screenZone != null else Scene.nearFrame(layout, onScreen)
                    if (pressed && !triggerDown) beginGrab(worldRay, onScreen)
                }
                Grab.Item -> if (!pressed) endGrab()
                is Grab.Corner -> {
                    // Measured from where the screen's centre was when the drag began, so the screen
                    // rising to clear the bar as it grows does not feed back into its own size.
                    val hit = Scene.hitScreen(layout, current.centerY, ray)
                    if (!pressed) endGrab()
                    else if (hit != null) {
                        screenHit = Scene.hitScreen(layout, lift, ray)
                        val width = current.startWidth * hit.radius / current.startRadius
                        if (kotlin.math.abs(width - layout.width) > 0.002f) update(layout.copy(width = width))
                    }
                }
                Grab.Move -> {
                    val start = moveStart
                    if (!pressed) endGrab()
                    else if (start == null) {
                        // The scene was just re-centred on the player; the first reports still describe the
                        // hand from the old centre, so the move takes hold a few frames later.
                        if (--moveSettle <= 0) {
                            moveStart = MoveStart(yaw, worldRay.heading, pitch, worldRay.elevation, layout.distance, worldRay)
                        }
                    } else {
                        val current = start
                        // The screen follows the pointer sideways and up or down; the thumbstick sets how far away it is.
                        yaw = current.yaw + worldRay.heading - current.heading
                        pitch = (current.pitch + worldRay.elevation - current.elevation)
                            .coerceIn(-Scene.MAX_PITCH, Scene.MAX_PITCH)
                        screenHit = Scene.hitScreen(layout, lift, worldRay.turned(yaw, pitch))
                        // Pushing the hand forward sends the screen away and pulling brings it closer;
                        // the thumbstick does the same for those who prefer it.
                        if (kotlin.math.abs(stick) > 0.15f) stickScale *= kotlin.math.exp(stick * MOVE_RATE * elapsed)
                        val pushed = (worldRay.ox - current.hand.ox) * current.hand.dx +
                            (worldRay.oy - current.hand.oy) * current.hand.dy +
                            (worldRay.oz - current.hand.oz) * current.hand.dz
                        val push = if (pushed > PUSH_DEADZONE) pushed - PUSH_DEADZONE
                            else if (pushed < -PUSH_DEADZONE) pushed + PUSH_DEADZONE else 0f
                        val distance = current.distance * kotlin.math.exp(push * PUSH_GAIN) * stickScale
                        if (kotlin.math.abs(distance - layout.distance) > 0.005f) update(layout.copy(distance = distance))
                        else pushScene()
                    }
                }
            }
            triggerDown = pressed
        }

        // While a hand is on the frame, its trigger and buttons must not reach the game.
        stream.touchBusy = !controls && (screenZone != null || (grab != null && grab != Grab.Item))
        if (hadPointer != pointerSeen || frameWasVisible != frameVisible) pushScene()
        if (oldCursor != barCursor || oldHit != screenHit || oldZone != screenZone || oldHover != hover || oldGrab != grab) {
            drawPanels()
        }
    }

    private fun beginGrab(worldRay: Ray, onScreen: ScreenHit?) {
        val item = hover
        when {
            item != null -> {
                grab = Grab.Item
                focus = null
                startHolding(item.action)
            }
            onScreen == null -> Unit
            screenZone == ScreenZone.CORNER ->
                grab = Grab.Corner(
                    layout.width, onScreen.radius.coerceAtLeast(0.05f),
                    if (mode == Mode.CONTROLS) placement.screenLift else 0f,
                )
            screenZone == ScreenZone.GRIP -> {
                // Wherever the player has walked to, the screen is moved around them, not around
                // the spot where the session began.
                XrNative.recentre(xr)?.let { placed ->
                    yaw = placed[0]
                    pitch = placed[1].coerceIn(-Scene.MAX_PITCH, Scene.MAX_PITCH)
                    update(layout.copy(distance = placed[2]))
                }
                stickScale = 1f
                moveStart = null
                moveSettle = 3
                grab = Grab.Move
            }
        }
        grab?.let { Diagnostics.event("grab started $it hand=${if (activeHand == 0) "right" else "left"}") }
    }

    private fun endGrab() {
        if (grab == null) return
        Diagnostics.event("grab ended $grab layout=$layout pitch=$pitch yaw=$yaw")
        grab = null
        stopHolding()
        if (!demo) store.current = layout
    }

    // --- Immersive screen (frame thread) ---

    override fun onSurfacesReady(video: Surface, bar: Surface, overlay: Surface, canCurve: Boolean, canPassthrough: Boolean) =
        runOnUiThread {
            if (leaving) return@runOnUiThread
            Diagnostics.event("surfaces ready canCurve=$canCurve canPassthrough=$canPassthrough layout=$layout pitch=$pitch")
            this.canCurve = canCurve
            this.canPassthrough = canPassthrough
            barSurface = bar
            overlaySurface = overlay
            layout = fit(layout)
            if (demo) {
                drawDemoPicture(video)
            } else if (!stream.attachSurface(video)) {
                leave(getString(R.string.stream_failed_start))
                return@runOnUiThread
            }
            if (demo) {
                demoFrame = intent.getBooleanExtra("frame", false)
                if (intent.getBooleanExtra("pin", false)) openPin(false)
                else if (intent.getBooleanExtra("controls", false)) openControls()
                else pushScene()
                if (demoFrame) {
                    drawPanels()
                    pushScene()
                }
                return@runOnUiThread
            }
            mode = Mode.HINT
            renderer.drawHint(bar, getString(R.string.immersive_hint))
            pushScene()
            handler.postDelayed(hideHint, HINT_MS)
        }

    override fun onTouchState(buttons: Int, l2: Int, r2: Int, leftX: Int, leftY: Int, rightX: Int, rightY: Int) =
        runOnUiThread {
            if (leaving) return@runOnUiThread
            // Menu key: a tap opens or closes the controls, holding it leaves the game.
            val menu = buttons and XrNative.TOUCH_MENU != 0
            if (menu != touchMenuDown) {
                touchMenuDown = menu
                handler.removeCallbacks(touchMenuHold)
                if (menu) {
                    touchMenuLeft = false
                    handler.postDelayed(touchMenuHold, TOUCH_LEAVE_MS)
                } else if (!touchMenuLeft) {
                    toggleControls()
                }
            }
            if (!leaving) stream.setTouchState(buttons, l2, r2, leftX, leftY, rightX, rightY)
        }

    override fun onPointer(values: FloatArray) {
        synchronized(pointerValues) { values.copyInto(pointerValues, 0, 0, pointerValues.size) }
        if (pointerPending.compareAndSet(false, true)) handler.post { onPointerUpdate() }
    }

    /** The screen opens where the player is looking, which is not straight ahead for someone lying back. */
    override fun onGaze(heading: Float, elevation: Float) = runOnUiThread {
        if (leaving) return@runOnUiThread
        yaw = heading
        pitch = elevation.coerceIn(-Scene.MAX_PITCH, Scene.MAX_PITCH)
        Diagnostics.event("screen opened at gaze yaw=$yaw pitch=$pitch")
        pushScene()
    }

    override fun onXrError(message: String) = runOnUiThread {
        Diagnostics.event("xr error: $message")
        leave(getString(R.string.immersive_failed))
    }

    override fun onXrExit() = runOnUiThread {
        Diagnostics.event("xr session ended by the system")
        leave(null)
    }

    // --- Stream ---

    override fun onStreamConnected() = Unit

    override fun onLoginPinRequest(incorrect: Boolean) = openPin(incorrect)

    override fun onStreamEnded(error: String?) =
        leave(error?.let { getString(R.string.stream_ended_error, it) })

    override fun onLeaveRequested() = leave(null)

    override fun onMenuToggle() {
        if (!leaving) toggleControls()
    }

    /** Gamepad in the controls: the d-pad moves, Cross presses, Circle closes. */
    override fun onMenuPad(buttons: Int) {
        val pressed = buttons and lastPadButtons.inv()
        val released = lastPadButtons and buttons.inv()
        lastPadButtons = buttons
        if (mode != Mode.CONTROLS || leaving) return

        if (released and PadState.CROSS != 0) stopHolding()
        when {
            pressed and PadState.CIRCLE != 0 -> if (pinPad != null) perform(BarAction.Backspace) else closeControls()
            pressed and PadState.CROSS != 0 -> focus?.let { startHolding(it.action) }
            pressed and PadState.DPAD_MASK != 0 -> {
                val from = focus
                focus = when {
                    from == null -> panel.find(BarAction.Larger) ?: panel.items.first()
                    pressed and PadState.DPAD_LEFT != 0 -> panel.neighbour(from, -1, 0)
                    pressed and PadState.DPAD_RIGHT != 0 -> panel.neighbour(from, 1, 0)
                    pressed and PadState.DPAD_UP != 0 -> panel.neighbour(from, 0, -1)
                    else -> panel.neighbour(from, 0, 1)
                }
                drawPanels()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Diagnostics.event("window focus=$hasFocus")
        if (hasFocus && ::stream.isInitialized && stream.wantsPointerCapture) focusView?.requestPointerCapture()
    }

    override fun onResume() {
        super.onResume()
        Diagnostics.event("activity resumed")
    }

    override fun onPause() {
        super.onPause()
        Diagnostics.event("activity paused")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (rawEventsLogged < RAW_EVENT_LOG_LIMIT && event.repeatCount == 0) {
            rawEventsLogged++
            Diagnostics.event("key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} source=${"%x".format(event.source)} device=${event.device?.name}")
        }
        return (!leaving && stream.onKeyEvent(event)) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (rawMotionsLogged < 5) {
            rawMotionsLogged++
            Diagnostics.event("motion action=${event.action} source=${"%x".format(event.source)} device=${event.device?.name}")
        }
        return (!leaving && stream.onMotionEvent(event)) || super.dispatchGenericMotionEvent(event)
    }

    private var focusView: View? = null
    private var rawEventsLogged = 0
    private var rawMotionsLogged = 0

    companion object {
        /** Test builds only: show the bundled picture instead of connecting to a console. */
        const val EXTRA_DEMO = "demo"

        const val VIDEO_WIDTH = 1920
        const val VIDEO_HEIGHT = 1080

        const val HINT_MS = 7000L
        const val RESET_CONFIRM_MS = 5000L
        const val RAW_EVENT_LOG_LIMIT = 60
        const val TOUCH_LEAVE_MS = 2000L
        const val REPEAT_DELAY_MS = 350L
        const val REPEAT_MS = 90L

        /** Trigger travel that counts as pressed, and the lower value it must fall to before it lets go. */
        const val PRESS = 0.6f
        const val RELEASE = 0.35f

        /** With the thumbstick fully pushed, the screen covers this share of its distance per second. */
        const val MOVE_RATE = 0.9f

        /** Hand travel along the pointing direction that is ignored, in metres, so turning the wrist does not move the screen. */
        const val PUSH_DEADZONE = 0.03f

        /** A push of 1 / PUSH_GAIN metres multiplies the distance by e; about 23 cm doubles it. */
        const val PUSH_GAIN = 3f

        /** Bare hands are ignored for this long after the gamepad was last touched. */
        const val GAMEPAD_BUSY_MS = 4000L
    }
}

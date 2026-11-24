// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.input

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Talks back to a DualSense paired with the headset over Bluetooth: adaptive triggers, the light bar,
 * the player lights and vibration.
 *
 * Android has no public way to send a controller anything but a plain vibration. The system's own
 * Bluetooth service does accept raw messages for it, through an interface apps are not shown
 * (`BluetoothHidHost`), which is reached here by reflection. That this is possible on a Quest, and
 * most of what can go wrong on the way, was worked out by the P5M project.
 *
 * Everything runs on the link's own threads. Callers only say what the controller should be doing;
 * what is sent, and when, is decided here:
 *  - a trigger effect is sent once when it changes, because repeating it would restart it mid-pull;
 *  - the light bar is repeated twice a second, because the system keeps putting its own blue back;
 *  - something is sent at least every four seconds, because half a minute without a message from the
 *    headset makes the link doze, and a dozing link delays everything in both directions.
 */
class DualSenseLink(context: Context) {
    private val context = context.applicationContext
    private val lock = Object()
    private val sendLock = Any()

    private var worker: Thread? = null
    private var hapticsWorker: Thread? = null
    @Volatile private var running = false

    // The connection. Touched by the worker; read by others through [connected].
    private var proxy: BluetoothProfile? = null
    private var device: BluetoothDevice? = null
    private var sendData: Method? = null
    private var sequence = 0
    private var hapticsBlock = 0
    @Volatile private var lastSendTime = 0L

    /** True while messages are reaching a DualSense. */
    @Volatile var connected = false
        private set

    // What the controller should be doing, guarded by [lock].
    private var triggers: ByteArray? = null
    private var triggerSetting = 1
    private var triggersEnabled = true
    private var triggersPending = true
    private var lightsEnabled = true
    private var colour: Int? = null
    private var player: Int? = null
    private var brightness: Int? = null
    private var lightsPending = false
    private var lightbarReleased = false
    private var motorLeft = 0
    private var motorRight = 0
    private var motorsPending = false
    private var motorsOwned = false
    private var motorTime = 0L
    private var rumbleSetting = 1
    private var lastLightTime = 0L

    // Haptics waiting to be played, as signed 8-bit stereo. Guarded by [hapticsQueue] itself.
    private val hapticsQueue = ByteArray(HAPTICS_QUEUE_SIZE)
    private var hapticsQueued = 0
    @Volatile private var hapticsWanted = false

    /** True while the fine haptics are being played by the controller's own actuators. */
    val playsHaptics: Boolean get() = hapticsWanted && connected

    fun start(triggers: Boolean, lights: Boolean, haptics: Boolean) {
        if (running || !available(context)) return
        synchronized(lock) {
            triggersEnabled = triggers
            lightsEnabled = lights
            triggersPending = true
        }
        hapticsWanted = haptics
        running = true
        worker = Thread({ run() }, "farsofa-dualsense").also { it.start() }
        if (haptics) hapticsWorker = Thread({ runHaptics() }, "farsofa-haptics").also { it.start() }
    }

    /** Lets go of the triggers and the motors, then closes. Returns once that is done or a second has passed. */
    fun stop() {
        if (!running) return
        running = false
        synchronized(lock) { lock.notifyAll() }
        hapticsWorker?.interrupt()
        try {
            hapticsWorker?.join(500)
            worker?.join(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        worker = null
        hapticsWorker = null
    }

    /** Right then left trigger, eleven bytes each, as the console sent them. */
    fun setTriggers(effects: ByteArray) {
        if (effects.size != 2 * DualSenseReport.TRIGGER_SIZE) return
        synchronized(lock) {
            if (triggers?.contentEquals(effects) == true) return
            triggers = effects.copyOf()
            triggersPending = true
            lock.notifyAll()
        }
    }

    /** The console's own strength setting for the triggers: 0 off, 1 strong, 2 medium, 3 weak. */
    fun setTriggerSetting(setting: Int) = synchronized(lock) {
        if (triggerSetting == setting) return
        triggerSetting = setting
        triggersPending = true
        lock.notifyAll()
    }

    /** The console's own strength setting for vibration, on the same scale. */
    fun setRumbleSetting(setting: Int) = synchronized(lock) { rumbleSetting = setting }

    /** Light bar colour as 0xRRGGBB; zero means the game has not chosen one. */
    fun setColour(rgb: Int) = synchronized(lock) {
        colour = rgb and 0xFFFFFF
        lightsPending = true
        lock.notifyAll()
    }

    /** Player number counted from zero. */
    fun setPlayer(index: Int) = synchronized(lock) {
        player = index
        lightsPending = true
        lock.notifyAll()
    }

    /** Rumble strengths, 0..255. Must be repeated while the effect lasts or it is stopped. */
    fun setMotors(left: Int, right: Int) = synchronized(lock) {
        motorTime = SystemClock.uptimeMillis()
        if (left == motorLeft && right == motorRight) return
        motorLeft = left
        motorRight = right
        motorsPending = true
        lock.notifyAll()
    }

    /** One packet of the console's haptics: 16-bit stereo at 3 kHz. Any thread. */
    fun pushHaptics(pcm: ByteArray) {
        val gain = synchronized(lock) { if (rumbleSetting == 0) 0f else TriggerStrength.factor(rumbleSetting) }
        val frames = pcm.size / 4
        synchronized(hapticsQueue) {
            for (frame in 0 until frames) {
                if (hapticsQueued + 2 > hapticsQueue.size) {
                    // Full: forget the oldest sound rather than play everything late.
                    val drop = 64
                    System.arraycopy(hapticsQueue, drop, hapticsQueue, 0, hapticsQueued - drop)
                    hapticsQueued -= drop
                }
                for (channel in 0 until 2) {
                    val at = frame * 4 + channel * 2
                    val sample = (pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)
                    hapticsQueue[hapticsQueued++] = eightBit(sample, gain)
                }
            }
        }
    }

    // --- State messages ---

    private fun run() {
        var attempts = 0
        try {
            while (running) {
                if (!connected) {
                    if (open()) {
                        attempts = 0
                        synchronized(lock) {
                            triggersPending = true
                            lightsPending = colour != null || player != null
                            motorsPending = motorsOwned
                            lightbarReleased = false
                        }
                    } else {
                        attempts++
                        pause(if (attempts <= 15) 2_000L else 10_000L)
                        continue
                    }
                }
                val wait = step()
                if (wait > 0) synchronized(lock) { if (running && !anythingPending()) lock.wait(wait) }
            }
        } catch (e: InterruptedException) {
            // stopping
        } finally {
            if (connected) {
                send(DualSenseReport.state(DualSenseReport.State(
                    triggers = DualSenseReport.releasedTriggers(),
                    motors = if (motorsOwned) Pair(0, 0) else null,
                )))
            }
            close()
        }
    }

    private fun anythingPending(): Boolean = triggersPending || lightsPending || motorsPending

    /** Sends whatever is due and returns how long the worker may sleep. */
    private fun step(): Long {
        val now = SystemClock.uptimeMillis()
        var release = false
        var change: DualSenseReport.State? = null
        var hadTriggers = false
        var nextWake = KEEP_ALIVE_MS

        synchronized(lock) {
            // The console never says "stop": the haptics just end. A rumble nobody renews is over.
            if (motorsOwned && (motorLeft != 0 || motorRight != 0) && now - motorTime > MOTOR_TIMEOUT_MS) {
                motorLeft = 0
                motorRight = 0
                motorsPending = true
            }

            val lightsKnown = lightsEnabled && (colour != null || player != null)
            val lightsDue = lightsKnown && (lightsPending || now - lastLightTime >= LIGHT_REPEAT_MS)
            if (lightsDue && !lightbarReleased) {
                release = true
            } else if (triggersPending || motorsPending || lightsDue) {
                var effects: ByteArray? = null
                if (triggersPending) {
                    effects = currentTriggers()
                    hadTriggers = true
                }
                var motors: Pair<Int, Int>? = null
                if (motorsPending) {
                    motors = Pair(motorLeft, motorRight)
                    motorsOwned = true
                }
                val index = player ?: 0
                change = DualSenseReport.State(
                    triggers = effects,
                    motors = motors,
                    colour = if (lightsDue) (colour?.takeIf { it != 0 } ?: DualSenseReport.playerColour(index)) else null,
                    playerLeds = if (lightsDue) DualSenseReport.playerLeds(index) else null,
                    brightness = if (lightsDue) brightness else null,
                )
                triggersPending = false
                motorsPending = false
                if (lightsDue) {
                    lightsPending = false
                    lastLightTime = now
                }
            }
            if (lightsKnown) nextWake = minOf(nextWake, LIGHT_REPEAT_MS)
            if (motorsOwned && (motorLeft != 0 || motorRight != 0)) nextWake = minOf(nextWake, 100L)
        }

        if (release) {
            if (send(DualSenseReport.releaseLightbar())) {
                synchronized(lock) { lightbarReleased = true }
                pause(300) // the controller needs a moment before it takes a colour
            }
            return 0
        }
        val state = change
        if (state != null) {
            if (!send(DualSenseReport.state(state))) markAllPending()
            // A burst of effects from the console becomes at most one message every few milliseconds.
            return if (hadTriggers) 8 else 1
        }
        if (now - lastSendTime >= KEEP_ALIVE_MS) send(DualSenseReport.keepAlive())
        return nextWake
    }

    /** The effect to apply now, weakened or removed according to the settings. Called with [lock] held. */
    private fun currentTriggers(): ByteArray {
        val effects = triggers
        if (!triggersEnabled || triggerSetting == 0 || effects == null) return DualSenseReport.releasedTriggers()
        val factor = TriggerStrength.factor(triggerSetting)
        val size = DualSenseReport.TRIGGER_SIZE
        val right = TriggerStrength.scaled(effects.copyOfRange(0, size), factor)
        val left = TriggerStrength.scaled(effects.copyOfRange(size, 2 * size), factor)
        return right + left
    }

    private fun markAllPending() = synchronized(lock) {
        triggersPending = true
        lightsPending = colour != null || player != null
        motorsPending = motorsOwned
        lightbarReleased = false
    }

    // --- Haptics ---

    /**
     * Plays the queued haptics at the pace the controller consumes them: 32 frames of 3 kHz sound per
     * message. The pace is a hair slower than that on purpose, so that any difference between the two
     * clocks piles up here, where it can be seen and trimmed, and not inside the controller.
     */
    private fun runHaptics() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val block = ByteArray(DualSenseReport.HAPTICS_PAYLOAD)
        var next = System.nanoTime()
        var flowing = false
        var emptyReads = 0
        var silentBlocks = 0
        var lastSetup = 0L
        try {
            while (running) {
                next += HAPTICS_PERIOD_NS
                val now = System.nanoTime()
                if (next > now) LockSupport.parkNanos(next - now) else next = now
                if (Thread.interrupted()) break
                if (!connected) {
                    synchronized(hapticsQueue) { hapticsQueued = 0 }
                    flowing = false
                    continue
                }

                var taken = 0
                synchronized(hapticsQueue) {
                    if (hapticsQueued > HAPTICS_QUEUE_LIMIT) {
                        val drop = (hapticsQueued - HAPTICS_QUEUE_LIMIT + 1) and 1.inv()
                        System.arraycopy(hapticsQueue, drop, hapticsQueue, 0, hapticsQueued - drop)
                        hapticsQueued -= drop
                    }
                    emptyReads = if (hapticsQueued == 0) emptyReads + 1 else 0
                    val whole = hapticsQueued >= block.size
                    // A short effect after silence goes out at once, and so does the tail of a longer one.
                    val partial = hapticsQueued in 1 until block.size && (!flowing || emptyReads >= 2)
                    if (whole || partial) {
                        taken = minOf(hapticsQueued, block.size)
                        System.arraycopy(hapticsQueue, 0, block, 0, taken)
                        System.arraycopy(hapticsQueue, taken, hapticsQueue, 0, hapticsQueued - taken)
                        hapticsQueued -= taken
                    }
                }
                for (index in taken until block.size) block[index] = 0
                if (taken > 0) flowing = true else if (emptyReads >= 2) flowing = false

                // Silence still fills the controller's queue, so after a moment of it nothing is sent at all.
                silentBlocks = if (block.all { it.toInt() == 0 }) silentBlocks + 1 else 0
                if (silentBlocks > HAPTICS_SILENT_BLOCKS) continue

                val time = SystemClock.uptimeMillis()
                if (time - lastSetup >= 2_000L) {
                    lastSetup = time
                    send(DualSenseReport.hapticsSetup())
                }
                if (!send(DualSenseReport.haptics(block, hapticsBlock))) markAllPending()
                hapticsBlock = (hapticsBlock + 1) and 0xFF
            }
        } finally {
            if (connected) send(DualSenseReport.haptics(ByteArray(DualSenseReport.HAPTICS_PAYLOAD), hapticsBlock))
        }
    }

    // --- Transport ---

    private fun open(): Boolean {
        try {
            // The list is replaced by each call, so all three classes go in together every time.
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/bluetooth/BluetoothHidHost;", "Landroid/bluetooth/BluetoothAdapter;", "Landroid/bluetooth/BluetoothDevice;",
            )
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
            if (!adapter.isEnabled) return false

            if (proxy == null) {
                val ready = CountDownLatch(1)
                var arrived: BluetoothProfile? = null
                var abandoned = false
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
                        synchronized(ready) {
                            if (abandoned) adapter.closeProfileProxy(HID_HOST, service) else arrived = service
                        }
                        ready.countDown()
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        connected = false
                        proxy = null
                    }
                }
                if (!adapter.getProfileProxy(context, listener, HID_HOST)) return false
                if (!ready.await(4, TimeUnit.SECONDS)) {
                    synchronized(ready) { abandoned = true }
                    arrived?.let { adapter.closeProfileProxy(HID_HOST, it) }
                    return false
                }
                proxy = synchronized(ready) { arrived } ?: return false
            }

            val service = proxy ?: return false
            val pad = service.connectedDevices.firstOrNull { isDualSense(it) } ?: return false
            val method = findSendData(service) ?: run {
                Log.w(TAG, "This system has no way to send a controller a message")
                return false
            }
            device = pad
            sendData = method
            connected = true
            Log.i(TAG, "DualSense link open")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the DualSense link: $e")
            return false
        }
    }

    private fun isDualSense(candidate: BluetoothDevice): Boolean {
        val name = try { candidate.name?.lowercase() } catch (e: SecurityException) { null } ?: return false
        // "Wireless Controller" on its own is a DualSense too; with a brand in front it is something else.
        return name.contains("dualsense") || name == "wireless controller"
    }

    /** The message goes out as text, two hexadecimal digits per byte, which is how this service takes it. */
    private fun findSendData(service: BluetoothProfile): Method? {
        var type: Class<*>? = service.javaClass
        while (type != null) {
            type.declaredMethods.firstOrNull {
                it.name == "sendData" && it.parameterTypes.contentEquals(arrayOf(BluetoothDevice::class.java, String::class.java))
            }?.let { it.isAccessible = true; return it }
            type = type.superclass
        }
        return null
    }

    /** Numbers, seals and sends. True means the system took the message, not that the controller did. */
    private fun send(report: ByteArray): Boolean = synchronized(sendLock) {
        val service = proxy
        val pad = device
        val method = sendData
        if (service == null || pad == null || method == null || !connected) return false
        DualSenseReport.seal(report, sequence)
        sequence = (sequence + 1) and 0x0F
        val accepted = try {
            method.invoke(service, pad, hex(report)) == true
        } catch (e: Exception) {
            false
        }
        if (accepted) {
            lastSendTime = SystemClock.uptimeMillis()
        } else {
            // The controller went away. The worker looks for it again.
            connected = false
            device = null
        }
        accepted
    }

    private fun close() {
        connected = false
        val service = proxy
        proxy = null
        device = null
        sendData = null
        if (service != null) {
            try {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.closeProfileProxy(HID_HOST, service)
            } catch (e: Exception) {
                // nothing left to release
            }
        }
    }

    private fun pause(ms: Long) = synchronized(lock) { if (running) lock.wait(ms) }

    companion object {
        private const val TAG = "FarsofaPad"

        /** `BluetoothProfile.HID_HOST`, which the public SDK leaves out. */
        private const val HID_HOST = 4

        private const val KEEP_ALIVE_MS = 4_000L
        private const val LIGHT_REPEAT_MS = 500L
        private const val MOTOR_TIMEOUT_MS = 300L

        private const val HAPTICS_QUEUE_SIZE = 1024
        private const val HAPTICS_QUEUE_LIMIT = 320
        private const val HAPTICS_SILENT_BLOCKS = 24

        /** 32 frames at 3 kHz are 10.667 ms; this is 0.05 % longer. */
        private const val HAPTICS_PERIOD_NS = 10_672_000L

        /** The permission exists from Android 12 on, and so does everything else this needs. */
        fun available(context: Context): Boolean = Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        /** A 16-bit haptics sample as the controller's signed byte: half of full scale already drives it fully. */
        fun eightBit(sample: Int, gain: Float): Byte {
            val scaled = sample * gain / 16384f * 127f
            return scaled.toInt().coerceIn(-127, 127).toByte()
        }

        fun hex(bytes: ByteArray): String {
            val digits = "0123456789ABCDEF"
            val out = CharArray(bytes.size * 2)
            for (index in bytes.indices) {
                val value = bytes[index].toInt() and 0xFF
                out[index * 2] = digits[value shr 4]
                out[index * 2 + 1] = digits[value and 0x0F]
            }
            return String(out)
        }
    }
}

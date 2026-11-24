// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.InputDevice

/**
 * Reads the gyroscope and accelerometer of the controller itself. The headset has sensors of its own,
 * and those are the ones Android hands out by default, so a game would be aimed with the player's
 * head; the controller's are reached through its input device.
 *
 * [onSample] gets angular speed in rad/s, acceleration in g and the time of the reading in
 * microseconds, on this class's own thread. [onLost] is called when the controller goes away.
 */
class PadMotion(
    context: Context,
    private val onSample: (gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, timeUs: Long) -> Unit,
    private val onLost: () -> Unit,
) : SensorEventListener, InputManager.InputDeviceListener {
    private val inputManager = context.applicationContext.getSystemService(Context.INPUT_SERVICE) as InputManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var sensors: SensorManager? = null
    private val gyro = FloatArray(3)
    private val accel = floatArrayOf(0f, 1f, 0f)
    private var haveAccel = false

    fun start() {
        if (Build.VERSION.SDK_INT < 31 || thread != null) return
        val started = HandlerThread("farsofa-motion").also { it.start() }
        thread = started
        handler = Handler(started.looper).also {
            inputManager.registerInputDeviceListener(this, it)
            it.post { attach() }
        }
    }

    fun stop() {
        val running = thread ?: return
        inputManager.unregisterInputDeviceListener(this)
        handler?.post { detach() }
        running.quitSafely()
        // A reading already on its way must be done before the session it is headed for goes away.
        try { running.join(200) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        thread = null
        handler = null
    }

    private fun attach() {
        if (Build.VERSION.SDK_INT < 31) return
        detach()
        val devices = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { it.supportsSource(InputDevice.SOURCE_GAMEPAD) && it.supportsSource(InputDevice.SOURCE_SENSOR) }
        val device = devices.firstOrNull { it.name.contains("DualSense", ignoreCase = true) } ?: devices.firstOrNull()
        val manager = device?.sensorManager
        val gyroscope = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || gyroscope == null || accelerometer == null) {
            onLost()
            return
        }
        haveAccel = false
        sensors = manager
        manager.registerListener(this, gyroscope, SAMPLE_PERIOD_US, handler)
        manager.registerListener(this, accelerometer, SAMPLE_PERIOD_US, handler)
    }

    private fun detach() {
        sensors?.unregisterListener(this)
        sensors = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                event.values.copyInto(gyro, 0, 0, 3)
                if (haveAccel) onSample(gyro[0], gyro[1], gyro[2], accel[0], accel[1], accel[2], event.timestamp / 1000)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                for (axis in 0 until 3) accel[axis] = event.values[axis] / SensorManager.GRAVITY_EARTH
                haveAccel = true
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    // A controller that was switched off and on again comes back as a new device, and the old sensors
    // simply go quiet. Its parts are still being put together when the first notice arrives.
    override fun onInputDeviceAdded(deviceId: Int) = reattachSoon()
    override fun onInputDeviceRemoved(deviceId: Int) = reattachSoon()
    override fun onInputDeviceChanged(deviceId: Int) = Unit

    private fun reattachSoon() {
        val current = handler ?: return
        current.removeCallbacksAndMessages(REATTACH)
        current.postDelayed({ attach() }, REATTACH, 500)
    }

    private companion object {
        const val SAMPLE_PERIOD_US = 4000
        val REATTACH = Any()
    }
}

// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.stream

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.farsofa.vr.R
import app.farsofa.vr.input.PadState

/** Streams one console in a flat panel. Used where there is no immersive mode. */
class StreamActivity : ComponentActivity(), StreamSession.Callbacks, SurfaceHolder.Callback {
    private lateinit var stream: StreamSession
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val surfaceView = SurfaceView(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setText(R.string.stream_connecting)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        })

        stream = StreamSession(this, intent, this)
        if (!stream.isOpen) {
            fail(getString(R.string.stream_failed_start))
            return
        }
        surfaceView.holder.addCallback(this)
    }

    override fun onStop() {
        super.onStop()
        close()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (stream.isOpen && !stream.attachSurface(holder.surface)) fail(getString(R.string.stream_failed_start))
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = close()

    private fun close() {
        if (stream.isOpen) stream.release()
    }

    private fun fail(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
        close()
        handler.postDelayed({ finish() }, 4000)
    }

    override fun onStreamConnected() {
        status.setText(R.string.stream_exit_hint)
        handler.postDelayed({ status.visibility = View.GONE }, 4000)
    }

    override fun onLoginPinRequest(incorrect: Boolean) {
        val field = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this)
            .setTitle(if (incorrect) R.string.stream_login_pin_incorrect else R.string.stream_login_pin_title)
            .setView(field)
            .setPositiveButton(android.R.string.ok) { _, _ -> stream.setLoginPin(field.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    override fun onStreamEnded(error: String?) {
        if (isFinishing) return
        if (error != null) fail(error) else finish()
    }

    override fun onLeaveRequested() = finish()

    // The flat mode has no controls of its own, so a PS tap goes to the console as usual.
    override fun onMenuToggle() = stream.tap(PadState.PS)

    override fun onMenuPad(buttons: Int) = Unit

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        stream.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        stream.onMotionEvent(event) || super.dispatchGenericMotionEvent(event)
}

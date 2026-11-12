package app.nodstop.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.nodstop.trip.TripService
import app.nodstop.ui.AlarmScreen

/**
 * Shown over the lock screen while ringing. Stops the ring with: a 1 s hold on the big "I'm awake" area,
 * holding volume down for 1.5 s, or the "I'm awake" button on the alarm notification (for when you're
 * using the phone). A tap, a pocket brush, back, or a quick volume press does nothing.
 */
class AlarmActivity : ComponentActivity() {
    private var volumeDownSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleAck(intent)) return
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ring by RingService.state.collectAsState()
                AlarmScreen(ring = ring, holdMs = 1_000, onAwake = { awake(this); finish() }, onClose = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAck(intent)
    }

    /** The notification's "I'm awake" button opens this activity with EXTRA_ACK. */
    private fun handleAck(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_ACK, false) != true) return false
        intent.removeExtra(EXTRA_ACK)
        if (RingService.state.value != null) awake(this)
        finish()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) volumeDownSince = SystemClock.elapsedRealtime()
            else if (volumeDownSince > 0 && SystemClock.elapsedRealtime() - volumeDownSince >= VOLUME_HOLD_MS) {
                volumeDownSince = 0
                if (RingService.state.value != null) { awake(this); finish() }
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { volumeDownSince = 0; return true }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        const val EXTRA_ACK = "ack"
        private const val VOLUME_HOLD_MS = 1_500L

        /** "I'm awake": stop the ring and tell the trip; if the trip process was killed, resume it. */
        fun awake(ctx: Context) {
            val wasTest = RingService.state.value?.test == true
            RingService.stop(ctx)
            val svc = TripService.instance
            when {
                svc != null -> svc.ack()
                !wasTest -> TripService.resumeAfterColdRing(ctx)
            }
        }
    }
}

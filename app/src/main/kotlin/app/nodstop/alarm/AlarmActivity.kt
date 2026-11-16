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
import androidx.compose.runtime.mutableStateOf
import app.nodstop.notify.MessageStore
import app.nodstop.trip.TripService
import app.nodstop.ui.AfterWakeScreen
import app.nodstop.ui.AlarmScreen
import app.nodstop.ui.ShareLine

/**
 * Shown over the lock screen while ringing. Stops the ring with: a 1 s hold on the big "I'm awake" area,
 * holding volume down for 1.5 s, or the "I'm awake" button on the alarm notification (for when you're
 * using the phone). A tap, a pocket brush, back, or a quick volume press does nothing.
 */
class AlarmActivity : ComponentActivity() {
    private var volumeDownSince = 0L
    /** "I'm awake" was held here and there's something to read: show it until Done (DESIGN §10). */
    private val acked = mutableStateOf(false)

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
                val current = ring
                if (current == null && acked.value) {
                    AfterWakeScreen(onShare = { ShareLine.share(this, it) }, onDone = { close() })
                } else {
                    AlarmScreen(ring = current, holdMs = 1_000, onAwake = { awakeHere() }, onClose = { close() })
                }
            }
        }
    }

    /** Held "I'm awake" on this screen: keep it open to read the briefing if there is one. */
    private fun awakeHere() {
        awake(this, clearMessages = false)
        val snap = MessageStore.instance.snapshot()
        if (snap.active && (snap.messages.isNotEmpty() || snap.otherNotifications > 0)) acked.value = true else close()
    }

    /** Closing the alarm screen forgets the messages it showed (W3). */
    private fun close() {
        MessageStore.instance.clear()
        acked.value = false
        finish()
    }

    override fun onDestroy() {
        if (acked.value && !isChangingConfigurations) MessageStore.instance.clear()
        super.onDestroy()
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
        fun awake(ctx: Context, clearMessages: Boolean = true) {
            val wasTest = RingService.state.value?.test == true
            RingService.stop(ctx)
            // The alarm screen is closing: forget the messages it showed (DESIGN §10, W3).
            if (clearMessages) MessageStore.instance.clear()
            val svc = TripService.instance
            when {
                svc != null -> svc.ack()
                !wasTest -> TripService.resumeAfterColdRing(ctx)
            }
        }
    }
}

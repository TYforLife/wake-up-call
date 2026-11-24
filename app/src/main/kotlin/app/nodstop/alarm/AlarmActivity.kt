package app.nodstop.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import app.nodstop.openIfPossible
import app.nodstop.App
import app.nodstop.Notifications
import app.nodstop.Shortcuts
import app.nodstop.brief.Digests
import app.nodstop.notify.MessageStore
import app.nodstop.notify.PanelText
import app.nodstop.ui.Readiness
import android.provider.Settings
import androidx.compose.runtime.remember
import app.nodstop.trip.TripService
import app.nodstop.ui.ShareLine
import app.nodstop.ui.v2.AlarmAwakeHost
import app.nodstop.ui.v2.AlarmRingHost

/**
 * Shown over the lock screen while ringing. Stops the ring with: a 1 s hold on the big "I'm awake" area,
 * holding volume down for 1.5 s, or the "I'm awake" button on the alarm notification (for when you're
 * using the phone). A tap, a pocket brush, back, or a quick volume press does nothing.
 */
class AlarmActivity : ComponentActivity() {
    private var volumeDownSince = 0L
    /** "I'm awake" was held here and there's something to read: show it until Done (DESIGN §10). */
    private val acked = mutableStateOf(false)
    /** Whether the ring being answered was Debug's test ring (the awake screen says so). */
    private val lastRingTest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleAck(intent)) return
        // Opened from "What you missed": the messages only show after unlocking.
        val missed = intent?.getBooleanExtra(EXTRA_MISSED, false) == true && RingService.state.value == null
        if (missed) acked.value = true
        setShowWhenLocked(!missed)
        setTurnScreenOn(!missed)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            val ring by RingService.state.collectAsState()
            val current = ring
            if (current != null) lastRingTest.value = current.test
            if (current == null && acked.value) {
                // v2 (PLAN Run 8, M4): what to do now, then what you missed, then Done.
                val access = remember { Readiness.notificationAccessOk(this) }
                AlarmAwakeHost(
                    test = lastRingTest.value, accessOk = access,
                    onShare = { ShareLine.share(this, it) },
                    onFix = {
                        openIfPossible(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        close()
                    },
                    onDone = { close() },
                    // Passed your stop (BACKLOG F23): Plan a trip back from here, after unlocking.
                    onBack = { name, pos -> afterUnlock { openIfPossible(Shortcuts.planIntent(this, name, pos)); close() } },
                )
            } else if (current != null) {
                AlarmRingHost(current, onAwake = { awakeHere() })
            } else {
                // Opened with nothing ringing and nothing to read: nothing to show.
                androidx.compose.runtime.LaunchedEffect(Unit) { close() }
            }
        }
    }

    /** Runs [then] once the phone is unlocked: at once if it is, after you unlock if not. */
    private fun afterUnlock(then: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { then(); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = then()
        })
    }

    /** Held "I'm awake" on this screen: "You're awake" always follows (PLAN U5), until Done. */
    private fun awakeHere() {
        RingDiag.acked("hold")
        awake(this, clearMessages = false)
        acked.value = true
    }

    /** Closing the alarm screen forgets the messages it showed (W3). */
    private fun close() {
        MessageStore.instance.clear()
        Notifications.cancelMissed(this)
        acked.value = false
        finish()
    }

    override fun onDestroy() {
        if (acked.value && !isChangingConfigurations) MessageStore.instance.clear()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (RingService.state.value != null) RingDiag.shown()
        // The alarm screen is up: the helper window that let it open over another app can go (PLAN A1).
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handleAck(intent)) return
        if (intent.getBooleanExtra(EXTRA_MISSED, false) && RingService.state.value == null) {
            setShowWhenLocked(false)
            acked.value = true
        }
    }

    /** The notification's "I'm awake" button opens this activity with EXTRA_ACK. */
    private fun handleAck(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_ACK, false) != true) return false
        intent.removeExtra(EXTRA_ACK)
        if (RingService.state.value != null) { RingDiag.acked("notification"); awakeElsewhere(this) }
        finish()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) volumeDownSince = SystemClock.elapsedRealtime()
            else if (volumeDownSince > 0 && SystemClock.elapsedRealtime() - volumeDownSince >= VOLUME_HOLD_MS) {
                volumeDownSince = 0
                if (RingService.state.value != null) { RingDiag.acked("volume key"); awakeElsewhere(this); finish() }
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
        const val EXTRA_MISSED = "missed"

        /**
         * Acked without seeing "You're awake" (notification button, volume key): keep the messages and post a
         * silent "What you missed (N chats)" that reopens it (PLAN U5). Nothing to show: nothing posted.
         */
        fun awakeElsewhere(ctx: Context) {
            awake(ctx, clearMessages = false)
            val snap = MessageStore.instance.snapshot()
            val d = Digests.build(snap.messages, snap.otherNotifications, App.instance.settings.vipNames.value)
            val title = if (snap.active) PanelText.missedTitle(d) else null
            if (title != null) Notifications.missed(ctx, title, PanelText.summary(true, d)) else MessageStore.instance.clear()
        }
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

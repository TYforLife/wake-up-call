package app.nodstop.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nodstop.App
import app.nodstop.Notifications
import app.nodstop.isolated
import app.nodstop.engine.Config
import app.nodstop.trip.TripService

/**
 * Rule 2: rings unconditionally. Never add a condition here that could skip or defer the ring,
 * even when the process was cold-started and there is no trip state.
 *
 * PLAN Run 10, A1: the ring notification (full screen, insistent, vibrating on its channel) is posted here first,
 * then [RingService] takes it over (same ID) and plays the ring. If the service can't start, the alarm is still
 * on screen and buzzing, and it rings again in a minute. Still the one ring path: receiver, then service.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = App.instance
        val info = RingService.info()
        isolated("ring notification") {
            Notifications.ensureAlarmChannel(context)
            context.getSystemService(NotificationManager::class.java)
                .notify(Notifications.ID_RING, Notifications.ring(context, info.legName, info.reason, info.test))
        }
        app.scheduler.markFired()
        val started = isolated("ring service start", false) { RingService.start(context); true }
        // Keep the dead-man's switch: with no service to re-ring, the system rings again in a minute.
        if (!started && !info.test && TripService.instance == null) {
            app.scheduler.set(System.currentTimeMillis() + (Config().reringS * 1000).toLong())
        }
        TripService.instance?.onRang()
    }

    companion object {
        const val ACTION_FIRE = "app.nodstop.ALARM_FIRE"
    }
}

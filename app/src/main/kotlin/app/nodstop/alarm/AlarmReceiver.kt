package app.nodstop.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nodstop.App
import app.nodstop.trip.TripService

/**
 * Rule 2: rings unconditionally. Never add a condition here that could skip or defer the ring,
 * even when the process was cold-started and there is no trip state.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RingService.start(context)
        App.instance.scheduler.markFired()
        TripService.instance?.onRang()
    }

    companion object {
        const val ACTION_FIRE = "app.nodstop.ALARM_FIRE"
    }
}

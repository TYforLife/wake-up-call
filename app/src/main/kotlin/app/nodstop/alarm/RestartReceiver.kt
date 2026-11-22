package app.nodstop.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nodstop.isolated
import app.nodstop.App
import app.nodstop.Notifications
import app.nodstop.data.Restart
import app.nodstop.trip.TripService

/**
 * After an app update or a phone restart (PLAN Run 10, A1): a trip that was running is not lost. Its alarm is set
 * again at once (a restart wipes system alarms; one that passed meanwhile rings now), then tracking resumes.
 * Android may refuse to start location tracking from here, so a notification offers it in one tap as well.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED && intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = App.instance
        val trip = app.trips.load()
        val plan = Restart.plan(trip, app.scheduler.scheduledAtMs, System.currentTimeMillis())
        if (plan.dropTrip) { app.trips.clear(); app.scheduler.cancel(); return }
        if (!plan.resume || trip == null) return
        plan.alarmAtMs?.let { app.scheduler.set(it) }
        val why = if (intent.action == Intent.ACTION_BOOT_COMPLETED) "the phone restarted" else "the app was updated"
        isolated("trip log after restart") {
            app.traces.continueTrip(trip.traceFile)
            app.traces.note(System.currentTimeMillis(), "$why during the trip: alarm set again, resuming")
        }
        Notifications.tripPaused(context, trip.currentLeg?.name)
        if (TripService.instance == null) TripService.resumeAfterRestart(context)
    }
}

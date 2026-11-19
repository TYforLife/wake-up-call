package app.nodstop.trip

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nodstop.App
import app.nodstop.Notifications
import app.nodstop.engine.LatLng

/**
 * The per-leg "How was that ring's timing?" notification's buttons (optional; PLAN U3 feedback). Each answer
 * tunes that stop's lead (too early → later next time, too late → earlier, 30–90 s) and goes in the trip log.
 */
class RateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val value = intent.getStringExtra(EXTRA_VALUE) ?: return
        val file = intent.getStringExtra(EXTRA_FILE) ?: return
        val leg = intent.getIntExtra(EXTRA_LEG, -1).takeIf { it >= 0 } ?: return
        val app = App.instance
        app.traces.rate(file, System.currentTimeMillis(), leg, value)
        if (intent.hasExtra(EXTRA_LAT)) {
            app.learned.rate(LatLng(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LNG, 0.0)), value)
        }
        // Answered here: the end-of-trip card doesn't ask about this leg again.
        app.settings.pendingRating.value?.takeIf { it.traceFile == file }?.let { app.settings.setPendingRating(it.without(leg)) }
        context.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_RATE)
    }

    companion object {
        const val ACTION_RATE = "app.nodstop.RATE"
        const val EXTRA_VALUE = "value"
        const val EXTRA_FILE = "file"
        const val EXTRA_LEG = "leg"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
    }
}

package app.nodstop.notify

import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import app.nodstop.App

/** Applies [DndRestore] decisions. Needs the rider's Do Not Disturb access; without it, does nothing. */
object DndController {
    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun granted(ctx: Context): Boolean = nm(ctx).isNotificationPolicyAccessGranted

    /**
     * Quiet trip follows the screen and the ring (PLAN Run 10): on while a trip runs with the screen off, off
     * while you use the phone, and off for a ring. Called on screen changes, every trip tick, and at a ring.
     */
    fun update(
        ctx: Context,
        tripRunning: Boolean = app.nodstop.trip.TripService.instance != null,
        screenOn: Boolean = ctx.getSystemService(PowerManager::class.java).isInteractive,
        ringing: Boolean = app.nodstop.alarm.RingService.isRinging || app.nodstop.alarm.RingService.state.value != null,
    ) {
        val settings = App.instance.settings
        val enabled = settings.dndDuringTrips.value
        val wanted = DndRestore.wanted(enabled, tripRunning, screenOn, ringing)
        apply(ctx, DndRestore.update(wanted, enabled, granted(ctx), nm(ctx).currentInterruptionFilter, settings.dndRecord()))
    }

    /** A ring is starting: lift quiet trip first, so nothing of ours stands between you and the alarm. */
    fun onRingStart(ctx: Context) = runCatching { update(ctx, ringing = true) }

    fun onTripStart(ctx: Context) {
        val settings = App.instance.settings
        val d = DndRestore.onTripStart(settings.dndDuringTrips.value, granted(ctx), nm(ctx).currentInterruptionFilter, settings.dndRecord())
        apply(ctx, d)
    }

    fun onTripEnd(ctx: Context) {
        val settings = App.instance.settings
        apply(ctx, DndRestore.onTripEnd(nm(ctx).currentInterruptionFilter, settings.dndRecord()))
    }

    private fun apply(ctx: Context, d: DndRestore.Decision) {
        // Save first: if we die between the two, the next start can still restore.
        App.instance.settings.setDndRecord(d.record)
        val filter = d.setFilter ?: return
        if (granted(ctx)) runCatching { nm(ctx).setInterruptionFilter(filter) }
    }
}

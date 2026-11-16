package app.nodstop.notify

import android.app.NotificationManager
import android.content.Context
import app.nodstop.App

/** Applies [DndRestore] decisions. Needs the rider's Do Not Disturb access; without it, does nothing. */
object DndController {
    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun granted(ctx: Context): Boolean = nm(ctx).isNotificationPolicyAccessGranted

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

package app.nodstop.alarm

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.VibratorManager
import app.nodstop.App
import app.nodstop.isolated

/** Applies [RingMode] decisions (PLAN R1). Changing Mute or Do Not Disturb needs Do Not Disturb access. */
object RingModeController {
    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
    private fun audio(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    // Read at the ring's start (RingService.begin): a reading that fails gets the usual value, never a stopped ring.
    fun granted(ctx: Context) = isolated("Do Not Disturb access read", false) { nm(ctx).isNotificationPolicyAccessGranted }
    fun ringer(ctx: Context) = isolated("ringer read", RingMode.RINGER_NORMAL) { audio(ctx).ringerMode }
    fun filter(ctx: Context) = isolated("Do Not Disturb read", RingMode.FILTER_ALL) { nm(ctx).currentInterruptionFilter }
    private fun hasVibrator(ctx: Context) =
        isolated("vibrator read", true) { ctx.getSystemService(VibratorManager::class.java).defaultVibrator.hasVibrator() }

    /** At the ring: switch if needed, and say whether it can buzz (decided on the settings before the switch). */
    fun onRingStart(ctx: Context): RingMode.Buzz {
        val ringer = ringer(ctx)
        val filter = filter(ctx)
        val granted = granted(ctx)
        val buzz = RingMode.buzz(ringer, filter, granted, hasVibrator(ctx))
        apply(ctx, RingMode.onRingStart(ringer, filter, granted, App.instance.settings.ringModeRecord()))
        return buzz
    }

    /** After the ring, and on every app start (undoes a switch a crash left behind). */
    fun onRingEnd(ctx: Context) {
        apply(ctx, RingMode.onRingEnd(ringer(ctx), filter(ctx), App.instance.settings.ringModeRecord()))
    }

    private fun apply(ctx: Context, d: RingMode.Decision) {
        // Save first: if we die between the two, the next start can still put it back.
        App.instance.settings.setRingModeRecord(d.record)
        if (!granted(ctx)) return
        // Restoring: the ringer first (Mute can itself turn on a DND mode on some phones), then the filter.
        // Each change on its own: Android can still refuse one (access revoked a moment ago: SecurityException).
        d.setFilter?.let { f -> if (d.record != null) isolated("Do Not Disturb change") { nm(ctx).setInterruptionFilter(f) } }
        d.setRinger?.let { r -> isolated("ringer change") { audio(ctx).ringerMode = r } }
        d.setFilter?.let { f -> if (d.record == null) isolated("Do Not Disturb change") { nm(ctx).setInterruptionFilter(f) } }
    }
}

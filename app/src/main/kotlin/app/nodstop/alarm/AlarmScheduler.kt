package app.nodstop.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.nodstop.ui.MainActivity

/**
 * The ONE system alarm (rule 1, DESIGN §2). One fixed request code, so every set() replaces the last.
 * "Ring now" is set(now).
 */
class AlarmScheduler(private val ctx: Context) {
    private val am = ctx.getSystemService(AlarmManager::class.java)
    private val prefs = ctx.getSharedPreferences("alarm", Context.MODE_PRIVATE)

    private fun receiverPi(): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQUEST_CODE, Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun showPi(): PendingIntent = PendingIntent.getActivity(
        ctx, REQUEST_CODE + 1, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The time currently scheduled, as far as this app knows (survives process death). */
    val scheduledAtMs: Long? get() = prefs.getLong(KEY_AT, 0L).takeIf { it > 0 }

    fun set(atMs: Long) {
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, showPi()), receiverPi())
            prefs.edit().putLong(KEY_AT, atMs).apply()
        } catch (e: SecurityException) {
            Log.e("Nodstop", "exact alarm refused", e)
        }
    }

    fun cancel() {
        am.cancel(receiverPi())
        prefs.edit().remove(KEY_AT).apply()
    }

    /** Called when the alarm fires: it is no longer scheduled. */
    fun markFired() { prefs.edit().remove(KEY_AT).apply() }

    private companion object {
        const val REQUEST_CODE = 4100
        const val KEY_AT = "scheduled_at"
    }
}

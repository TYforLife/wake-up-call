package app.nodstop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.nodstop.alarm.AlarmActivity
import app.nodstop.ui.MainActivity

object Notifications {
    const val CH_TRIP = "trip"
    const val CH_HEADS_UP = "heads_up"
    const val CH_ALARM = "alarm"

    const val ID_TRIP = 1
    const val ID_RING = 2
    const val ID_HEADS_UP = 3

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_TRIP, "Trip in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Distance and ETA while a trip runs"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_HEADS_UP, "Heads-up (5 min out)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "One gentle vibration about 5 minutes before the alarm"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Wake-up alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The alarm itself. Silent channel: the app plays the sound and vibration."
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun trip(ctx: Context, text: String): Notification =
        Notification.Builder(ctx, CH_TRIP)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Nodstop · trip running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(openApp(ctx))
            .build()

    fun ring(ctx: Context, legName: String?): Notification {
        val full = PendingIntent.getActivity(
            ctx, 11, Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Wake up! Your stop is close")
            .setContentText(legName?.let { "Get off at $it" } ?: "Hold \"I'm awake\" to stop")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .build()
    }

    fun headsUp(ctx: Context, legName: String?) {
        val n = Notification.Builder(ctx, CH_HEADS_UP)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("About 5 minutes out")
            .setContentText(legName?.let { "Next: $it" } ?: "Your stop is coming up")
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .setContentIntent(openApp(ctx))
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_HEADS_UP, n) }
    }
}

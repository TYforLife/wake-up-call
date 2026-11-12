package app.nodstop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import app.nodstop.alarm.AlarmActivity
import app.nodstop.engine.AlarmReason
import app.nodstop.engine.Phase
import app.nodstop.trip.TripService
import app.nodstop.ui.MainActivity
import kotlin.math.roundToInt

object Notifications {
    const val CH_TRIP = "trip"
    const val CH_AT_STOP = "at_stop"
    const val CH_ALARM = "alarm"

    const val ID_TRIP = 1
    const val ID_RING = 2
    const val ID_AT_STOP = 3

    /** Android 16 Live Updates: promoted to the lock screen and status bar. */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("heads_up") // v0's 5-min heads-up, replaced by the at-stop nudge
        nm.createNotificationChannel(
            NotificationChannel(CH_TRIP, "Trip progress", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Distance to your stop while a trip runs (silent)"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_AT_STOP, "At your stop", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "One light buzz when the bus is at your stop"
                setSound(null, null)
                enableVibration(false) // the app buzzes once itself, through Do Not Disturb
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

    fun tripStarting(ctx: Context): Notification =
        Notification.Builder(ctx, CH_TRIP)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Nodstop · starting")
            .setContentText("Getting GPS…")
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .build()

    /** The glanceable trip notification: phase, distance, progress bar. */
    fun trip(ctx: Context, ui: TripService.TripUi): Notification {
        val s = ui.status
        val leg = "Leg ${ui.legIndex + 1}/${ui.legNames.size}"
        val title = when (s.phase) {
            Phase.WAITING -> "Waiting for the bus → ${ui.legName}"
            Phase.RIDING -> "On the bus → ${ui.legName}"
            Phase.AWAKE -> "Get off at ${ui.legName}"
            else -> TripService.phaseLabel(ui)
        }
        val text = "$leg · ${TripService.summary(ui)}"
        val start = s.startDistanceM
        val d = s.distanceM
        val progress = if (start != null && d != null && start > 0) ((1 - d / start) * 100).roundToInt().coerceIn(0, 100) else null
        val b = Notification.Builder(ctx, CH_TRIP)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(openApp(ctx))
        if (Build.VERSION.SDK_INT >= 36) {
            d?.let { b.setShortCriticalText(TripService.distance(it)) }
            val style = Notification.ProgressStyle()
                .setProgressTrackerIcon(Icon.createWithResource(ctx, R.drawable.ic_tile))
            if (progress != null) style.setProgress(progress) else style.setProgressIndeterminate(s.phase != Phase.AWAKE)
            b.setStyle(style)
            b.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        } else if (progress != null) {
            b.setProgress(100, progress, false)
        }
        return b.build()
    }

    fun atStop(ctx: Context, legName: String?) {
        val n = Notification.Builder(ctx, CH_AT_STOP)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("You're at ${legName ?: "your stop"}")
            .setContentText("Get off now")
            .setAutoCancel(true)
            .setTimeoutAfter(3 * 60_000L)
            .setContentIntent(openApp(ctx))
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_AT_STOP, n) }
    }

    fun reasonText(reason: AlarmReason?): String? = when (reason) {
        AlarmReason.NO_GPS -> "No GPS yet: the app can't see where you are. Stay awake."
        AlarmReason.FAILSAFE -> "GPS stopped: your stop may be close. Check where you are."
        AlarmReason.NEXT_LEG -> "GPS stopped: check where you are."
        AlarmReason.OVERSHOOT -> "You passed your stop."
        else -> null
    }

    fun ring(ctx: Context, legName: String?, reason: AlarmReason?): Notification {
        val full = PendingIntent.getActivity(
            ctx, 11, Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // One tap when you're already using the phone (the ring shows as a banner, not full screen).
        val awake = PendingIntent.getActivity(
            ctx, 12,
            Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AlarmActivity.EXTRA_ACK, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (reason == AlarmReason.OVERSHOOT) "You passed your stop!" else "Wake up! Your stop is close"
        return Notification.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(reasonText(reason) ?: legName?.let { "Get off at $it" } ?: "Tap I'm awake to stop")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setColorized(true)
            .setColor(0xFFFFC94A.toInt())
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_tile), "I'm awake", awake).build())
            .build()
    }
}

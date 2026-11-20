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
import app.nodstop.engine.LatLng
import app.nodstop.trip.RateReceiver
import app.nodstop.engine.Phase
import app.nodstop.trip.TripService
import app.nodstop.ui.MainActivity
import app.nodstop.ui.v2.RingText
import app.nodstop.ui.v2.RingTextUi
import app.nodstop.ui.v2.Tokens
import app.nodstop.ui.v2.TripNoteText
import app.nodstop.ui.v2.hm
import kotlin.math.roundToInt

object Notifications {
    const val CH_TRIP = "trip"
    const val CH_AT_STOP = "at_stop"
    const val CH_ALARM = "alarm"

    const val ID_TRIP = 1
    const val ID_RING = 2
    const val ID_AT_STOP = 3
    const val ID_RATE = 4
    const val ID_MISSED = 5

    /** Android 16 Live Updates: promoted to the lock screen and status bar. */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    const val TRIP_STOP_LABEL = "Stop trip"

    /** The design's one accent ("trip is live") and its red, for notification tints. */
    private val ACCENT = Tokens.light.getValue("accent").toInt()
    private val DANGER = Tokens.light.getValue("danger").toInt()

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

    /** The trip notification's quiet-trip note (PLAN R2): null when off; the count when known. */
    fun quietLine(on: Boolean, held: Int?): String? = when {
        !on -> null
        held != null && held > 0 -> "Quiet trip: $held held"
        else -> "Quiet trip"
    }

    /** The glanceable trip notification (mockup `lock`): "<stop> in 6 min", the alarm time, an accent bar. */
    fun trip(ctx: Context, ui: TripService.TripUi, quiet: String? = null): Notification {
        val s = ui.status
        val note = TripNoteText.of(ui, ::hm, quiet)
        val d = s.distanceM
        val progress = note.progress
        val b = Notification.Builder(ctx, CH_TRIP)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(note.title)
            .setContentText(note.text)
            .setColor(ACCENT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(openApp(ctx))
            // The "island" (PLAN U2): stop the trip from the notification or the status-bar chip. Needs the
            // phone unlocked, so a pocket or a stranger can't cancel your alarm.
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_tile), TRIP_STOP_LABEL,
                    PendingIntent.getService(ctx, 21, TripService.stopIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                ).setAuthenticationRequired(true).build(),
            )
        if (Build.VERSION.SDK_INT >= 36) {
            d?.let { b.setShortCriticalText(TripService.distance(it)) }
            val style = Notification.ProgressStyle()
                .setProgressTrackerIcon(Icon.createWithResource(ctx, R.drawable.ic_tile))
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(ACCENT)))
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

    /** After "I'm awake" on a stop's ring: optional, silent, gone after 15 min (PLAN U3 feedback). */
    fun rateLeg(ctx: Context, traceFile: String, leg: Int, legName: String?, dest: LatLng?) {
        fun action(label: String, value: String, code: Int): Notification.Action {
            val i = Intent(ctx, RateReceiver::class.java).setAction(RateReceiver.ACTION_RATE)
                .putExtra(RateReceiver.EXTRA_VALUE, value).putExtra(RateReceiver.EXTRA_FILE, traceFile)
                .putExtra(RateReceiver.EXTRA_LEG, leg)
            if (dest != null) i.putExtra(RateReceiver.EXTRA_LAT, dest.lat).putExtra(RateReceiver.EXTRA_LNG, dest.lng)
            val pi = PendingIntent.getBroadcast(ctx, 4200 + code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action.Builder(null as Icon?, label, pi).build()
        }
        val n = Notification.Builder(ctx, CH_TRIP)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(RATE_TITLE)
            .setContentText(legName?.let { "$it · optional" } ?: "Optional")
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(15 * 60_000L)
            .addAction(action("Too early", "too_early", 0))
            .addAction(action("Good", "good", 1))
            .addAction(action("Too late", "too_late", 2))
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_RATE, n) }
    }

    /** "What you missed (N chats)": counts only (W3). Tap: "You're awake" after unlocking. Swipe: forget them. */
    fun missed(ctx: Context, title: String, counts: String) {
        val open = PendingIntent.getActivity(
            ctx, 4300, Intent(ctx, AlarmActivity::class.java).putExtra(AlarmActivity.EXTRA_MISSED, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val forget = PendingIntent.getBroadcast(
            ctx, 4301, Intent(ctx, app.nodstop.alarm.MissedReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CH_TRIP)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(counts)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setDeleteIntent(forget)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_MISSED, n) }
    }

    fun cancelMissed(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(ID_MISSED) }
    }

    const val RATE_TITLE = "How was the ring timing?"

    /** The ring notification's two lines: the ring screen's words (mockup `ring`). */
    fun ringText(legName: String?, reason: AlarmReason?, test: Boolean): RingTextUi =
        RingText.of(legName, reason, overshoot = false, test = test, etaS = null)

    fun ring(ctx: Context, legName: String?, reason: AlarmReason?, test: Boolean = false): Notification {
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
        val t = ringText(legName, reason, test)
        return Notification.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(t.title)
            .setContentText(t.line)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setColorized(true)
            .setColor(if (t.danger) DANGER else ACCENT)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_tile), "I'm awake", awake).build())
            .build()
    }
}

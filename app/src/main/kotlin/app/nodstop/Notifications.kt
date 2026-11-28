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
import app.nodstop.alarm.AlarmChannel
import app.nodstop.engine.AlarmReason
import app.nodstop.engine.LatLng
import app.nodstop.trip.RateReceiver
import app.nodstop.engine.Phase
import app.nodstop.trip.TripService
import app.nodstop.ui.MainActivity
import app.nodstop.ui.v2.RingText
import app.nodstop.ui.v2.RingTextUi
import app.nodstop.ui.v2.Tokens
import app.nodstop.ui.v2.TripActionUi
import app.nodstop.ui.v2.TripNoteText
import app.nodstop.ui.v2.hm
import kotlin.math.roundToInt

object Notifications {
    const val CH_TRIP = "trip"
    const val CH_AT_STOP = "at_stop"
    const val CH_ALARM = "alarm"
    const val CH_WATCH = "watch"

    const val ID_TRIP = 1
    const val ID_RING = 2
    const val ID_AT_STOP = 3
    const val ID_RATE = 4
    const val ID_MISSED = 5
    const val ID_PAUSED = 6
    /** The ring, again and again, for a watch that mirrors notifications (BACKLOG F3). */
    const val ID_WATCH = 8
    /** Debug → Learn spike recording (docs/LEARN.md L0). */
    const val ID_LEARN_SPIKE = 7

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
            NotificationChannel(CH_WATCH, "Wake-up on your watch", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The alarm again every few seconds, so a watch that mirrors notifications keeps buzzing"
                // The phone already rings and buzzes on the alarm channel: this one is for the watch only.
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        ensureAlarmChannel(ctx)
    }

    // ---- the alarm channel (PLAN Run 10, A1) ----

    private fun channelPrefs(ctx: Context) = ctx.getSharedPreferences("channels", Context.MODE_PRIVATE)

    /** The alarm channel in use. It gets a new ID when it has to be replaced: Android keeps a channel's settings. */
    fun alarmChannelId(ctx: Context): String = channelPrefs(ctx).getString(KEY_ALARM_CHANNEL, null) ?: CH_ALARM

    /** Vibration the channel itself plays (repeated: the ring notification is insistent), as well as the app's own. */
    val ALARM_VIBRATION = longArrayOf(0, 900, 500)

    private fun newAlarmChannel(id: String) =
        NotificationChannel(id, "Wake-up alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "The alarm itself. It buzzes, and shows over the lock screen."
            setSound(null, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
            enableVibration(true)
            vibrationPattern = ALARM_VIBRATION
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

    /**
     * Makes sure the alarm channel can reach you: high importance, vibrating, and through Do Not Disturb when
     * Nodstop has that access. Android keeps a channel's settings (and only honours "bypass Do Not Disturb" if
     * the access existed when it was made), so a channel that falls short is replaced with a fresh one.
     * Never while a ring is showing: its notification lives on the channel.
     */
    fun ensureAlarmChannel(ctx: Context) {
        isolated("alarm channel check") {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val id = alarmChannelId(ctx)
            val ch = nm.getNotificationChannel(id)
            val ok = AlarmChannel.healthy(
                exists = ch != null, importance = ch?.importance ?: 0, canBypassDnd = ch?.canBypassDnd() == true,
                dndAccess = nm.isNotificationPolicyAccessGranted, vibrates = ch?.shouldVibrate() == true,
            )
            if (ok) return
            if (ch == null) { nm.createNotificationChannel(newAlarmChannel(id)); return }
            if (app.nodstop.alarm.RingService.instance != null) return
            val next = AlarmChannel.nextId(id)
            nm.createNotificationChannel(newAlarmChannel(next))
            channelPrefs(ctx).edit().putString(KEY_ALARM_CHANNEL, next).commit()
            nm.deleteNotificationChannel(id)
        }
    }

    private const val KEY_ALARM_CHANNEL = "alarm_channel"

    /** A trip notification that can't be posted must never stop the trip that posts it. */
    private fun post(ctx: Context, id: Int, n: Notification) =
        isolated("notification $id") { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }

    /** The alarm channel in a few words, for the ring report (PLAN A0): "high bypass", "high", "low", "off", "missing". */
    fun alarmChannelState(ctx: Context): String = isolated("alarm channel state", "?") {
        val ch = ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(alarmChannelId(ctx)) ?: return "missing"
        val level = when {
            ch.importance == NotificationManager.IMPORTANCE_NONE -> "off"
            ch.importance >= NotificationManager.IMPORTANCE_HIGH -> "high"
            else -> "low"
        }
        if (ch.canBypassDnd()) "$level bypass" else level
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** A trip button (I'm off this bus, Stop trip): the notification's, and the home screen widget's (BACKLOG F10). */
    fun tripAction(ctx: Context, kind: TripActionUi.Kind): PendingIntent {
        val intent = when (kind) {
            TripActionUi.Kind.OFF -> TripService.offIntent(ctx)
            TripActionUi.Kind.STOP -> TripService.stopIntent(ctx)
        }
        val code = if (kind == TripActionUi.Kind.OFF) 22 else 21
        return PendingIntent.getService(ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun tripStarting(ctx: Context): Notification =
        Notification.Builder(ctx, CH_TRIP)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Sleepers · starting")
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
    fun trip(ctx: Context, ui: TripService.TripUi, quiet: String? = null, radiusM: Double? = null): Notification {
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
        // The "island" (PLAN U2, Run 10 A3): get off, or stop the trip, from the notification or the status-bar
        // chip. Stop trip, and getting off before it rang or you're near, need the phone unlocked.
        for (a in TripNoteText.actions(ui, radiusM)) {
            b.addAction(
                Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_tile), a.label, tripAction(ctx, a.kind))
                    .setAuthenticationRequired(a.unlock).build(),
            )
        }
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
        post(ctx, ID_AT_STOP, n)
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
        post(ctx, ID_RATE, n)
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
        post(ctx, ID_MISSED, n)
    }

    /**
     * After an update or a restart (PLAN A1): the alarm is already set again; this resumes tracking in one tap,
     * in case Android didn't let the app restart it by itself.
     */
    fun tripPaused(ctx: Context, legName: String?) {
        val pi = PendingIntent.getForegroundService(
            ctx, 4400, TripService.resumeIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CH_AT_STOP)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Your trip is still on")
            .setContentText("Alarm set${legName?.let { " for $it" } ?: ""}. Tap to keep tracking where you are.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setColor(ACCENT)
            .build()
        post(ctx, ID_PAUSED, n)
    }

    fun cancelTripPaused(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_PAUSED)
    }

    fun cancelMissed(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_MISSED)
    }

    const val RATE_TITLE = "How was the ring timing?"

    /** The ring notification's two lines: the ring screen's words (mockup `ring`). */
    fun ringText(legName: String?, reason: AlarmReason?, test: Boolean): RingTextUi =
        RingText.of(legName, reason, overshoot = false, test = test, etaS = null)

    /**
     * One buzz on a watch that mirrors the phone's notifications (BACKLOG F3, COROS): the ring's words, posted anew
     * (a watch buzzes for a new notification, not an update). Only `RingService` calls it, while it rings, so the
     * watch never rings on its own; [clearWatch] at I'm awake.
     */
    fun buzzWatch(ctx: Context, legName: String?, reason: AlarmReason?, test: Boolean) {
        val t = ringText(legName, reason, test)
        val open = PendingIntent.getActivity(
            ctx, 13, Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CH_WATCH)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(t.title)
            .setContentText(t.line)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            // Gone by itself if a ring ends without clearing it.
            .setTimeoutAfter(60_000L)
            .build()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_WATCH)
        nm.notify(ID_WATCH, n)
    }

    fun clearWatch(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ID_WATCH)

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
        return Notification.Builder(ctx, alarmChannelId(ctx))
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
            // Insistent: the channel's vibration repeats until the ring is stopped, even if the app's own can't run.
            .apply { flags = flags or Notification.FLAG_INSISTENT }
    }
}

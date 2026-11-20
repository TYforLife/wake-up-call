package app.nodstop.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.nodstop.App
import app.nodstop.notify.CaptureService

/** What the alarm needs from the phone, checked live (setup, Now's red row, the trip's start). */
object Readiness {
    sealed interface Fix {
        data class Permission(val permission: String) : Fix
        data class Open(val intent: Intent) : Fix
    }

    data class Check(val title: String, val ok: Boolean, val detail: String, val fix: Fix?) {
        val optional: Boolean get() = title.endsWith("(optional)")

        /** A stable id for the v2 setup rows (titles are copy and may change). */
        val id: String get() = when {
            title.startsWith("Notifications") -> NOTIFICATIONS
            title.startsWith("Precise location") -> LOCATION
            title.startsWith("Full-screen") -> FULL_SCREEN
            title.startsWith("Let the alarm buzz") -> BUZZ
            title.startsWith("Exact alarms") -> EXACT
            title.startsWith("Battery") -> BATTERY
            title.startsWith("Live trip") -> LIVE
            title.startsWith("While you slept") -> WYS
            title.startsWith("Alarm volume") -> VOLUME
            title.startsWith("Display over other apps") -> OVERLAY
            title.startsWith("Alarm notifications") -> ALARM_CHANNEL
            else -> title
        }
    }

    const val NOTIFICATIONS = "notifications"
    const val LOCATION = "location"
    const val FULL_SCREEN = "full_screen"
    const val BUZZ = "buzz"
    const val EXACT = "exact"
    const val BATTERY = "battery"
    const val LIVE = "live"
    const val WYS = "wys"
    const val VOLUME = "volume"
    const val OVERLAY = "overlay"
    const val ALARM_CHANNEL = "alarm_channel"

    /** Required checks that aren't green: what Home counts and Readiness lists first. */
    fun needsAttention(checks: List<Check>): List<Check> = checks.filter { !it.ok && !it.optional }

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** POST_NOTIFICATIONS only exists from Android 13; before that notifications are on unless the user blocked them. */
    private fun notificationsOk(ctx: Context) =
        if (Build.VERSION.SDK_INT >= 33) granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
        else ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    private fun pkg(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    private fun captureComponent(ctx: Context) = ComponentName(ctx, CaptureService::class.java)

    fun notificationAccessOk(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(captureComponent(ctx))

    fun fullScreenAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun fullScreenSettings(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg(ctx))
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    fun canStartTrip(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && notificationsOk(ctx)

    /** The alarm's notification channel is on and high (PLAN A1): the app repairs it, unless you turned it off. */
    private fun alarmChannelCheck(ctx: Context, nm: NotificationManager): Check {
        app.nodstop.Notifications.ensureAlarmChannel(ctx)
        val ch = nm.getNotificationChannel(app.nodstop.Notifications.alarmChannelId(ctx))
        return Check(
            "Alarm notifications", ch != null && ch.importance >= NotificationManager.IMPORTANCE_HIGH,
            "Turned down or off for Nodstop's alarm: turn it back on.",
            Fix.Open(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, app.nodstop.Notifications.alarmChannelId(ctx)),
            ),
        )
    }

    fun checks(ctx: Context): List<Check> {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val audio = ctx.getSystemService(AudioManager::class.java)
        val speaker = App.instance.settings.speakerLastResort.value
        return listOf(
            Check(
                "Notifications", notificationsOk(ctx),
                "Needed for the alarm screen and the trip status.",
                if (Build.VERSION.SDK_INT >= 33) Fix.Permission(Manifest.permission.POST_NOTIFICATIONS)
                else Fix.Open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)),
            ),
            Check(
                "Precise location", granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION),
                "Only while a trip you started is running. Choose \"While using the app\" and keep Precise on.",
                Fix.Permission(Manifest.permission.ACCESS_FINE_LOCATION),
            ),
            Check(
                "Full-screen alarm",
                Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
                "Lets the alarm show over the lock screen.",
                if (Build.VERSION.SDK_INT >= 34) Fix.Open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg(ctx))) else null,
            ),
            Check(
                "Let the alarm buzz on Mute", nm.isNotificationPolicyAccessGranted,
                "If your phone is on Mute or Do Not Disturb, the alarm switches it to vibrate for the ring and puts it " +
                    "back after \"I'm awake\". Needs Do Not Disturb access.",
                Fix.Open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
            ),
            Check(
                "Display over other apps", Settings.canDrawOverlays(ctx),
                "So the alarm screen opens even while you're using another app, not only a banner.",
                Fix.Open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg(ctx))),
            ),
            alarmChannelCheck(ctx, nm),
            Check(
                "Exact alarms", am.canScheduleExactAlarms(),
                "The alarm must fire on time, even in Doze.",
                Fix.Open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg(ctx))),
            ),
            Check(
                "Battery: not optimised", pm.isIgnoringBatteryOptimizations(ctx.packageName),
                "Stops Android from pausing GPS updates during a trip.",
                Fix.Open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg(ctx))),
            ),
            Check(
                "Live trip progress (optional)",
                Build.VERSION.SDK_INT < 36 || nm.canPostPromotedNotifications(),
                "Shows distance to your stop on the lock screen and status bar (Android 16).",
                Fix.Open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)),
            ),
            Check(
                "While you slept (optional)", notificationAccessOk(ctx),
                "Lets Nodstop read your chat notifications during a trip and show what you missed on the alarm " +
                    "screen. Memory only: never saved, logged or sent anywhere.",
                Fix.Open(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, captureComponent(ctx).flattenToString()),
                ),
            ),
            Check(
                "Alarm volume above 0", audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0 || !speaker,
                if (speaker) "Used by the speaker last resort." else "Only used by the speaker last resort, which is off.",
                Fix.Open(Intent(Settings.ACTION_SOUND_SETTINGS)),
            ),
        )
    }
}

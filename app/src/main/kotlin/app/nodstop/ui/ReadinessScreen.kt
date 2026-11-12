package app.nodstop.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nodstop.App

object Readiness {
    sealed interface Fix {
        data class Permission(val permission: String) : Fix
        data class Open(val intent: Intent) : Fix
    }

    data class Check(val title: String, val ok: Boolean, val detail: String, val fix: Fix?)

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** POST_NOTIFICATIONS only exists from Android 13; before that notifications are on unless the user blocked them. */
    private fun notificationsOk(ctx: Context) =
        if (Build.VERSION.SDK_INT >= 33) granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
        else ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    private fun pkg(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    fun canStartTrip(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && notificationsOk(ctx)

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
                "Alarm volume above 0", audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0 || !speaker,
                if (speaker) "Used by the speaker last resort." else "Only used by the speaker last resort, which is off.",
                Fix.Open(Intent(Settings.ACTION_SOUND_SETTINGS)),
            ),
        )
    }
}

@Composable
fun ReadinessScreen(resumeTick: Int, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val refresh = remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh.intValue++ }
    val speaker by App.instance.settings.speakerLastResort.collectAsState()
    val checks = remember(resumeTick, refresh.intValue, speaker) { Readiness.checks(ctx) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopBar("Readiness", onBack = { go(Screen.Home) })
        Section {
            for (c in checks) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("●", color = if (c.ok) Color(0xFF2E7D32) else Color(0xFFD32F2F))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(c.title, style = MaterialTheme.typography.titleSmall)
                        Text(c.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    val fix = c.fix
                    if (!c.ok && fix != null) Button(onClick = {
                        when (fix) {
                            is Readiness.Fix.Permission -> launcher.launch(fix.permission)
                            is Readiness.Fix.Open -> runCatching { ctx.startActivity(fix.intent) }
                        }
                    }) { Text("Fix") }
                }
            }
        }
        Section("Alarm") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Phone speaker as last resort", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "If you haven't held \"I'm awake\" after 60 s, ring out loud through the speaker. " +
                            "Off: earbuds (if connected) and vibration only.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = speaker, onCheckedChange = { App.instance.settings.setSpeakerLastResort(it) })
            }
            Text(
                "Without the speaker, vibration has to wake you: keep the phone in a pocket or your hand, not a bag. " +
                    "Check Settings → Sounds and vibration → Vibration intensity isn't at zero.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Section("Samsung: do these once") {
            Text("1. Settings → Apps → Nodstop → Battery → Unrestricted")
            Text("2. Settings → Battery → Background usage limits: make sure Nodstop is not in Sleeping or Deep sleeping apps, and add it to Never sleeping apps")
            Text("3. Never Force stop the app: Android cancels its alarms when you do.")
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
            }) { Text("Open Nodstop's app settings") }
        }
    }
}

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
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
import app.nodstop.notify.CaptureService
import app.nodstop.notify.DndController

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

    private fun captureComponent(ctx: Context) = ComponentName(ctx, CaptureService::class.java)

    fun notificationAccessOk(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(captureComponent(ctx))

    fun fullScreenAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun fullScreenSettings(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg(ctx))
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

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
        val lastRing by App.instance.settings.lastRing.collectAsState()
        lastRing?.let { Section { Text(it, style = MaterialTheme.typography.bodyMedium) } }
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
        Section("Ring timing") {
            val ask by App.instance.settings.askTiming.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ask after each ring", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "After \"I'm awake\", a silent notification asks: too early, good or too late? Answering is " +
                            "optional; each answer moves that stop's ring 10–15 s next time.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = ask, onCheckedChange = { App.instance.settings.setAskTiming(it) })
            }
        }
        Section("While you slept (experimental)") {
            val settings = App.instance.settings
            val vips by settings.vipNames.collectAsState()
            val tea by settings.teaTone.collectAsState()
            var vipText by remember(vips) { mutableStateOf(vips.joinToString(", ")) }
            Text("Important contacts", style = MaterialTheme.typography.titleSmall)
            Text(
                "Names as they appear in your chats, separated by commas (e.g. Mum, Daycare). Only their messages can " +
                    "wake you, and only if the on-phone model judges them urgent. Shown first on the alarm screen.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = vipText, onValueChange = { vipText = it }, modifier = Modifier.weight(1f), singleLine = true)
                TextButton(enabled = vipText != vips.joinToString(", "), onClick = { settings.setVipNames(vipText.split(',')) }) { Text("Save") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tea tone", style = MaterialTheme.typography.titleSmall)
                    Text("Briefing lines in group-chat voice. Same facts, checked again; plain if a rewrite changes one.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = tea, onCheckedChange = { settings.setTeaTone(it) })
            }
            val gpu by settings.useGpu.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use the GPU (experimental)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Runs the model on the graphics chip: often faster and lighter on the battery. Falls back to the CPU " +
                            "if the phone or model can't. Trips use it only after Debug → Briefing lab → Run has worked on " +
                            "the GPU once on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = gpu, onCheckedChange = { settings.setUseGpu(it) })
            }
            val dnd by App.instance.settings.dndDuringTrips.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Do Not Disturb during trips", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Holds other notifications while you sleep (alarms still get through), and puts your " +
                            "setting back when the trip ends. Untested on Samsung: check the earbud ring still plays.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = dnd, onCheckedChange = { on ->
                    App.instance.settings.setDndDuringTrips(on)
                    if (on && !DndController.granted(ctx)) {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                    }
                })
            }
            if (dnd && !DndController.granted(ctx)) {
                Text("Needs Do Not Disturb access for Nodstop: tap the switch again after allowing it.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD32F2F))
            }
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

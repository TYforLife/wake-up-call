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

    data class Check(val title: String, val ok: Boolean, val detail: String, val fix: Fix?) {
        val optional: Boolean get() = title.endsWith("(optional)")
    }

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

/** Readiness's switches and texts, as plain data (also what the screenshot tests render). */
data class ReadinessUi(
    val checks: List<Readiness.Check>,
    val lastRing: String? = null,
    val speaker: Boolean = false,
    val askTiming: Boolean = true,
    val vips: List<String> = emptyList(),
    val tea: Boolean = false,
    val gpu: Boolean = false,
    val dnd: Boolean = false,
    val dndGranted: Boolean = true,
)

class ReadinessActions(
    val back: () -> Unit = {},
    val fix: (Readiness.Check) -> Unit = {},
    val speaker: (Boolean) -> Unit = {},
    val askTiming: (Boolean) -> Unit = {},
    val vips: (List<String>) -> Unit = {},
    val tea: (Boolean) -> Unit = {},
    val gpu: (Boolean) -> Unit = {},
    val dnd: (Boolean) -> Unit = {},
    val appSettings: () -> Unit = {},
)

@Composable
fun ReadinessScreen(resumeTick: Int, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val refresh = remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh.intValue++ }
    val settings = App.instance.settings
    val speaker by settings.speakerLastResort.collectAsState()
    val checks = remember(resumeTick, refresh.intValue, speaker) { Readiness.checks(ctx) }
    val lastRing by settings.lastRing.collectAsState()
    val ask by settings.askTiming.collectAsState()
    val vips by settings.vipNames.collectAsState()
    val tea by settings.teaTone.collectAsState()
    val gpu by settings.useGpu.collectAsState()
    val dnd by settings.dndDuringTrips.collectAsState()
    val ui = ReadinessUi(checks, lastRing, speaker, ask, vips, tea, gpu, dnd, DndController.granted(ctx))
    ReadinessContent(ui, ReadinessActions(
        back = { go(Screen.Home) },
        fix = { c ->
            when (val f = c.fix) {
                is Readiness.Fix.Permission -> launcher.launch(f.permission)
                is Readiness.Fix.Open -> runCatching { ctx.startActivity(f.intent) }
                null -> Unit
            }
        },
        speaker = { settings.setSpeakerLastResort(it) },
        askTiming = { settings.setAskTiming(it) },
        vips = { settings.setVipNames(it) },
        tea = { settings.setTeaTone(it) },
        gpu = { settings.setUseGpu(it) },
        dnd = { on ->
            settings.setDndDuringTrips(on)
            if (on && !DndController.granted(ctx)) runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        },
        appSettings = { runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) } },
    ))
}

@Composable
fun ReadinessContent(ui: ReadinessUi, a: ReadinessActions) {
    var showAll by remember { mutableStateOf(false) }
    val attention = Readiness.needsAttention(ui.checks)
    val optionalOff = ui.checks.filter { !it.ok && it.optional }
    ScreenColumn("Readiness", a.back) {
        if (attention.isEmpty()) Section(tone = Tone.OK) {
            Text("All set", style = MaterialTheme.typography.headlineSmall, color = LocalNodstopColors.current.tone(Tone.OK).fg)
            Text("Every check the alarm needs is green.", style = MaterialTheme.typography.bodyMedium)
            QuietButton(if (showAll) "Hide checks" else "Show all ${ui.checks.size} checks", { showAll = !showAll })
        } else Section("${attention.size} to fix before you rely on the alarm", tone = Tone.BAD) {
            for (c in attention) CheckRow(c, a)
        }
        ui.lastRing?.let { Section("Last ring") { Text(it.removePrefix("Last ring: "), style = MaterialTheme.typography.bodyMedium) } }
        if (optionalOff.isNotEmpty()) Section("Optional") { for (c in optionalOff) CheckRow(c, a) }
        if (showAll || attention.isNotEmpty()) {
            val ok = ui.checks.filter { it.ok }
            if (ok.isNotEmpty()) Section("Working") { for (c in ok) CheckRow(c, a) }
        }

        GroupLabel("Alarm")
        Section {
            SettingRow(
                "Phone speaker as last resort",
                "If you haven't held \"I'm awake\" after 60 s, ring out loud. Off: earbuds (if in) and vibration only.",
                ui.speaker, a.speaker,
            )
            SettingRow(
                "Ask about ring timing",
                "After \"I'm awake\", a silent notification asks: too early, good or too late? Optional; each answer moves that stop's ring 10–15 s.",
                ui.askTiming, a.askTiming,
            )
            Hint(
                "Without the speaker, vibration has to wake you: keep the phone in a pocket or your hand, not a bag. " +
                    "Check Settings → Sounds and vibration → Vibration intensity isn't at zero.",
            )
        }

        GroupLabel("While you slept (experimental)")
        Section {
            var vipText by remember(ui.vips) { mutableStateOf(ui.vips.joinToString(", ")) }
            Text("Important contacts", style = MaterialTheme.typography.titleSmall)
            Hint(
                "Names as they appear in your chats, separated by commas (e.g. Mum, Daycare). Only their messages can " +
                    "wake you, and only if the on-phone model judges them urgent.",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = vipText, onValueChange = { vipText = it }, modifier = Modifier.weight(1f), singleLine = true)
                QuietButton("Save", { a.vips(vipText.split(',')) }, enabled = vipText != ui.vips.joinToString(", "))
            }
            SettingRow("Tea tone", "Briefing lines in group-chat voice. Same facts, checked again.", ui.tea, a.tea)
            SettingRow(
                "Use the GPU",
                "Often faster and lighter on the battery; falls back to the CPU. Trips use it only after Debug → Briefing lab has run on the GPU once.",
                ui.gpu, a.gpu,
            )
            SettingRow(
                "Do Not Disturb during trips",
                "Holds other notifications while you sleep (alarms still get through) and restores your setting after.",
                ui.dnd, a.dnd,
            )
            if (ui.dnd && !ui.dndGranted) StatusChip("Needs Do Not Disturb access: tap the switch again after allowing it", Tone.WARN)
        }

        GroupLabel("Samsung: do these once")
        Section {
            Text("1. Settings → Apps → Nodstop → Battery → Unrestricted", style = MaterialTheme.typography.bodyMedium)
            Text("2. Settings → Battery → Background usage limits → add Nodstop to Never sleeping apps", style = MaterialTheme.typography.bodyMedium)
            Text("3. Never Force stop the app: Android cancels its alarms when you do.", style = MaterialTheme.typography.bodyMedium)
            SecondaryButton("Open Nodstop's app settings", a.appSettings)
        }
    }
}

@Composable
private fun CheckRow(c: Readiness.Check, a: ReadinessActions) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (c.ok) Tone.OK else if (c.optional) Tone.NEUTRAL else Tone.BAD)
        Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
            Text(c.title.removeSuffix(" (optional)"), style = MaterialTheme.typography.titleSmall)
            Hint(c.detail)
        }
        if (!c.ok && c.fix != null) Button(onClick = { a.fix(c) }, shape = Radius.button) { Text("Fix") }
    }
}

package app.nodstop.ui.v2

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.nodstop.App
import app.nodstop.BuildConfig
import app.nodstop.notify.DndController
import app.nodstop.ui.Readiness
import app.nodstop.ui.Screen

fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

/** Runs a check's fix: asks for a permission, or opens the system page for it. */
@Composable
private fun rememberFixer(onResult: () -> Unit): (Readiness.Check) -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    return { c ->
        when (val f = c.fix) {
            is Readiness.Fix.Permission -> launcher.launch(f.permission)
            is Readiness.Fix.Open -> runCatching { ctx.startActivity(f.intent) }
            null -> Unit
        }
    }
}

/** Setup (mockup `setup`): first run, and from Now's red row. Re-checked on every resume and after each Allow. */
@Composable
fun SetupHost(resumeTick: Int, done: () -> Unit) {
    val ctx = LocalContext.current
    val settings = App.instance.settings
    val refresh = remember { mutableIntStateOf(0) }
    val speaker by settings.speakerLastResort.collectAsState()
    val checks = remember(resumeTick, refresh.intValue, speaker) { Readiness.checks(ctx) }
    val fix = rememberFixer { refresh.intValue++ }
    val samsung = remember { isSamsung() }
    SetupScreen(
        SetupText.rows(checks, samsung), samsung,
        SetupActions(
            allow = { id -> checks.firstOrNull { it.id == id }?.let(fix) },
            done = { settings.setSetupSeen(); done() },
            notNow = { settings.setSetupSeen(); done() },
        ),
        Modifier.statusBarsPadding().navigationBarsPadding(),
    )
}

/** Settings (mockup `settings`). */
@Composable
fun SettingsTab(resumeTick: Int, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val s = App.instance.settings
    val quiet by s.dndDuringTrips.collectAsState()
    val speaker by s.speakerLastResort.collectAsState()
    val ask by s.askTiming.collectAsState()
    val vips by s.vipNames.collectAsState()
    val refresh = remember { mutableIntStateOf(0) }
    val live = remember(resumeTick, refresh.intValue) { Readiness.checks(ctx).firstOrNull { it.id == Readiness.LIVE } }
    val fix = rememberFixer { refresh.intValue++ }
    SettingsScreen(
        SettingsUi(quiet, speaker, ask, SetupText.contacts(vips), "Build ${BuildConfig.BUILD_NUMBER} · ${BuildConfig.GIT_SHA}", live?.ok == false,
            credits = remember { app.nodstop.data.TransitData.attribution(ctx) },
            homeWork = remember(resumeTick) { PlanText.homeWork(s.home?.title, s.work?.title) }),
        SettingsActions(
            quiet = { on ->
                s.setDndDuringTrips(on)
                if (on && !DndController.granted(ctx)) open(ctx, Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            },
            speaker = { s.setSpeakerLastResort(it) },
            ask = { s.setAskTiming(it) },
            contacts = { go(Screen.Contacts) },
            report = { reportMissedAlarm(ctx) },
            live = { live?.let(fix) },
            debug = { go(Screen.Debug) },
            homeWork = { go(Screen.HomeWork) },
        ),
        Modifier.statusBarsPadding(),
    )
}

/** Important contacts, and the notification access While you slept needs to see their messages. */
@Composable
fun ContactsHost(resumeTick: Int, back: () -> Unit) {
    val ctx = LocalContext.current
    val s = App.instance.settings
    val vips by s.vipNames.collectAsState()
    val readingOk = remember(resumeTick) { Readiness.notificationAccessOk(ctx) }
    val fix = rememberFixer { }
    ContactsScreen(
        vips, readingOk,
        ContactsActions(
            back = back,
            add = { n -> if (vips.none { it.equals(n, ignoreCase = true) }) s.setVipNames(vips + n) },
            remove = { n -> s.setVipNames(vips - n) },
            allowReading = { Readiness.checks(ctx).firstOrNull { it.id == Readiness.WYS }?.let(fix) },
        ),
        Modifier.statusBarsPadding().navigationBarsPadding(),
    )
}

/** Shares the last trip's log with locations hidden, plus the last ring's summary as the message. */
fun reportMissedAlarm(ctx: Context) {
    val app = App.instance
    val text = app.settings.lastRing.value ?: "No ring recorded yet."
    app.traces.shareIntent(hideLocation = true, text = "Nodstop missed alarm report. $text")?.let { open(ctx, it) }
}

private fun open(ctx: Context, i: Intent) {
    runCatching { ctx.startActivity(i) }
}

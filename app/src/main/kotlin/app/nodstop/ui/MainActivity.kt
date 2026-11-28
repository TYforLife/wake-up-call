package app.nodstop.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.nodstop.App
import app.nodstop.Shortcuts
import app.nodstop.data.Footpaths
import app.nodstop.engine.LatLng
import app.nodstop.alarm.AlarmActivity
import app.nodstop.alarm.RingService
import app.nodstop.trip.TripService
import app.nodstop.ui.v2.AlarmRingHost
import app.nodstop.ui.v2.FirstRun
import app.nodstop.ui.v2.RouteText
import app.nodstop.ui.v2.NodstopV2Theme
import app.nodstop.ui.v2.RouteHost
import app.nodstop.ui.v2.StopHost
import app.nodstop.ui.v2.SetupHost
import app.nodstop.ui.v2.ShareHost
import app.nodstop.ui.v2.HomeWorkHost
import app.nodstop.ui.v2.PlanHost
import app.nodstop.ui.v2.ContactsHost
import app.nodstop.ui.v2.SoundsHost
import app.nodstop.ui.v2.CoverageHost
import app.nodstop.ui.v2.LiveBusesHost
import app.nodstop.ui.v2.SubScreen
import app.nodstop.ui.v2.Tab
import app.nodstop.ui.v2.TabbedShell

sealed interface Screen {
    data object Home : Screen
    data object Trip : Screen
    /** A route (null: a new one, named first). */
    data class Route(val routeId: String?) : Screen
    /** Set one bus's stop on the map. [fresh]: just added; backing out without saving removes it (PLAN B1). */
    data class Stop(val routeId: String, val leg: Int, val fresh: Boolean = false) : Screen
    /** Plan a trip with real times (PLAN Run 13, T4), optionally from a Google Maps link. */
    /** [to]: plan to there from where you are ("Get back to Symonds St", BACKLOG F23). */
    data class Plan(val link: String? = null, val to: app.nodstop.ui.v2.PlaceUi? = null) : Screen
    /** "Where's home? Where's work?" (PLAN Run 13, T5). */
    data object HomeWork : Screen
    /** Shared from Google Maps (PLAN Run 12, C1). */
    data class Share(val text: String) : Screen
    data object Setup : Screen
    data object Contacts : Screen
    /** Settings → Alarm sound (BACKLOG 3). */
    data object Sounds : Screen
    /** Routes → Departures: any stop's next buses (BACKLOG F27). */
    data object Board : Screen
    /** Settings → Where Nodstop works. */
    data object Coverage : Screen
    /** Settings → Live buses (BACKLOG F13). */
    data object LiveBuses : Screen
    data object Debug : Screen
    data object BriefingLab : Screen
    /** Debug → Learn spike (docs/LEARN.md L0). */
    data object LearnSpike : Screen

    /** Where Back goes: a stop goes back to its route; everything else to the tabs. */
    val parent: Screen? get() = when (this) {
        is Stop -> Route(routeId)
        else -> null
    }
}

class MainActivity : ComponentActivity() {
    /** Bumped on every onResume so screens re-read permission and settings state. */
    private val resumeTick = mutableIntStateOf(0)
    private val pendingStart = mutableStateOf<String?>(null)
    private val pendingShare = mutableStateOf<String?>(null)
    private val pendingPlan = mutableStateOf<app.nodstop.ui.v2.PlaceUi?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { AppRoot(resumeTick.intValue, pendingStart, pendingShare, pendingPlan) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    private fun handle(intent: Intent?) {
        // A place or directions shared from Google Maps (PLAN Run 12, C1).
        ShareIn.text(intent)?.let { pendingShare.value = it; intent?.removeExtra(Intent.EXTRA_TEXT) }
        intent?.getStringExtra(Shortcuts.EXTRA_START_ROUTE_ID)?.let {
            pendingStart.value = it
            Shortcuts.reportUsed(this, it)
            intent.removeExtra(Shortcuts.EXTRA_START_ROUTE_ID)
        }
        // "Get back to Symonds St" from the alarm screen (BACKLOG F23): Plan a trip there from here.
        intent?.getStringExtra(Shortcuts.EXTRA_PLAN_TO_NAME)?.let { name ->
            val lat = intent.getDoubleExtra(Shortcuts.EXTRA_PLAN_TO_LAT, Double.NaN)
            val lng = intent.getDoubleExtra(Shortcuts.EXTRA_PLAN_TO_LNG, Double.NaN)
            if (!lat.isNaN() && !lng.isNaN()) {
                pendingPlan.value = app.nodstop.ui.v2.PlaceUi(name, "", app.nodstop.engine.LatLng(lat, lng), app.nodstop.ui.v2.PlaceUi.Kind.STOP)
            }
            intent.removeExtra(Shortcuts.EXTRA_PLAN_TO_NAME)
        }
    }
}

@Composable
private fun AppRoot(
    resumeTick: Int, pendingStart: MutableState<String?>, pendingShare: MutableState<String?>,
    pendingPlan: MutableState<app.nodstop.ui.v2.PlaceUi?>,
) {
    val ctx = LocalContext.current
    // v2 (PLAN Run 8, M2): three tabs, plus the old screens as sub-screens until Run 9 replaces them.
    var tab by remember { mutableStateOf(Tab.NOW) }
    var sub by remember { mutableStateOf<Screen?>(null) }
    val go: (Screen) -> Unit = { s ->
        when (s) {
            Screen.Home -> sub = null
            Screen.Trip -> { sub = null; tab = Tab.NOW }
            else -> sub = s
        }
    }

    // First run (BACKLOG F34): home and work by address first, then Setup once if anything the alarm needs is off.
    // Later, Now's red row leads there.
    LaunchedEffect(Unit) {
        val ready = App.instance.routes.routes.value.any { RouteText.problem(it) == null }
        val step = FirstRun.atLaunch(ready, App.instance.settings.setupSeen, Readiness.needsAttention(Readiness.checks(ctx)).isNotEmpty())
        when (step) {
            FirstRun.Step.HOME_WORK -> go(Screen.HomeWork)
            FirstRun.Step.SETUP -> go(Screen.Setup)
            FirstRun.Step.NONE -> Unit
        }
    }

    // The commute's footpaths before you plan it (BACKLOG 27): home, work, and the stops where you change buses.
    val routes by App.instance.routes.routes.collectAsState()
    LaunchedEffect(routes) {
        val st = App.instance.settings
        Footpaths.prefetch(ctx, Footpaths.commute(st.home?.let { LatLng(it.lat, it.lng) }, st.work?.let { LatLng(it.lat, it.lng) }, routes))
    }

    LaunchedEffect(pendingShare.value) {
        val text = pendingShare.value ?: return@LaunchedEffect
        pendingShare.value = null
        go(Screen.Share(text))
    }

    // Started from a Quick Settings tile or launcher shortcut: start that route (or show the running trip).
    LaunchedEffect(pendingPlan.value) {
        val to = pendingPlan.value ?: return@LaunchedEffect
        pendingPlan.value = null
        go(Screen.Plan(to = to))
    }
    LaunchedEffect(pendingStart.value) {
        val id = pendingStart.value ?: return@LaunchedEffect
        pendingStart.value = null
        when {
            TripService.status.value != null -> go(Screen.Trip)
            !Readiness.canStartTrip(ctx) -> go(Screen.Setup)
            else -> { TripService.startRoute(ctx, id); go(Screen.Trip) }
        }
    }

    if (sub != null) BackHandler { sub = sub?.parent }
    else if (tab != Tab.NOW) BackHandler { tab = Tab.NOW }

    // Ringing while the app is open: the alarm takes over this screen, no banner to hunt for.
    val ring by RingService.state.collectAsState()
    val s = sub
    val back = { sub = sub?.parent }
    when (s) {
        null -> NodstopV2Theme { TabbedShell(tab, { tab = it }, resumeTick, go) }
        is Screen.Route -> NodstopV2Theme { SubScreen { RouteHost(s.routeId, go, back) } }
        is Screen.Stop -> NodstopV2Theme { SubScreen { StopHost(s.routeId, s.leg, s.fresh, back) } }
        Screen.Setup -> NodstopV2Theme { SubScreen { SetupHost(resumeTick, back) } }
        is Screen.Share -> NodstopV2Theme { SubScreen { ShareHost(s.text, go, back) } }
        is Screen.Plan -> NodstopV2Theme {
            SubScreen {
                PlanHost(
                    "Plan a trip", null, s.to, null, s.link, null, fixed = false,
                    onUsed = { r, start -> if (start) { TripService.startRoute(ctx, r.id); go(Screen.Trip) } else go(Screen.Route(r.id)) },
                    back = back,
                )
            }
        }
        Screen.HomeWork -> NodstopV2Theme { SubScreen { HomeWorkHost(go, back) } }
        Screen.Contacts -> NodstopV2Theme { SubScreen { ContactsHost(resumeTick, back) } }
        Screen.Sounds -> NodstopV2Theme { SubScreen { SoundsHost(back) } }
        Screen.Coverage -> NodstopV2Theme { SubScreen { CoverageHost(back) } }
        Screen.LiveBuses -> NodstopV2Theme { SubScreen { LiveBusesHost(back) } }
        Screen.Board -> NodstopV2Theme { SubScreen { app.nodstop.ui.v2.BoardHost(back) } }
        else -> NodstopV2Theme {
            androidx.compose.foundation.layout.Box(Modifier.safeDrawingPadding()) {
                when (s) {
                    Screen.Debug -> DebugScreen(go)
                    Screen.BriefingLab -> BriefingLabScreen(go)
                    Screen.LearnSpike -> LearnSpikeScreen(go)
                    else -> Unit
                }
            }
        }
    }
    ring?.let { r ->
        BackHandler { }
        AlarmRingHost(r, onAwake = { AlarmActivity.awake(ctx) })
    }
}

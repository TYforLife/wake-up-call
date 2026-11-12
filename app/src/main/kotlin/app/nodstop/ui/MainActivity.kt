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
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import app.nodstop.Shortcuts
import app.nodstop.alarm.AlarmActivity
import app.nodstop.alarm.RingService
import app.nodstop.trip.TripService

sealed interface Screen {
    data object Home : Screen
    data class Edit(val routeId: String?) : Screen
    data object Trip : Screen
    data object Readiness : Screen
    data object Debug : Screen
}

class MainActivity : ComponentActivity() {
    /** Bumped on every onResume so screens re-read permission and settings state. */
    private val resumeTick = mutableIntStateOf(0)
    private val pendingStart = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            NodstopTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot(resumeTick.intValue, pendingStart)
                }
            }
        }
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
        intent?.getStringExtra(Shortcuts.EXTRA_START_ROUTE_ID)?.let {
            pendingStart.value = it
            intent.removeExtra(Shortcuts.EXTRA_START_ROUTE_ID)
        }
    }
}

@Composable
fun NodstopTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun AppRoot(resumeTick: Int, pendingStart: MutableState<String?>) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val go: (Screen) -> Unit = { screen = it }

    // Started from a Quick Settings tile or launcher shortcut: start that route (or show the running trip).
    LaunchedEffect(pendingStart.value) {
        val id = pendingStart.value ?: return@LaunchedEffect
        pendingStart.value = null
        screen = when {
            TripService.status.value != null -> Screen.Trip
            !Readiness.canStartTrip(ctx) -> Screen.Readiness
            else -> { TripService.startRoute(ctx, id); Screen.Trip }
        }
    }

    if (screen != Screen.Home) BackHandler { screen = Screen.Home }

    // Ringing while the app is open: the alarm takes over this screen, no banner to hunt for.
    val ring by RingService.state.collectAsState()
    androidx.compose.foundation.layout.Box(Modifier.safeDrawingPadding()) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(resumeTick, go)
            is Screen.Edit -> RouteEditorScreen(s.routeId, onDone = { screen = Screen.Home })
            Screen.Trip -> TripScreen(go)
            Screen.Readiness -> ReadinessScreen(resumeTick, go)
            Screen.Debug -> DebugScreen(go)
        }
    }
    ring?.let { r ->
        BackHandler { }
        AlarmScreen(ring = r, holdMs = 600, onAwake = { AlarmActivity.awake(ctx) }, onClose = { })
    }
}

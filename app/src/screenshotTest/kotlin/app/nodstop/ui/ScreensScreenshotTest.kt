package app.nodstop.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.nodstop.data.LegDraft
import app.nodstop.data.PendingRating
import app.nodstop.data.Route
import app.nodstop.engine.AlarmReason
import app.nodstop.engine.LatLng
import app.nodstop.engine.Learned
import app.nodstop.engine.Phase
import app.nodstop.engine.Status
import app.nodstop.map.ZoneSheet
import app.nodstop.trip.TripService
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of every screen (PLAN U0/U6), from made-up data (no real stops). Recorded in CI with
 * `-Pscreenshots` and attached to the debug-latest release. The map itself (MapLibre) can't render on
 * the JVM, so the Map shot is its stop sheet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ScreensScreenshotTest {
    private val a = LatLng(-36.80, 174.70)
    private val b = LatLng(-36.81, 174.71)
    private val c = LatLng(-36.82, 174.72)
    private val routes = listOf(
        Route("r1", "To work", listOf(LegDraft("Central Station", "${a.lat}, ${a.lng}"), LegDraft("Harbour St", "${b.lat}, ${b.lng}"))),
        Route("r2", "From work", listOf(LegDraft("Central Station", "${a.lat}, ${a.lng}"), LegDraft("Park Rd", "${c.lat}, ${c.lng}", alarmEnabled = false), LegDraft("Hill Ave", ""))),
    )

    private val status = Status(
        legIndex = 1, legCount = 2, legName = "Harbour St", phase = Phase.RIDING, distanceM = 2_340.0, etaS = 372.0,
        vEstKmh = 31.0, speedKmh = 38.0, lastFixAgeS = 1.0, gpsOk = true, alarmAtMs = 1_731_300_330_000,
        alarmReason = AlarmReason.WAKE, ringReason = null, onLearnedRoute = true, learnedRides = 4,
        startDistanceM = 9_800.0, pace = 1.1, leadS = 45.0,
    )
    private val trip = TripService.TripUi("To work", listOf("Central Station", "Harbour St"), listOf(a, b), 1, status, false, 0)

    private fun shot(name: String, content: @Composable () -> Unit) = captureRoboImage("src/test/snapshots/$name.png") {
        MaterialTheme(colorScheme = lightColorScheme()) { Surface(Modifier.fillMaxSize()) { content() } }
    }

    @Test
    fun home_trip_running() = shot("screen_home_trip") {
        HomeContent(HomeUi(routes, trip = trip, build = "Build 60 · abc1234"), HomeActions())
    }

    @Test
    fun home_idle_with_rating() = shot("screen_home_idle") {
        HomeContent(
            HomeUi(
                routes, rating = PendingRating("t.jsonl", listOf(0 to "Central Station", 1 to "Harbour St"), 0, listOf(a, b)),
                fullScreenOk = false, notReady = 1, build = "Build 60 · abc1234",
            ),
            HomeActions(),
        )
    }

    @Test
    fun trip() = shot("screen_trip") { TripContent(trip, {}, {}, {}, {}) }

    @Test
    fun route_editor() = shot("screen_route_editor") {
        RouteEditorContent(
            isNew = false, name = "To work", legs = routes[0].legs,
            learned = { if (it == a) Learned(a, emptyList(), 4, 40.0) else null }, locating = null, error = null, a = EditorActions(),
        )
    }

    private val checks = listOf(
        Readiness.Check("Notifications", true, "Needed for the alarm screen and the trip status.", null),
        Readiness.Check("Precise location", true, "Only while a trip you started is running.", null),
        Readiness.Check("Full-screen alarm", false, "Lets the alarm show over the lock screen.", Readiness.Fix.Permission("x")),
        Readiness.Check("Exact alarms", true, "The alarm must fire on time, even in Doze.", null),
        Readiness.Check("Battery: not optimised", true, "Stops Android from pausing GPS updates during a trip.", null),
        Readiness.Check("While you slept (optional)", false, "Shows what you missed on the alarm screen.", Readiness.Fix.Permission("y")),
    )

    @Test
    fun readiness_needs_attention() = shot("screen_readiness") {
        ReadinessContent(ReadinessUi(checks, lastRing = "Last ring: screen turned on ✓ · earbuds · I'm awake (hold) after 14 s"), ReadinessActions())
    }

    @Test
    fun readiness_all_set() = shot("screen_readiness_all_set") {
        ReadinessContent(ReadinessUi(checks.map { it.copy(ok = true) }, vips = listOf("Mum", "Daycare")), ReadinessActions())
    }

    @Test
    fun map_stop_sheet() = shot("screen_map_sheet") {
        ZoneSheet("Harbour St", draft = 250.0, autoM = 310.0, onDraft = {}, onSave = {}, onCancel = {})
    }
}

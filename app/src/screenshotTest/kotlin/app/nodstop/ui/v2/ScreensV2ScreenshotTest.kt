package app.nodstop.ui.v2

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.nodstop.brief.BriefedLine
import app.nodstop.brief.Digests
import app.nodstop.brief.PanelLines
import app.nodstop.brief.SampleChats
import app.nodstop.notify.PanelText
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The v2 screens of Run 8, recorded as `v2_<id>.png` so `docs/design/compare.py` pairs each with its mockup.
 * MapLibre can't render on the JVM: the map is a flat `map-bg` box.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi", application = Application::class)
class ScreensV2ScreenshotTest {
    private fun shot(id: String, dark: Boolean = false, content: @Composable () -> Unit) =
        captureRoboImage("src/test/snapshots/v2_$id.png") { NodstopV2Theme(dark = dark) { content() } }

    /** MapLibre can't render on the JVM: the map is a flat box in Google's land colour (PLAN B2). */
    private val flatMap: @Composable (Modifier) -> Unit = { m ->
        val land = app.nodstop.map.MapPalette.colors(LocalN.current.dark).base.getValue(app.nodstop.map.MapPalette.Role.LAND)
        Box(m.background(androidx.compose.ui.graphics.Color(land.toInt())))
    }

    private val routes = listOf(
        RouteOption("work", "To work", "Central Station, then Harbour St"),
        RouteOption("home", "From work", "3 buses · Hill Ave"),
    )

    @Composable
    private fun withTabs(live: Boolean, tab: Tab = Tab.NOW, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            TabBar(tab, live, {})
        }
    }

    private val riding = TripSheetUi("Get off at", "Harbour St", "6 min · 2.3 km", false, "Alarm about 7:45", false, "I'm off this bus")

    @Test fun now() = shot("now") { withTabs(false) { NowIdle(NowIdleUi("Good morning", routes, "work"), NowActions()) } }

    @Test fun now_not_ready() = shot("now-not-ready") { withTabs(false) { NowIdle(NowIdleUi("Good morning", routes, "work", notReady = 1), NowActions()) } }

    @Test fun now_last_trip() = shot("now-last-trip") {
        withTabs(false) {
            NowIdle(
                NowIdleUi(
                    "Good evening", routes, "home",
                    lastTrip = listOf(
                        app.nodstop.alarm.VerdictLine("Central Station", "Rang at Central Station", "Buzzed · screen on", false),
                        app.nodstop.alarm.VerdictLine("Harbour St", "Rang at Harbour St", "May not have buzzed: phone on Mute without access", true),
                    ),
                ),
                NowActions(),
            )
        }
    }

    @Test fun riding() = shot("riding") { withTabs(true) { NowRiding(riding, {}, {}, {}, flatMap) } }

    /** PLAN Run 11, B3: the sheet folded to one line, for more map. */
    @Test fun riding_collapsed() = shot("riding-collapsed") {
        withTabs(true) {
            NowRiding(
                riding, {}, {}, {}, flatMap,
                compact = CompactSheetUi("Harbour St · 6 min · alarm 7:45", false, "I'm off this bus"), expanded = false,
            )
        }
    }

    /** PLAN Run 11, B4: your stop's circle tapped on the map: its sizes. */
    @Test fun riding_circle() = shot("riding-circle") {
        withTabs(true) {
            NowRiding(riding, {}, {}, {}, flatMap, overlay = { CircleSheet(CircleSheetUi("Harbour St", 250.0, 250.0), {}, {}) })
        }
    }

    @Test fun riding_night() = shot("riding-night", dark = true) {
        withTabs(true) { NowRiding(riding.copy(alarm = "Alarm about 22:45"), {}, {}, {}, flatMap) }
    }

    @Test fun gps_lost() = shot("gps-lost") {
        withTabs(true) {
            NowRiding(TripSheetUi("Get off at", "Harbour St", "2.9 km · 40 s ago", true, "GPS lost. Alarm at 7:44 at the latest.", true, "I'm off this bus"), {}, {}, {}, flatMap)
        }
    }

    @Test fun transfer() = shot("transfer") {
        withTabs(true) {
            NowRiding(TripSheetUi("Off at Central Station · next stop", "Harbour St", "Waiting for bus 2", true, "Alarm set until you're on it", false, "Skip this bus"), {}, {}, {}, flatMap)
        }
    }

    @Test fun ring() = shot("ring", dark = true) { AlarmRing(RingTextUi("Wake up", "Harbour St in about 45 s"), 1_000, {}, holdProgress = 0.375f) }

    private val lines = PanelLines.combine(
        Digests.build(SampleChats.messages + SampleChats.urgent, 3, listOf("Daycare")),
        mapOf("com.whatsapp/Flat group" to BriefedLine("com.whatsapp/Flat group", "Saturday 7pm is decided; you're on snacks.", true, false, 6_000)),
        setOf("com.whatsapp/Daycare"),
    ).take(2)

    @Test fun awake() = shot("awake", dark = true) {
        AlarmAwake(AwakeUi("Get off at Harbour St", "About 45 s away. It's your last bus."), PanelText.AfterWake.CONTENT, lines, 3, {}, {}, {})
    }

    @Test fun awake_early() = shot("awake-early", dark = true) {
        AlarmAwake(
            AwakeUi("Stay on the bus", "GPS dropped before Harbour St, so it rang to be safe. It will ring again near your stop."),
            PanelText.AfterWake.NOTHING_NEW, emptyList(), 0, {}, {}, {},
        )
    }

    // ---- Run 9, M5: Routes, a route, set a stop ----

    @Test fun routes() = shot("routes") {
        withTabs(false, Tab.ROUTES) {
            RoutesList(
                listOf(RouteRowUi("work", "To work", "2 buses · 12 rides", false), RouteRowUi("home", "From work", "Hill Ave needs a stop", true)),
                RoutesActions(),
            )
        }
    }

    @Test fun route() = shot("route") {
        RouteDetail(
            RouteDetailUi(
                "From work",
                listOf(
                    StopRowUi(0, "Central Station", "Alarm 45 s before", StopRowUi.Kind.SET),
                    StopRowUi(1, "Park Rd", "No alarm", StopRowUi.Kind.NO_ALARM),
                    StopRowUi(2, "Hill Ave", "Set the stop", StopRowUi.Kind.NOT_SET),
                ),
                "Set Hill Ave", canAddBus = true,
            ),
            RouteDetailActions(),
        )
    }

    @Test fun stop() = shot("stop") {
        StopPicker(StopPickerUi("Hill Ave · wake me within", choice = 2, radiusM = 250.0), StopPickerActions(), flatMap)
    }

    // ---- Run 9, M6: Setup, Settings ----

    @Test fun setup() = shot("setup") {
        SetupScreen(
            listOf(
                SetupRowUi("notifications", "Notifications", "To show the alarm", true),
                SetupRowUi("full_screen", "Over the lock screen", "So you can stop it without unlocking", false),
                SetupRowUi("exact", "Exact alarms", "So it rings on time", false),
                SetupRowUi("buzz", "Buzz on Mute", "So it vibrates even when muted", false),
                SetupRowUi("location", "Location while riding", "Only during a trip you start", false),
            ),
            samsung = false, SetupActions(),
        )
    }

    @Test fun settings() = shot("settings") {
        withTabs(false, Tab.SETTINGS) {
            SettingsScreen(SettingsUi(quiet = true, speaker = false, ask = true, contacts = "Mum, Daycare", build = "Build 64"), SettingsActions())
        }
    }

    // ---- Run 9, M7: the trip notification on the lock screen ----

    @Test fun lock() = shot("lock", dark = true) {
        LockPreview(TripNoteUi("Harbour St in 6 min", "Alarm about 7:45", 68), "7:41", "Friday 20 November")
    }

    // ---- Run 12: finding stops ----

    @Test fun share() = shot("share") {
        ShareScreen(
            ShareText.of(
                app.nodstop.data.MapsLink.Result.One(app.nodstop.data.MapsLink.Place("Sky Tower", app.nodstop.engine.LatLng(-36.8485, 174.7622))),
                listOf(
                    app.nodstop.data.Route("w", "To work", listOf(app.nodstop.data.LegDraft("Central Station", "-36.80, 174.70"))),
                    app.nodstop.data.Route("h", "From work", listOf(app.nodstop.data.LegDraft("Park Rd", "-36.82, 174.72"))),
                ),
            ),
            {}, {},
        )
    }
}


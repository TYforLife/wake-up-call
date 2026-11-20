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

    private val flatMap: @Composable (Modifier) -> Unit = { m -> Box(m.background(LocalN.current.mapBg)) }

    private val routes = listOf(
        RouteOption("work", "To work", "Central Station, then Harbour St"),
        RouteOption("home", "From work", "3 buses · Hill Ave"),
    )

    @Composable
    private fun withTabs(live: Boolean, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            TabBar(Tab.NOW, live, {})
        }
    }

    private val riding = TripSheetUi("Get off at", "Harbour St", "6 min · 2.3 km", false, "Alarm about 7:45", false, "I'm off this bus")

    @Test fun now() = shot("now") { withTabs(false) { NowIdle(NowIdleUi("Good morning", routes, "work"), NowActions()) } }

    @Test fun now_not_ready() = shot("now-not-ready") { withTabs(false) { NowIdle(NowIdleUi("Good morning", routes, "work", notReady = 1), NowActions()) } }

    @Test fun riding() = shot("riding") { withTabs(true) { NowRiding(riding, {}, {}, {}, flatMap) } }

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
}

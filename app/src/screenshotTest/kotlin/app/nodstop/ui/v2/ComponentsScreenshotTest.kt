package app.nodstop.ui.v2

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every v2 component (PLAN Run 8, M1), light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi", application = Application::class)
class ComponentsScreenshotTest {
    private fun shot(name: String, content: @Composable () -> Unit) {
        for (dark in listOf(false, true)) {
            captureRoboImage("src/test/snapshots/v2c_${name}_${if (dark) "dark" else "light"}.png") {
                NodstopV2Theme(dark = dark) { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() } }
            }
        }
    }

    @Test fun buttons() = shot("buttons") {
        PrimaryButton("Start", {})
        GhostButton("I'm off this bus", {})
        PrimaryButton("Continue", {}, enabled = false)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Pill("Allow", {}); IconCircle(NIcons.Plus, "Add", {}); IconCircle(NIcons.Locate, "Recentre", {}, floating = true) }
    }

    @Test fun rows() = shot("rows") {
        Column {
            ListRow("To work", "2 buses · 12 rides", trailing = { Chevron() })
            ListRow("Exact alarms", "So it rings on time", trailing = { Pill("Allow", {}) })
            ListRow("From work", "Hill Ave needs a stop", subtitleDanger = true, divider = false, trailing = { Chevron() })
        }
    }

    @Test fun choices() = shot("choices") {
        Column {
            RadioRow("To work", "Central Station, then Harbour St", true, {})
            RadioRow("From work", "3 buses · Hill Ave", false, {}, divider = false)
        }
        Segmented(listOf("Auto", "150 m", "250 m", "400 m"), 2, {})
        Column {
            SwitchRow("Quiet other notifications", "During trips", true, {})
            SwitchRow("Ring out loud after 60 s", "If vibration didn't wake you", false, {}, divider = false)
        }
    }

    @Test fun trip_sheet() = shot("trip_sheet") {
        TripSheet(TripSheetUi("Get off at", "Harbour St", "6 min · 2.3 km", false, "Alarm about 7:45", false, "I'm off this bus"), {})
        TripSheet(TripSheetUi("Get off at", "Harbour St", "2.9 km · 40 s ago", true, "GPS lost. Alarm at 7:44 at the latest.", true, "I'm off this bus"), {})
    }

    @Test fun hold_and_tabs() = shot("hold_and_tabs") {
        HoldToStop(1_000, {}, shownProgress = 0.375f)
        TabBar(Tab.NOW, live = true, onSelect = {})
        TabBar(Tab.ROUTES, live = false, onSelect = {})
    }
}

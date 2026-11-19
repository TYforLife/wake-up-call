package app.nodstop.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import app.nodstop.alarm.RingService
import app.nodstop.brief.BriefedLine
import app.nodstop.brief.Digests
import app.nodstop.brief.PanelLines
import app.nodstop.brief.SampleChats
import app.nodstop.engine.AlarmReason
import app.nodstop.engine.RingPlan
import app.nodstop.notify.PanelText
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the alarm screens (PLAN U0), rendered on the JVM with Robolectric. Record with
 * `./gradlew :app:recordRoborazziDebug`; the PNGs live in `app/src/test/snapshots` and show up in PR diffs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class AlarmScreenshotTest {
    private val digest = Digests.build(SampleChats.messages + SampleChats.urgent, 3, listOf("Daycare"))
    private val lines = PanelLines.combine(
        digest,
        mapOf(
            "com.whatsapp/Flat group" to BriefedLine("com.whatsapp/Flat group", "Saturday 7pm is decided; Jess brings cake, you're on snacks.", true, false, 6_000),
            "com.Slack/Boss" to BriefedLine("com.Slack/Boss", "Meeting moved to 10:30; bring the Q3 slides.", true, true, 8_000),
        ),
        setOf("com.whatsapp/Daycare"),
    )
    private val summary = PanelText.summary(true, digest)

    private fun ring(reason: AlarmReason?, headset: Boolean = true) = RingService.RingUi(
        legName = "Smales Farm Stop B", overshoot = reason == AlarmReason.OVERSHOOT, startedElapsedMs = 0,
        step = RingPlan.step(5_000, headset, false, false), headset = headset, test = false, reason = reason,
    )

    private fun shot(name: String, content: @Composable () -> Unit) =
        captureRoboImage("src/test/snapshots/$name.png") { MaterialTheme(colorScheme = darkColorScheme()) { content() } }

    @Test
    fun ringing_with_briefing() = shot("alarm_ringing_with_briefing") {
        AlarmScreen(ring(AlarmReason.WAKE), 1_000, {}, {}) { WhileYouSleptContent(it, summary, lines) }
    }

    @Test
    fun ringing_urgent_no_earbuds() = shot("alarm_ringing_urgent_no_earbuds") {
        AlarmScreen(ring(AlarmReason.URGENT, headset = false), 1_000, {}, {}) { WhileYouSleptContent(it, summary, lines) }
    }

    @Test
    fun ringing_briefing_unavailable() = shot("alarm_ringing_briefing_unavailable") {
        AlarmScreen(ring(AlarmReason.FAILSAFE), 1_000, {}, {}) { WhileYouSleptContent(it, "Briefing unavailable.", emptyList()) }
    }

    @Test
    fun after_wake_with_share() = shot("alarm_after_wake_with_share") {
        AfterWakeScreen({}, {}) { WhileYouSleptContent(it, summary, lines, onShare = {}) }
    }

    @Test
    fun after_wake_nothing_new() = shot("alarm_after_wake_nothing_new") {
        AfterWakeScreen({}, {}, kind = PanelText.AfterWake.NOTHING_NEW) { WhileYouSleptContent(it, "Nothing new.", emptyList()) }
    }

    @Test
    fun after_wake_needs_access() = shot("alarm_after_wake_needs_access") {
        AfterWakeScreen({}, {}, kind = PanelText.AfterWake.NEEDS_ACCESS, onFix = {}) { WhileYouSleptContent(it, "Briefing unavailable.", emptyList()) }
    }
}

package app.nodstop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.nodstop.alarm.RingService
import app.nodstop.brief.BriefedLine
import app.nodstop.brief.Digests
import app.nodstop.brief.PanelLines
import app.nodstop.brief.SampleChats
import app.nodstop.engine.AlarmReason
import app.nodstop.engine.RingPlan
import app.nodstop.notify.PanelText
import org.junit.Rule
import org.junit.Test

/**
 * Screenshots of the alarm screens (PLAN U0), rendered on the JVM. Record with
 * `./gradlew :app:recordPaparazziDebug`; the PNGs land in `app/src/test/snapshots` and show up in PR diffs.
 */
class AlarmScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

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

    @Test
    fun ringing_with_briefing() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            AlarmScreen(ring(AlarmReason.WAKE), 1_000, {}, {}) { WhileYouSleptContent(it, summary, lines) }
        }
    }

    @Test
    fun ringing_urgent_no_earbuds() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            AlarmScreen(ring(AlarmReason.URGENT, headset = false), 1_000, {}, {}) { WhileYouSleptContent(it, summary, lines) }
        }
    }

    @Test
    fun ringing_briefing_unavailable() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            AlarmScreen(ring(AlarmReason.FAILSAFE), 1_000, {}, {}) { WhileYouSleptContent(it, "Briefing unavailable.", emptyList()) }
        }
    }

    @Test
    fun after_wake_with_share() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            AfterWakeScreen({}, {}) { WhileYouSleptContent(it, summary, lines, onShare = {}) }
        }
    }
}

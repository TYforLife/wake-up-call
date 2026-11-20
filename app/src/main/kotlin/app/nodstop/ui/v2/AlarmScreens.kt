package app.nodstop.ui.v2

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nodstop.App
import app.nodstop.alarm.RingService
import app.nodstop.brief.Digests
import app.nodstop.brief.PanelLine
import app.nodstop.brief.PanelLines
import app.nodstop.notify.MessageStore
import app.nodstop.notify.PanelText
import app.nodstop.trip.TripService

/**
 * The ring (mockup `ring`): black, like the phone's clock. "Wake up", where, one round button to hold.
 * Messages wait for the next screen. [holdProgress] fixes the ring for screenshots.
 */
@Composable
fun AlarmRing(text: RingTextUi, holdMs: Int, onAwake: () -> Unit, holdProgress: Float? = null) {
    val c = LocalN.current
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text.title, style = NType.alarm, color = c.fg, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 56.dp, start = 28.dp, end = 28.dp))
        Text(
            text.line, style = NType.rowBig.copy(fontWeight = NType.body.fontWeight), color = if (text.danger) c.danger else c.muted,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp, start = 28.dp, end = 28.dp),
        )
        Spacer(Modifier.weight(1f))
        HoldToStop(holdMs, onAwake, holdProgress)
        Text(
            "Hold for 1 second to stop\nor hold volume down", style = NType.secondary.copy(lineHeight = NType.body.lineHeight),
            color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp),
        )
    }
}

/** The ring as the live service sees it (the app's own screen and the lock-screen activity). */
@Composable
fun AlarmRingHost(ring: RingService.RingUi, onAwake: () -> Unit, holdMs: Int = 1_000) {
    val trip by TripService.status.collectAsState()
    val text = RingText.of(ring.legName, ring.reason, ring.overshoot, ring.test, trip?.status?.etaS)
    NodstopV2Theme(dark = true) { AlarmRing(text, holdMs, onAwake) }
}

/**
 * After "I'm awake" (mockup `awake`): what to do now comes first, then what you missed, then Done.
 * Tapping a message shares that line (Run 3's share-back); a missing briefing says how to turn it on.
 */
@Composable
fun AlarmAwake(
    now: AwakeUi,
    kind: PanelText.AfterWake,
    lines: List<PanelLine>,
    others: Int,
    onShare: (PanelLine) -> Unit,
    onFix: () -> Unit,
    onDone: () -> Unit,
) {
    val c = LocalN.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Eyebrow("Now")
            ScreenTitle(now.headline, xl = true)
            Lead(now.line)
            Text("While you slept", style = NType.eyebrow.copy(fontWeight = NType.button.fontWeight), color = c.muted, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            when (kind) {
                PanelText.AfterWake.CONTENT -> {
                    lines.take(PanelText.MAX_LINES).forEachIndexed { i, line ->
                        MessageRow(line, onShare, divider = i < lines.size - 1 || others > 0 || lines.size > PanelText.MAX_LINES)
                    }
                    val more = (lines.size - PanelText.MAX_LINES).coerceAtLeast(0)
                    if (more > 0 || others > 0) {
                        val parts = listOfNotNull(
                            more.takeIf { it > 0 }?.let { "$it more chat${if (it == 1) "" else "s"}" },
                            others.takeIf { it > 0 }?.let { "$it other notification${if (it == 1) "" else "s"}" },
                        )
                        ListRow(parts.joinToString(" · ").replaceFirstChar { it.uppercase() }, divider = false)
                    }
                }
                PanelText.AfterWake.NEEDS_ACCESS -> ListRow(
                    "Turn on While you slept", "See what you missed next time", divider = false, onClick = onFix, trailing = { Chevron() },
                )
                else -> ListRow(PanelText.afterWakeText(kind), divider = false)
            }
        }
        PrimaryButton("Done", onDone)
    }
}

@Composable
private fun MessageRow(line: PanelLine, onShare: (PanelLine) -> Unit, divider: Boolean) {
    val c = LocalN.current
    val urgent = PanelText.markers(line).any { it.kind == PanelText.MarkerKind.URGENT }
    Column(Modifier.fillMaxWidth().clickable { onShare(line) }) {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(PanelText.title(line), style = NType.row, color = c.fg)
                if (urgent) Text(
                    "Urgent", style = NType.tab.copy(fontWeight = NType.button.fontWeight), color = c.danger,
                    modifier = Modifier.border(1.dp, c.danger, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Text(line.text, style = NType.secondary, color = c.muted)
        }
        if (divider) HorizontalDivider(thickness = 1.dp, color = c.line)
    }
}

/** "You're awake" as the live app sees it: the trip's next step and the captured messages. */
@Composable
fun AlarmAwakeHost(test: Boolean, accessOk: Boolean, onShare: (PanelLine) -> Unit, onFix: () -> Unit, onDone: () -> Unit) {
    val trip by TripService.status.collectAsState()
    val snap by MessageStore.instance.state.collectAsState()
    val vips by App.instance.settings.vipNames.collectAsState()
    val digest = remember(snap, vips) { Digests.build(snap.messages, snap.otherNotifications, vips) }
    val lines = remember(snap, digest) { PanelLines.combine(digest, snap.briefed, snap.urgent) }
    val kind = PanelText.afterWake(snap.active, accessOk, digest)
    NodstopV2Theme(dark = true) {
        AlarmAwake(AwakeText.of(trip, test), kind, lines, digest.otherNotifications, onShare, onFix, onDone)
    }
}

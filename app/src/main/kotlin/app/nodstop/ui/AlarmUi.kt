package app.nodstop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nodstop.Notifications
import app.nodstop.alarm.RingService
import app.nodstop.brief.Digests
import app.nodstop.App
import app.nodstop.brief.PanelLine
import app.nodstop.brief.PanelLines
import app.nodstop.engine.AlarmReason
import app.nodstop.notify.MessageStore
import app.nodstop.notify.PanelText
import kotlinx.coroutines.launch

/**
 * The ringing screen, shared by the lock-screen alarm and the app itself. The whole bottom half is the
 * "I'm awake" button: press and hold for [holdMs] (it fills up and buzzes). A tap or a brush does nothing.
 */
@Composable
fun AlarmScreen(ring: RingService.RingUi?, holdMs: Int, onAwake: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E1630)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (ring == null) {
            Spacer(Modifier.height(1.dp))
            Text("No alarm is ringing.", color = Color.White, fontSize = 22.sp)
            TextButton(onClick = onClose) { Text("Close") }
            return@Column
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 32.dp)) {
            val urgent = ring.reason == AlarmReason.URGENT && !ring.overshoot
            Text(
                when {
                    ring.overshoot -> "You passed your stop"
                    urgent -> "Urgent message"
                    else -> "Wake up"
                },
                color = if (ring.overshoot) Color(0xFFFF6B6B) else Color(0xFFFFC94A),
                fontSize = 44.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    ring.test -> "Test ring"
                    urgent -> ring.legName?.let { "Still riding to $it" } ?: "Your stop alarm is still on"
                    ring.legName != null -> "Get off at ${ring.legName}"
                    else -> "Your stop is close"
                },
                color = Color.White, fontSize = 24.sp, textAlign = TextAlign.Center,
            )
            Notifications.reasonText(ring.reason)?.takeIf { !ring.overshoot }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFFFD27A), fontSize = 16.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (ring.headset) "Ringing in your earbuds" else "Vibrating (no earbuds connected)",
                color = Color(0xFF9AA7C7), fontSize = 15.sp,
            )
        }
        WhileYouSleptPanel(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp))
        HoldToAck(holdMs, onAwake)
    }
}

/**
 * After "I'm awake", while there's something to read: the panel with a Share button per line, and Done.
 * The messages are cleared when this screen closes (DESIGN §10, W3).
 */
@Composable
fun AfterWakeScreen(onShare: (PanelLine) -> Unit, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E1630)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("You're awake", color = Color(0xFFFFC94A), fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
        Text("Messages are cleared when you tap Done.", color = Color(0xFF9AA7C7), fontSize = 14.sp)
        WhileYouSleptPanel(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), onShare)
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Done", fontSize = 18.sp) }
    }
}

/** What you missed, from the trip's chat notifications (DESIGN §10). Memory only; cleared when this screen closes. */
@Composable
private fun WhileYouSleptPanel(modifier: Modifier, onShare: ((PanelLine) -> Unit)? = null) {
    val snap by MessageStore.instance.state.collectAsState()
    val vips by App.instance.settings.vipNames.collectAsState()
    val digest = remember(snap, vips) { Digests.build(snap.messages, snap.otherNotifications, vips) }
    val lines = remember(snap, digest) { PanelLines.combine(digest, snap.briefed, snap.urgent) }
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF17213F))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("While you slept", color = Color(0xFFFFC94A), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(PanelText.summary(snap.active, digest), color = Color(0xFFB8C3DD), fontSize = 15.sp)
        for (line in lines.take(PanelText.MAX_LINES)) PanelLineCard(line, onShare)
        PanelText.more(lines.size)?.let { Text(it, color = Color(0xFFB8C3DD), fontSize = 15.sp) }
    }
}

private fun markerColor(kind: PanelText.MarkerKind?): Color = when (kind) {
    PanelText.MarkerKind.URGENT -> Color(0xFFFF6B6B)
    PanelText.MarkerKind.REPLY -> Color(0xFFFFC94A)
    PanelText.MarkerKind.PLANS -> Color(0xFF7FB2FF)
    null -> Color(0xFF33416E)
}

/** One chat, readable half-asleep: a coloured edge for what matters, big text, labelled chips. */
@Composable
private fun PanelLineCard(line: PanelLine, onShare: ((PanelLine) -> Unit)?) {
    val markers = PanelText.markers(line)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E2A4D)).height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(markerColor(markers.firstOrNull()?.kind)))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(PanelText.title(line), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(line.text, color = Color(0xFFE6EBF8), fontSize = 17.sp, lineHeight = 23.sp)
            if (markers.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in markers) {
                    Text(
                        m.text,
                        color = Color(0xFF0E1630), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(markerColor(m.kind)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (onShare != null) {
                OutlinedButton(onClick = { onShare(line) }, modifier = Modifier.height(44.dp)) { Text("Share this line", fontSize = 15.sp) }
            }
        }
    }
}

@Composable
private fun HoldToAck(holdMs: Int, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val done = remember { booleanArrayOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.5f)
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xFF26345E))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val job = scope.launch {
                        progress.animateTo(1f, tween(durationMillis = holdMs, easing = LinearEasing))
                        if (!done[0]) {
                            done[0] = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDone()
                        }
                    }
                    tryAwaitRelease()
                    if (!done[0]) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(150)) }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        // Fill from the bottom as you hold.
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(progress.value).align(Alignment.BottomCenter)
                .background(Color(0xFFFFC94A).copy(alpha = 0.85f)),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("I'm awake", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text("Press and hold anywhere here", color = Color(0xFFDDE3F5), fontSize = 16.sp)
            Text("or hold volume down", color = Color(0xFF9AA7C7), fontSize = 14.sp)
        }
    }
}

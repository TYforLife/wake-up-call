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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
            Text(
                if (ring.overshoot) "You passed your stop" else "Wake up",
                color = if (ring.overshoot) Color(0xFFFF6B6B) else Color(0xFFFFC94A),
                fontSize = 44.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    ring.test -> "Test ring"
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
        HoldToAck(holdMs, onAwake)
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

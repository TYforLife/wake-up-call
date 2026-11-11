package app.nodstop.alarm

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nodstop.trip.TripService
import kotlinx.coroutines.launch

/**
 * Shown over the lock screen while ringing. Only a 1.5 s hold on "I'm awake" stops the ring:
 * a pocket brush, a tap, back, or the volume keys do nothing.
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ring by RingService.state.collectAsState()
                AlarmScreen(
                    ring = ring,
                    onAwake = { awake(this); finish() },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) true
        else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) true
        else super.onKeyUp(keyCode, event)

    companion object {
        /** "I'm awake": stop the ring and advance the trip; if the trip process was killed, resume at the next leg. */
        fun awake(ctx: Context) {
            val wasTest = RingService.state.value?.test == true
            RingService.stop(ctx)
            val svc = TripService.instance
            when {
                svc != null -> svc.ack()
                !wasTest -> TripService.resumeAfterColdRing(ctx)
            }
        }
    }
}

@Composable
private fun AlarmScreen(ring: RingService.RingUi?, onAwake: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E1630)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        if (ring == null) {
            Text("No alarm is ringing.", color = Color.White, fontSize = 22.sp)
            TextButton(onClick = onClose) { Text("Close") }
            return@Column
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Wake up", color = Color(0xFFFFC94A), fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    ring.test -> "Test ring"
                    ring.legName != null -> "Get off at ${ring.legName}"
                    else -> "Your stop is close"
                },
                color = Color.White, fontSize = 24.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (ring.headset) "Ringing in your earbuds" else "Vibrating (no earbuds connected)",
                color = Color(0xFF9AA7C7), fontSize = 15.sp,
            )
        }
        if (ring.overshoot) {
            Text(
                "You passed your stop",
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFD32F2F)).padding(16.dp),
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
        }
        HoldToAck(onAwake)
    }
}

@Composable
private fun HoldToAck(onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val done = remember { booleanArrayOf(false) }
    Box(
        Modifier.size(240.dp).clip(CircleShape).background(Color(0xFF26345E)).pointerInput(Unit) {
            detectTapGestures(onPress = {
                val job = scope.launch {
                    progress.animateTo(1f, tween(durationMillis = 1_500, easing = LinearEasing))
                    if (!done[0]) { done[0] = true; onDone() }
                }
                tryAwaitRelease()
                if (!done[0]) {
                    job.cancel()
                    scope.launch { progress.animateTo(0f, tween(200)) }
                }
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.size(228.dp),
            color = Color(0xFFFFC94A),
            strokeWidth = 10.dp,
            trackColor = Color(0x33FFFFFF),
        )
        Text("Hold\nI'm awake", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

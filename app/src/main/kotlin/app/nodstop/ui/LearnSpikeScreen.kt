package app.nodstop.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nodstop.engine.LearnSpike
import app.nodstop.learn.LearnSpikeService
import app.nodstop.learn.SpikeLog
import app.nodstop.openIfPossible
import app.nodstop.ui.v2.hm

/**
 * Debug → Learn spike (docs/LEARN.md L0): record one ride's raw signals, mark when you get on and off, then share
 * the file (location hidden) so the report can be written from it. A test tool: plain, not designed.
 */
@Composable
fun LearnSpikeScreen(go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val running by LearnSpikeService.running.collectAsState()
    val events by SpikeLog.events.collectAsState()
    val permTick = remember { mutableIntStateOf(0) }
    val allowed = remember(permTick.intValue) { LearnSpikeService.hasActivityPermission(ctx) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick.intValue++ }
    LaunchedEffect(Unit) { SpikeLog.loadLast(ctx) }
    val report = remember(events) { LearnSpike.reportLines(LearnSpike.report(events)) }
    val timeline = remember(events) { LearnSpike.timeline(events, ::hm).asReversed().take(300) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopBar("Learn spike", onBack = { go(Screen.Debug) })

        Section(null) {
            Text("Records one bus ride: GPS, what the phone thinks you're doing, and the battery.")
            Text("There's no alarm here. To sleep, start a trip as usual too: both can run at once.")
            Text("Tap Got on and Got off as it happens (also in the notification).", style = MaterialTheme.typography.bodySmall)
        }

        if (!allowed) Section("Activity recognition is off") {
            Text("Without it, only GPS is recorded, and the spike can't answer its main question.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { ask.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) { Text("Allow") }
        }

        Section(if (running) "Recording" else null) {
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { LearnSpikeService.mark(ctx, LearnSpike.BUS_ON) }, Modifier.weight(1f)) { Text("Got on bus") }
                    Button(onClick = { LearnSpikeService.mark(ctx, LearnSpike.BUS_OFF) }, Modifier.weight(1f)) { Text("Got off bus") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { LearnSpikeService.mark(ctx, LearnSpike.CAR_ON) }, Modifier.weight(1f)) { Text("Got in car") }
                    OutlinedButton(onClick = { LearnSpikeService.mark(ctx, LearnSpike.CAR_OFF) }, Modifier.weight(1f)) { Text("Out of car") }
                }
                OutlinedButton(onClick = { LearnSpikeService.stop(ctx) }) { Text("Stop recording") }
            } else {
                Button(onClick = { LearnSpikeService.start(ctx) }) { Text("Start recording") }
            }
        }

        if (events.isNotEmpty()) {
            Section("Report") {
                for (line in report) Text(line, style = MaterialTheme.typography.bodySmall)
                Button(enabled = !running, onClick = { SpikeLog.shareIntent(ctx)?.let { ctx.openIfPossible(it) } }) {
                    Text("Share (location hidden)")
                }
                if (running) Text("Stop recording first.", style = MaterialTheme.typography.bodySmall)
            }
            Section("Timeline") {
                for (line in timeline) {
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

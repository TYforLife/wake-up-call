package app.nodstop.ui

import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nodstop.App
import app.nodstop.trip.TripService

@Composable
fun DebugScreen(go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val app = App.instance
    val trip by TripService.status.collectAsState()
    val recent by app.traces.recent.collectAsState()
    var km by remember { mutableStateOf("6") }
    var kmh by remember { mutableStateOf("50") }
    var testAt by remember { mutableStateOf<Long?>(null) }
    var confirmKill by remember { mutableStateOf(false) }
    val tripActive = trip != null || app.trips.load() != null
    LaunchedEffect(Unit) { app.traces.loadRecentFromDisk() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopBar("Debug", onBack = { go(Screen.Home) })

        Section("Test ring") {
            Text("Schedules the real system alarm 30 s from now (the same path a trip uses). Lock the phone and wait.")
            Button(enabled = !tripActive, onClick = {
                val at = System.currentTimeMillis() + 30_000
                app.scheduler.set(at)
                testAt = at
            }) { Text("Test ring in 30 s") }
            if (tripActive) Text("Disabled while a trip is running: it would replace the trip's alarm.", style = MaterialTheme.typography.bodySmall)
            testAt?.let { Text("Scheduled for ${TripService.clock(it)}") }
        }

        Section("Simulated trip") {
            Text(
                "A pretend bus, N km before stop 1 at X km/h, in real time, through the full engine and alarm path. " +
                    "It halts 90 s at stop 1 (you 'get off and wait'), then drives 3 km to stop 2. Don't ack to test 'you passed your stop'.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(km, { km = it }, label = { Text("km out") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(kmh, { kmh = it }, label = { Text("km/h") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            val k = km.toDoubleOrNull()
            val v = kmh.toDoubleOrNull()
            Button(enabled = !tripActive && k != null && k > 0 && v != null && v > 0, onClick = {
                if (Readiness.canStartTrip(ctx)) { TripService.startSim(ctx, k!!, v!!); go(Screen.Trip) } else go(Screen.Readiness)
            }) { Text("Start simulated trip") }
        }

        Section("Kill test") {
            Text("Kills the app process now, like One UI would. The scheduled alarm must still ring.")
            OutlinedButton(onClick = { confirmKill = true }) { Text("Kill app now") }
        }

        Section("Learned stops") {
            val learned by app.learned.all.collectAsState()
            Text("${learned.size} stop${if (learned.size == 1) "" else "s"} learned from ${learned.values.sumOf { it.rides }} rides.")
            OutlinedButton(enabled = learned.isNotEmpty(), onClick = { app.learned.forgetAll() }) { Text("Forget all learned stops") }
        }

        Section("Trip log") {
            Button(enabled = app.traces.lastTripFile() != null, onClick = {
                app.traces.shareIntent(hideLocation = true)?.let { runCatching { ctx.startActivity(it) } }
            }) { Text("Share last trip log (location hidden)") }
            Text("Safe for the public repo: every position is moved far away, and it still replays exactly.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = app.traces.lastTripFile() != null, onClick = {
                app.traces.shareIntent(hideLocation = false)?.let { runCatching { ctx.startActivity(it) } }
            }) { Text("Share raw log (private: shows where you ride)") }
            Text(app.traces.lastTripFile()?.name ?: "No trips yet", style = MaterialTheme.typography.bodySmall)
        }

        Section("Last ${recent.size} trace events") {
            for (line in recent.asReversed()) {
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (confirmKill) Confirm(
        "Kill the app?", "The process ends immediately. Any scheduled alarm stays set.", "Kill",
        onConfirm = { Process.killProcess(Process.myPid()) }, onDismiss = { confirmKill = false },
    )
}

package app.nodstop.ui

import app.nodstop.openIfPossible
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
import app.nodstop.brief.SampleChats
import app.nodstop.notify.MessageStore
import app.nodstop.trip.TripService
import app.nodstop.ui.v2.SwitchRow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import app.nodstop.data.DataExport
import app.nodstop.data.ExportBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val importScope = rememberCoroutineScope()
    var importMessage by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<ExportBundle?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                } catch (_: java.io.IOException) {
                    null
                } catch (_: SecurityException) {
                    null // the file's app took back access
                }
            }
            val b = text?.let { DataExport.decode(it) }
            if (b == null) importMessage = "That isn't a Nodstop backup." else pendingImport = b
        }
    }
    pendingImport?.let { b ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Restore this backup?") },
            text = { Text("${b.routes.size} routes and ${b.learned.size} learned stops will replace what's on this phone now.") },
            confirmButton = { TextButton(onClick = { DataExport.restore(b); importMessage = "Restored ${b.routes.size} routes."; pendingImport = null }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }
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
            val k = km.toDoubleOrNull()?.takeIf { it > 0 }
            val v = kmh.toDoubleOrNull()?.takeIf { it > 0 }
            Button(enabled = !tripActive && k != null && v != null, onClick = {
                if (k == null || v == null) return@Button
                if (Readiness.canStartTrip(ctx)) { TripService.startSim(ctx, k, v); go(Screen.Trip) } else go(Screen.Setup)
            }) { Text("Start simulated trip") }
        }

        Section("Learn spike") {
            Text("Record one bus ride's raw signals for learning trips by riding (no alarm). Share the file afterwards.")
            Button(onClick = { go(Screen.LearnSpike) }) { Text("Learn spike") }
        }

        Section("While you slept") {
            val tea by app.settings.teaTone.collectAsState()
            val gpu by app.settings.useGpu.collectAsState()
            SwitchRow("Tea tone", "Briefing lines in group-chat voice. Same facts, checked again.", tea, { app.settings.setTeaTone(it) })
            SwitchRow(
                "Use the GPU", "Often faster and lighter on the battery; falls back to the CPU. Trips use it only after the Briefing lab has run on the GPU once.",
                gpu, { app.settings.setUseGpu(it) }, divider = false,
            )
            Text("Import a Gemma model and time it on made-up chats (Decision C).")
            Button(onClick = { go(Screen.BriefingLab) }) { Text("Briefing lab") }
            Text(
                "Couch test without real chats: start a simulated trip, then add made-up chats to it. " +
                    "Add \"Daycare\" to Settings → Important contacts to test the urgent wake.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(enabled = tripActive && MessageStore.instance.isActive, onClick = {
                val now = System.currentTimeMillis()
                (SampleChats.messages + SampleChats.urgent).forEachIndexed { i, m -> MessageStore.instance.add(m.copy(app = "debug.sample", tMs = now + i)) }
            }) { Text("Add sample chats to this trip") }
        }
        Section("Your data") {
            Text(
                "Back up routes, stops, learned rides and settings to one file, or restore them. Updates keep your data " +
                    "anyway; this is for a new phone or a reinstall. The file has your stops: keep it private.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { ctx.openIfPossible(DataExport.shareIntent(ctx, System.currentTimeMillis())) }) { Text("Back up to a file") }
            OutlinedButton(enabled = !tripActive, onClick = { importPicker.launch(arrayOf("application/json", "*/*")) }) { Text("Restore from a file") }
            importMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
                app.traces.shareIntent(hideLocation = true)?.let { ctx.openIfPossible(it) }
            }) { Text("Share last trip log (location hidden)") }
            Text("Safe for the public repo: every position is moved far away, and it still replays exactly.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = app.traces.lastTripFile() != null, onClick = {
                app.traces.shareIntent(hideLocation = false)?.let { ctx.openIfPossible(it) }
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

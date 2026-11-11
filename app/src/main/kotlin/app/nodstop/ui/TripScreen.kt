package app.nodstop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nodstop.engine.Phase
import app.nodstop.trip.TripService
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TripScreen(go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val trip by TripService.status.collectAsState()
    var confirmStop by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopBar("Trip", onBack = { go(Screen.Home) })
        val t = trip
        if (t == null) {
            Text("No trip running.")
            return@Column
        }
        val s = t.status
        Section(t.routeName) {
            Text("Leg ${t.legIndex + 1} of ${t.legNames.size}: ${t.legName ?: ""}", style = MaterialTheme.typography.titleMedium)
            s.holdUntilMs?.let { Text("Transfer: tracking the next bus from ${TripService.clock(it)}") }
            KeyValue("Phase", when (s.phase) {
                Phase.RIDING -> "Riding"; Phase.RINGING -> "Ringing"; Phase.DONE -> "Done"; Phase.IDLE -> "Idle"
            })
            KeyValue("Distance (straight line)", s.distanceM?.let { "${fmt(it / 1000)} km" } ?: "—")
            KeyValue("ETA", s.etaS?.let { "${(it / 60).roundToInt()} min ${(it % 60).roundToInt()} s" } ?: "—")
            KeyValue("Speed estimate", s.vEstKmh?.let { "${it.roundToInt()} km/h" } ?: "—")
            val age = s.lastFixAgeS
            KeyValue("GPS", when {
                age == null -> "waiting for a fix"
                s.gpsOk -> "ok (${age.roundToInt()} s ago)"
                else -> "lost (${age.roundToInt()} s ago)"
            })
            KeyValue("Alarm", s.alarmAtMs?.let { TripService.clock(it) } ?: if (s.phase == Phase.RINGING) "ringing" else "none")
        }
        Text(
            "The alarm is a real system alarm. If the app is closed, it still rings, never later than safe.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(modifier = Modifier.fillMaxWidth(), onClick = { TripService.instance?.skipLeg() }) {
            Text(if (t.legIndex + 1 < t.legNames.size) "I'm off this bus (next leg)" else "I'm off this bus (end trip)")
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { confirmStop = true }) { Text("Stop trip") }
    }
    if (confirmStop) Confirm(
        "Stop trip?", "No alarm will ring for this trip.", "Stop trip",
        onConfirm = { TripService.stop(ctx); go(Screen.Home) }, onDismiss = { confirmStop = false },
    )
}

private fun fmt(x: Double) = String.format(Locale.US, "%.2f", x)

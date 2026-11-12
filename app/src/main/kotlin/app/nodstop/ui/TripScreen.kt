package app.nodstop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import app.nodstop.engine.AlarmReason
import app.nodstop.engine.Phase
import app.nodstop.trip.TripService
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
            Text(TripService.phaseLabel(t), style = MaterialTheme.typography.headlineSmall)
            val start = s.startDistanceM
            val d = s.distanceM
            if (start != null && d != null && start > 0) {
                LinearProgressIndicator(progress = { (1 - d / start).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            KeyValue("Distance to stop", d?.let { TripService.distance(it) + if (s.onLearnedRoute) " (along the route)" else " (straight line)" } ?: "—")
            KeyValue("ETA", s.etaS?.let { TripService.minutes(it) } ?: if (s.phase == Phase.WAITING) "when the bus moves" else "—")
            KeyValue("Speed", s.speedKmh?.let { "${it.roundToInt()} km/h" } ?: "—")
            val age = s.lastFixAgeS
            KeyValue("GPS", when {
                age == null -> "waiting for a fix"
                s.gpsOk -> "ok (${age.roundToInt()} s ago)"
                else -> "lost (${age.roundToInt()} s ago)"
            })
            KeyValue("Alarm", s.alarmAtMs?.let { "${TripService.clock(it)} · ${reasonLabel(s.alarmReason)}" } ?: if (s.phase == Phase.RINGING) "ringing" else "none")
            KeyValue("Route knowledge", if (s.learnedRides > 0) "learned from ${s.learnedRides} ride${if (s.learnedRides == 1) "" else "s"}" else "first ride: learning it now")
        }
        Text(
            "Rings about 45 s before your stop. It's a real system alarm: if the app is closed, it still rings, never later than safe.",
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

private fun reasonLabel(r: AlarmReason?): String = when (r) {
    AlarmReason.WAKE -> "~45 s before your stop"
    AlarmReason.FAILSAFE -> "backup if GPS stops"
    AlarmReason.NO_GPS -> "no GPS yet"
    AlarmReason.OVERSHOOT -> "you passed your stop"
    AlarmReason.NEXT_LEG -> "backup for the next bus"
    null -> ""
}

package app.nodstop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
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
    TripContent(
        trip, onBack = { go(Screen.Home) }, onOff = { TripService.instance?.skipLeg() },
        onMap = { go(Screen.Map) }, onStop = { confirmStop = true },
    )
    if (confirmStop) Confirm(
        "Stop trip?", "No alarm will ring for this trip.", "Stop trip",
        onConfirm = { TripService.stop(ctx); go(Screen.Home) }, onDismiss = { confirmStop = false },
    )
}

@Composable
fun TripContent(trip: TripService.TripUi?, onBack: () -> Unit, onOff: () -> Unit, onMap: () -> Unit, onStop: () -> Unit) {
    ScreenColumn("Trip", onBack) {
        val t = trip
        if (t == null) {
            Section { Text("No trip running.", style = MaterialTheme.typography.bodyLarge) }
            return@ScreenColumn
        }
        val s = t.status
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatusChip(TripService.phaseLabel(t), if (s.phase == Phase.RINGING) Tone.BAD else Tone.INFO)
            StatusChip(
                s.lastFixAgeS.let { age ->
                    when {
                        age == null -> "Waiting for GPS"
                        s.gpsOk -> "GPS ok"
                        else -> "GPS lost ${age.roundToInt()} s"
                    }
                },
                if (s.gpsOk) Tone.OK else Tone.WARN,
            )
        }
        // Glanceable at arm's length: where, how far, how long.
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Hint("${t.routeName} · leg ${t.legIndex + 1} of ${t.legNames.size} · get off at")
            Text(t.legName ?: "your stop", style = MaterialTheme.typography.displaySmall)
        }
        Section {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                BigStat(s.distanceM?.let { TripService.distance(it) } ?: "—", if (s.onLearnedRoute) "along the route" else "straight line")
                BigStat(s.etaS?.let { TripService.minutes(it) } ?: "—", if (s.phase == Phase.WAITING) "once the bus moves" else "to go")
            }
            val start = s.startDistanceM
            val d = s.distanceM
            if (start != null && d != null && start > 0) Progress((1 - d / start).toFloat())
            val alarm = s.alarmAtMs
            Text(
                if (alarm != null) "Alarm ${TripService.clock(alarm)} · ${reasonLabel(s.alarmReason, s.leadS)}"
                else if (s.phase == Phase.RINGING) "Ringing now" else "No alarm on this leg",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        PrimaryButton(if (t.legIndex + 1 < t.legNames.size) "I'm off this bus · next leg" else "I'm off this bus · end trip", onOff)
        SecondaryButton("Show on map", onMap)
        Section("Details") {
            KeyValue("ETA from", when {
                s.onLearnedRoute && s.pace != null -> "past rides × today's pace (${String.format(java.util.Locale.US, "%.1f", s.pace)}×)"
                s.onLearnedRoute -> "past rides"
                else -> "your speed this ride"
            })
            KeyValue("Speed", s.speedKmh?.let { "${it.roundToInt()} km/h" } ?: "—")
            KeyValue("Route knowledge", if (s.learnedRides > 0) "${s.learnedRides} past ride${if (s.learnedRides == 1) "" else "s"}" else "first ride: learning")
            Hint("A real system alarm: if the app is closed, it still rings, never later than safe.")
        }
        QuietButton("Stop trip", onStop, danger = true, modifier = Modifier.fillMaxWidth())
    }
}

private fun reasonLabel(r: AlarmReason?, leadS: Double?): String = when (r) {
    AlarmReason.WAKE -> "~${(leadS ?: 45.0).roundToInt()} s before your stop"
    AlarmReason.FAILSAFE -> "backup if GPS stops"
    AlarmReason.NO_GPS -> "no GPS yet"
    AlarmReason.OVERSHOOT -> "you passed your stop"
    AlarmReason.NEXT_LEG -> "backup for the next bus"
    AlarmReason.URGENT -> "an urgent message"
    null -> ""
}

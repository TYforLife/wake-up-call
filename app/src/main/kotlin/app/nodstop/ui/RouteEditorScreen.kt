package app.nodstop.ui

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nodstop.App
import app.nodstop.data.LegDraft
import app.nodstop.data.Route
import app.nodstop.engine.CoordParser
import app.nodstop.engine.Geo
import app.nodstop.trip.FusedLocationSource
import kotlin.math.roundToInt
import java.util.Locale

@Composable
fun RouteEditorScreen(routeId: String?, onDone: () -> Unit) {
    val store = App.instance.routes
    val original = remember(routeId) { routeId?.let { store.get(it) } }
    var name by remember { mutableStateOf(original?.name ?: "") }
    val legs = remember { mutableStateListOf<LegDraft>().apply { addAll(original?.legs ?: listOf(LegDraft())) } }
    var confirmDelete by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf<Int?>(null) }
    var locateError by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val learnedAll by App.instance.learned.all.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopBar(if (original == null) "New route" else "Edit route", onBack = onDone)
        OutlinedTextField(name, { name = it }, label = { Text("Route name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

        legs.forEachIndexed { i, leg ->
            Section("Leg ${i + 1}") {
                OutlinedTextField(
                    leg.name, { legs[i] = leg.copy(name = it) }, label = { Text("Get off at (stop name)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                OutlinedTextField(
                    leg.coords, { legs[i] = leg.copy(coords = it) }, label = { Text("Where you get off: coordinates or a Maps link") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val p = CoordParser.parse(leg.coords)
                Text(
                    when {
                        leg.coords.isBlank() -> "Paste from Google Maps (long-press the stop, copy the coordinates), or use your location at the stop."
                        p == null -> "Can't read that. Paste \"-36.78, 174.75\" or a Maps link."
                        !CoordParser.isInNewZealand(p) -> "Warning: ${fmt(p.lat)}, ${fmt(p.lng)} is outside New Zealand."
                        else -> "✓ ${fmt(p.lat)}, ${fmt(p.lng)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        leg.coords.isBlank() -> MaterialTheme.colorScheme.onSurfaceVariant
                        p == null || !CoordParser.isInNewZealand(p) -> MaterialTheme.colorScheme.error
                        else -> Color(0xFF2E7D32)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        locating = i
                        FusedLocationSource(ctx).current { loc ->
                            locating = null
                            if (loc != null) legs[i] = legs[i].copy(coords = String.format(Locale.US, "%.6f, %.6f", loc.latitude, loc.longitude))
                            else locateError = "Couldn't get your location. Check Readiness → Precise location."
                        }
                    }, enabled = locating == null) { Text(if (locating == i) "Locating…" else "Use my location") }
                }
                Text(
                    "Standing at the stop? Tap Use my location. Close is fine: after a ride or two the app learns exactly where you get off.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (p != null) {
                    val learned = learnedAll[Route.stopKey(p)]
                    if (learned != null) {
                        val shift = Geo.distanceM(learned.stop, p).roundToInt()
                        Text(
                            "Learned the route from ${learned.rides} ride${if (learned.rides == 1) "" else "s"}. " +
                                "Suggestion: you got off about $shift m from your pin. Your pin stays the stop unless you move it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (shift >= 20) TextButton(onClick = {
                                // A new pin starts learning afresh (past routes were measured to the old pin).
                                App.instance.learned.forget(p)
                                legs[i] = legs[i].copy(coords = String.format(Locale.US, "%.6f, %.6f", learned.stop.lat, learned.stop.lng))
                            }) { Text("Use suggestion") }
                            TextButton(onClick = { App.instance.learned.forget(p) }) { Text("Forget learned") }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Alarm for this leg", Modifier.weight(1f))
                    Switch(leg.alarmEnabled, { legs[i] = leg.copy(alarmEnabled = it) })
                }
                if (legs.size > 1) TextButton(onClick = { legs.removeAt(i) }) { Text("Remove leg") }
            }
        }
        if (legs.size < 3) OutlinedButton(onClick = { legs += LegDraft() }) { Text("+ Add leg") }
        locateError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = name.isNotBlank(), onClick = {
                val r = (original ?: Route(name = name, legs = emptyList())).copy(name = name.trim(), legs = legs.toList())
                store.upsert(r)
                onDone()
            }) { Text("Save") }
            if (original != null) OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }
    }
    if (confirmDelete && original != null) Confirm(
        "Delete ${original.name}?", "This can't be undone.", "Delete",
        onConfirm = { store.delete(original.id); onDone() }, onDismiss = { confirmDelete = false },
    )
}

private fun fmt(x: Double) = String.format(Locale.US, "%.5f", x)

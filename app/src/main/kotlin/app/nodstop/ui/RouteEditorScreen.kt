package app.nodstop.ui

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.nodstop.App
import app.nodstop.data.LegDraft
import app.nodstop.data.Route
import app.nodstop.engine.CoordParser
import java.util.Locale

@Composable
fun RouteEditorScreen(routeId: String?, onDone: () -> Unit) {
    val store = App.instance.routes
    val original = remember(routeId) { routeId?.let { store.get(it) } }
    var name by remember { mutableStateOf(original?.name ?: "") }
    val legs = remember { mutableStateListOf<LegDraft>().apply { addAll(original?.legs ?: listOf(LegDraft())) } }
    var confirmDelete by remember { mutableStateOf(false) }

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
                    leg.coords, { legs[i] = leg.copy(coords = it) }, label = { Text("Stop coordinates or Google Maps link") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val p = CoordParser.parse(leg.coords)
                Text(
                    when {
                        leg.coords.isBlank() -> "In Google Maps: search the stop number, long-press its pin, copy the coordinates, paste here."
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
                    NumberField("Ride min", leg.rideMin, Modifier.weight(1f)) { legs[i] = leg.copy(rideMin = it) }
                    if (i > 0) NumberField("Wait before, min", leg.waitMin, Modifier.weight(1f)) { legs[i] = leg.copy(waitMin = it) }
                }
                Text(
                    "Use your fastest usual ride: if GPS never works, the alarm rings at ride time minus 2.5 min.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Alarm for this leg", Modifier.weight(1f))
                    Switch(leg.alarmEnabled, { legs[i] = leg.copy(alarmEnabled = it) })
                }
                if (legs.size > 1) TextButton(onClick = { legs.removeAt(i) }) { Text("Remove leg") }
            }
        }
        if (legs.size < 3) OutlinedButton(onClick = { legs += LegDraft(waitMin = 5) }) { Text("+ Add leg") }

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

@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value > 0) value.toString() else "") }
    OutlinedTextField(
        text,
        { t -> text = t.filter(Char::isDigit).take(3); onChange(text.toIntOrNull() ?: 0) },
        label = { Text(label) }, modifier = modifier, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private fun fmt(x: Double) = String.format(Locale.US, "%.5f", x)

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
import app.nodstop.engine.Learned
import app.nodstop.engine.LatLng
import app.nodstop.trip.FusedLocationSource
import kotlin.math.roundToInt
import java.util.Locale

class EditorActions(
    val name: (String) -> Unit = {},
    val leg: (Int, LegDraft) -> Unit = { _, _ -> },
    val locate: (Int) -> Unit = {},
    val forget: (LatLng) -> Unit = {},
    val addLeg: () -> Unit = {},
    val removeLeg: (Int) -> Unit = {},
    val save: () -> Unit = {},
    val delete: () -> Unit = {},
    val back: () -> Unit = {},
)

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

    RouteEditorContent(
        isNew = original == null, name = name, legs = legs, learned = { learnedAll[Route.stopKey(it)] },
        locating = locating, error = locateError,
        a = EditorActions(
            name = { name = it },
            leg = { i, l -> legs[i] = l },
            locate = { i ->
                locating = i
                FusedLocationSource(ctx).current { loc ->
                    locating = null
                    if (loc != null) legs[i] = legs[i].copy(coords = String.format(Locale.US, "%.6f, %.6f", loc.latitude, loc.longitude))
                    else locateError = "Couldn't get your location. Check Readiness → Precise location."
                }
            },
            forget = { App.instance.learned.forget(it) },
            addLeg = { legs += LegDraft() },
            removeLeg = { legs.removeAt(it) },
            save = {
                val r = (original ?: Route(name = name, legs = emptyList())).copy(name = name.trim(), legs = legs.toList())
                store.upsert(r)
                onDone()
            },
            delete = { confirmDelete = true },
            back = onDone,
        ),
    )
    if (confirmDelete && original != null) Confirm(
        "Delete ${original.name}?", "This can't be undone.", "Delete",
        onConfirm = { store.delete(original.id); onDone() }, onDismiss = { confirmDelete = false },
    )
}

@Composable
fun RouteEditorContent(
    isNew: Boolean, name: String, legs: List<LegDraft>, learned: (LatLng) -> Learned?,
    locating: Int?, error: String?, a: EditorActions,
) {
    ScreenColumn(if (isNew) "New route" else "Edit route", a.back) {
        Section {
            OutlinedTextField(name, a.name, label = { Text("Route name, e.g. To work") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Hint("A route is the buses you take, in order: one leg per bus, each ending where you get off.")
        }
        legs.forEachIndexed { i, leg ->
            GroupLabel("Bus ${i + 1}")
            Section {
                OutlinedTextField(
                    leg.name, { a.leg(i, leg.copy(name = it)) }, label = { Text("Stop you get off at") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                val p = CoordParser.parse(leg.coords)
                Text("Where it is", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Space.xs))
                PrimaryButton(
                    if (locating == i) "Locating…" else if (p == null) "I'm at the stop: use my location" else "Use my location instead",
                    { a.locate(i) }, enabled = locating == null,
                )
                OutlinedTextField(
                    leg.coords, { a.leg(i, leg.copy(coords = it)) }, label = { Text("…or paste coordinates or a Maps link") },
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    leg.coords.isBlank() -> Hint("In Google Maps, long-press the stop and copy the coordinates.")
                    p == null -> StatusChip("Can't read that: paste \"-36.78, 174.75\" or a Maps link", Tone.BAD)
                    !CoordParser.isInNewZealand(p) -> StatusChip("${fmt(p.lat)}, ${fmt(p.lng)} is outside New Zealand", Tone.WARN)
                    else -> StatusChip("Pinned · ${fmt(p.lat)}, ${fmt(p.lng)}", Tone.OK)
                }
                if (p != null) {
                    val l = learned(p)
                    if (l != null && (l.rides > 0 || l.leadS != null)) Row(verticalAlignment = Alignment.CenterVertically) {
                        Hint(
                            listOfNotNull(
                                l.rides.takeIf { it > 0 }?.let { "Learned from $it ride${if (it == 1) "" else "s"}" },
                                l.leadS?.let { "rings ~${it.roundToInt()} s before (your ratings)" },
                            ).joinToString(" · "),
                            Modifier.weight(1f),
                        )
                        QuietButton("Forget", { a.forget(p) }, danger = true)
                    }
                }
                SettingRow("Wake me for this stop", if (leg.alarmEnabled) null else "No alarm: e.g. a short hop you stay awake for", leg.alarmEnabled) {
                    a.leg(i, leg.copy(alarmEnabled = it))
                }
                if (legs.size > 1) QuietButton("Remove this bus", { a.removeLeg(i) }, danger = true)
            }
        }
        if (legs.size < 3) SecondaryButton("+ Add another bus", a.addLeg)
        error?.let { StatusChip(it, Tone.BAD) }
        PrimaryButton("Save", a.save, enabled = name.isNotBlank())
        if (!isNew) QuietButton("Delete route", a.delete, danger = true, modifier = Modifier.fillMaxWidth())
    }
}

private fun fmt(x: Double) = String.format(Locale.US, "%.5f", x)

package app.nodstop.ui.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ---- Plan a trip (PLAN Run 13, T4) ----

enum class PlanField { FROM, TO }

data class PlanUi(
    val title: String,
    val from: String,
    val to: String,
    /** The field you're typing in (its suggestions show), or null. */
    val editing: PlanField? = null,
    val query: String = "",
    val suggestions: List<PlaceUi> = emptyList(),
    val time: String = "Leave now",
    val rides: List<RideUi> = emptyList(),
    val note: String? = null,
    val canSwap: Boolean = true,
)

class PlanActions(
    val back: () -> Unit = {},
    val edit: (PlanField) -> Unit = {},
    val query: (String) -> Unit = {},
    val pick: (Int) -> Unit = {},
    val swap: () -> Unit = {},
    val time: () -> Unit = {},
    val ride: (Int) -> Unit = {},
)

/** Plan a trip, like Google: From, To, when; then the rides, soonest first. */
@Composable
fun PlanTripScreen(ui: PlanUi, a: PlanActions, modifier: Modifier = Modifier) {
    val c = LocalN.current
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp).verticalScroll(rememberScrollState())) {
        TitleBar { IconCircle(NIcons.Back, "Back", a.back) }
        ScreenTitle(ui.title)
        Spacer(Modifier.height(12.dp))
        // From and To, one card, with the swap button.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(Tokens.RADIUS_BUTTON.dp)).background(c.fill)) {
                PlaceRow(PlanField.FROM, ui, a, dot = true)
                HorizontalDivider(Modifier.padding(start = 44.dp), color = c.line)
                PlaceRow(PlanField.TO, ui, a, dot = false)
            }
            if (ui.canSwap) {
                Spacer(Modifier.width(8.dp))
                IconCircle(NIcons.Swap, "Swap", a.swap)
            }
        }
        if (ui.editing != null) {
            ui.suggestions.forEachIndexed { i, s ->
                ListRow(
                    s.title, s.sub.takeIf { it.isNotEmpty() }, divider = i < ui.suggestions.lastIndex, onClick = { a.pick(i) },
                    leading = { Icon(placeIcon(s.kind), null, tint = c.muted) },
                )
            }
        } else {
            Row(Modifier.padding(top = 12.dp, bottom = 4.dp)) { Pill(ui.time, a.time) }
            ui.rides.forEachIndexed { i, r -> RideRow(r, divider = i < ui.rides.lastIndex) { a.ride(i) } }
        }
        ui.note?.let { Hint(it, Modifier.padding(top = 16.dp)) }
    }
}

private fun placeIcon(k: PlaceUi.Kind) = when (k) {
    PlaceUi.Kind.HERE -> NIcons.Locate
    PlaceUi.Kind.HOME, PlaceUi.Kind.WORK -> NIcons.Now
    PlaceUi.Kind.ADDRESS -> NIcons.Search
    PlaceUi.Kind.STOP -> NIcons.Routes
}

@Composable
private fun PlaceRow(field: PlanField, ui: PlanUi, a: PlanActions, dot: Boolean) {
    val c = LocalN.current
    val editing = ui.editing == field
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { a.edit(field) }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // A hollow dot for where you start, a filled one for where you're going (as Google draws them).
        Canvas(Modifier.size(14.dp)) {
            if (dot) drawCircle(c.fg, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            else drawCircle(c.danger, size.minDimension / 2)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (editing) {
                if (ui.query.isEmpty()) Text(if (field == PlanField.FROM) "Search where you start" else "Search where you're going", style = NType.body, color = c.muted)
                BasicTextField(
                    ui.query, a.query, singleLine = true, textStyle = NType.body.copy(color = c.fg), cursorBrush = SolidColor(c.fg),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                val v = if (field == PlanField.FROM) ui.from else ui.to
                Text(v.ifEmpty { "Where to?" }, style = NType.body, color = if (v.isEmpty()) c.muted else c.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One ride: "7:42 → 8:20" and the bus chips on the left, "38 min" on the right, how it starts below. */
@Composable
private fun RideRow(r: RideUi, divider: Boolean, onClick: () -> Unit) {
    val c = LocalN.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.times, style = NType.rowBig, color = c.fg)
                BusChips(r.buses)
                Text(r.sub, style = NType.secondary, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(r.minutes, style = NType.row, color = c.fg)
        }
        if (divider) HorizontalDivider(color = c.line)
    }
}

@Composable
fun BusChips(buses: List<String>) {
    val c = LocalN.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        buses.forEachIndexed { i, b ->
            if (i > 0) Text("›", style = NType.secondary, color = c.faint)
            Text(
                b, style = NType.secondary.copy(fontWeight = NType.button.fontWeight), color = c.fg,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.fill).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

// ---- A ride, step by step ----

data class RideDetailUi(val title: String, val summary: String, val buses: List<String>, val steps: List<StepUi>)

class RideActions(val back: () -> Unit = {}, val use: () -> Unit = {}, val start: () -> Unit = {})

/** A ride: its map ([map], when there is one: the real map, or a flat box in screenshot tests), then the timeline. */
@Composable
fun RideScreen(ui: RideDetailUi, a: RideActions, modifier: Modifier = Modifier, map: (@Composable (Modifier) -> Unit)? = null) {
    val c = LocalN.current
    Column(modifier.fillMaxSize().padding(top = 12.dp, bottom = 24.dp)) {
        Box(Modifier.padding(horizontal = 24.dp)) { TitleBar { IconCircle(NIcons.Back, "Back", a.back) } }
        map?.invoke(Modifier.fillMaxWidth().height(260.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            if (map != null) Spacer(Modifier.height(12.dp))
            ScreenTitle(ui.title)
            Text(ui.summary, style = NType.body, color = c.muted, modifier = Modifier.padding(bottom = 8.dp))
            BusChips(ui.buses)
            Spacer(Modifier.height(20.dp))
            ui.steps.forEachIndexed { i, s -> Step(s, last = i == ui.steps.lastIndex) }
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Hint("Every stop where you get off a bus is added, with its alarm.", Modifier.fillMaxWidth().padding(bottom = 12.dp), center = true)
            PrimaryButton("Use this", a.use)
            Spacer(Modifier.height(8.dp))
            GhostButton("Start now", a.start, Modifier.fillMaxWidth())
        }
    }
}

/** Leave at or arrive by, and the time, like Google's (owner's feedback on Run 13). Now clears the time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: TimeChoice, nowS: Int, onDone: (TimeChoice) -> Unit, onDismiss: () -> Unit) {
    val c = LocalN.current
    var arrive by remember { mutableStateOf(initial.arrive) }
    val start = initial.atS ?: nowS
    val state = rememberTimePickerState(initialHour = start / 3600 % 24, initialMinute = start % 3600 / 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, value) in listOf("Leave at" to false, "Arrive by" to true)) {
                    val on = arrive == value
                    Text(
                        label, style = NType.row, color = if (on) c.bg else c.fg,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c.fg else c.fill)
                            .clickable { arrive = value }.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        },
        text = { TimeInput(state) },
        confirmButton = { TextButton({ onDone(TimeChoice(arrive, state.hour * 3600 + state.minute * 60)) }) { Text("Done", color = c.fg) } },
        dismissButton = { TextButton({ onDone(TimeChoice()) }) { Text("Leave now", color = c.fg) } },
    )
}

@Composable
private fun Step(s: StepUi, last: Boolean) {
    val c = LocalN.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s.time, style = NType.secondary, color = c.muted, modifier = Modifier.width(44.dp).padding(top = 2.dp))
        Column(Modifier.width(16.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.size(12.dp).clip(CircleShape).background(
                    when (s.kind) {
                        StepUi.Kind.BUS -> c.fg
                        StepUi.Kind.END -> c.danger
                        else -> c.line
                    },
                ),
            )
            if (!last) Box(Modifier.weight(1f).width(2.dp).background(if (s.kind == StepUi.Kind.BUS) c.fg else c.line))
        }
        Column(Modifier.weight(1f).padding(bottom = 18.dp)) {
            Text(s.title, style = if (s.kind == StepUi.Kind.BUS) NType.row else NType.body, color = c.fg)
            Text(s.sub, style = NType.secondary, color = c.muted)
        }
    }
}

// ---- Where's home? Where's work? (PLAN Run 13, T5) ----

data class PlacePickUi(val title: String, val lead: String, val query: String, val suggestions: List<PlaceUi>, val hereLabel: String, val note: String? = null)

class PlacePickActions(
    val back: () -> Unit = {},
    val query: (String) -> Unit = {},
    val pick: (Int) -> Unit = {},
    val here: () -> Unit = {},
    val skip: () -> Unit = {},
)

@Composable
fun PlacePickScreen(ui: PlacePickUi, a: PlacePickActions, modifier: Modifier = Modifier) {
    val c = LocalN.current
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TitleBar { IconCircle(NIcons.Back, "Back", a.back) }
            ScreenTitle(ui.title)
            Lead(ui.lead)
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.RADIUS_BUTTON.dp)).background(c.fill).padding(horizontal = 16.dp).height(52.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (ui.query.isEmpty()) Text("Type an address", style = NType.body, color = c.muted)
                BasicTextField(ui.query, a.query, singleLine = true, textStyle = NType.body.copy(color = c.fg), cursorBrush = SolidColor(c.fg), modifier = Modifier.fillMaxWidth())
            }
            ListRow(ui.hereLabel, "Use where you are now", onClick = a.here, leading = { Icon(NIcons.Locate, null, tint = c.fg) })
            ui.suggestions.forEachIndexed { i, s ->
                ListRow(s.title, s.sub.takeIf { it.isNotEmpty() }, divider = i < ui.suggestions.lastIndex, onClick = { a.pick(i) }, leading = { Icon(placeIcon(s.kind), null, tint = c.muted) })
            }
            ui.note?.let { Hint(it, Modifier.padding(top = 12.dp)) }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextLinkButton("Skip", a.skip) }
    }
}

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.nodstop.map.ZoneScale

// ---- Routes (mockup `routes`) ----

class RoutesActions(val open: (String) -> Unit = {}, val add: () -> Unit = {})

/** Your routes as rows; a problem is the row's red second line (PLAN Run 9, M5). */
@Composable
fun RoutesList(rows: List<RouteRowUi>, a: RoutesActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TitleBar {
                Box(Modifier.weight(1f)) { ScreenTitle("Routes") }
                IconCircle(NIcons.Plus, "Add a route", a.add)
            }
            if (rows.isEmpty()) Lead("Add the buses you take, and where you get off.")
            rows.forEachIndexed { i, r ->
                ListRow(
                    r.title, r.subtitle, big = true, subtitleDanger = r.problem, divider = i < rows.lastIndex,
                    onClick = { a.open(r.id) }, trailing = { Chevron() },
                )
            }
        }
        if (rows.isEmpty()) PrimaryButton("Add a route", a.add)
    }
}

// ---- A route (mockup `route`) ----

data class RouteDetailUi(
    val name: String,
    val stops: List<StopRowUi>,
    val next: String?,
    val canAddBus: Boolean,
    /** "Add stop 2" (PLAN B1). */
    val addLabel: String = "Add a stop",
) {
    /** "Start from <stop>" for a later bus, when the route is ready (you're already past the first). */
    val startFrom: List<StopRowUi> get() = if (next == null) stops.drop(1) else emptyList()
}

class RouteDetailActions(
    val back: () -> Unit = {},
    val rename: () -> Unit = {},
    val openStop: (Int) -> Unit = {},
    val addBus: () -> Unit = {},
    /** The primary button: the next thing it needs, or Start when it's ready. */
    val next: () -> Unit = {},
    val delete: () -> Unit = {},
    val startFrom: (Int) -> Unit = {},
)

/** A route as its stops on a line; one button for the next thing it needs (PLAN Run 9, M5). */
@Composable
fun RouteDetail(ui: RouteDetailUi, a: RouteDetailActions, modifier: Modifier = Modifier) {
    val c = LocalN.current
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TitleBar {
                IconCircle(NIcons.Back, "Back", a.back)
                Spacer(Modifier.weight(1f))
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconCircle(NIcons.More, "More", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for (st in ui.startFrom) DropdownMenuItem(text = { Text("Start from ${st.title}") }, onClick = { menu = false; a.startFrom(st.leg) })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; a.rename() })
                        DropdownMenuItem(text = { Text("Delete route", color = c.danger) }, onClick = { menu = false; a.delete() })
                    }
                }
            }
            Box(Modifier.clickable(onClickLabel = "Rename", onClick = a.rename)) { ScreenTitle(ui.name) }
            Spacer(Modifier.height(20.dp))
            ui.stops.forEachIndexed { i, s -> StopStep(s, last = i == ui.stops.lastIndex) { a.openStop(s.leg) } }
            if (ui.canAddBus) LinkButton(ui.addLabel, a.addBus)
        }
        PrimaryButton(ui.next ?: "Start", a.next)
    }
}

/** One stop on the route's line: a ring (filled: rings; dashed: no alarm; red: not set) and its words. */
@Composable
private fun StopStep(s: StopRowUi, last: Boolean, onClick: () -> Unit) {
    val c = LocalN.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(2.dp))
            Canvas(Modifier.size(20.dp)) {
                val w = 2.dp.toPx()
                val r = size.minDimension / 2 - w / 2
                when (s.kind) {
                    StopRowUi.Kind.SET -> drawCircle(c.fg, r, style = Stroke(w))
                    StopRowUi.Kind.NO_ALARM -> drawCircle(c.faint, r, style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
                    StopRowUi.Kind.NOT_SET -> drawCircle(c.danger, r, style = Stroke(w))
                }
            }
            if (!last) Box(Modifier.weight(1f).width(2.dp).background(c.line))
        }
        Column(Modifier.weight(1f).padding(bottom = 24.dp)) {
            Text(s.title, style = NType.row.copy(fontSize = NType.rowBig.fontSize, lineHeight = NType.rowBig.lineHeight), color = c.fg)
            Text(s.detail, style = NType.secondary, color = if (s.kind == StopRowUi.Kind.NOT_SET) c.danger else c.muted)
        }
    }
}

/** An underlined text button, left-aligned (the mockups' `.link`). */
@Composable
fun LinkButton(text: String, onClick: () -> Unit) {
    Text(
        text, style = NType.button.copy(textDecoration = TextDecoration.Underline), color = LocalN.current.fg,
        modifier = Modifier.heightIn(min = Tokens.TAP.dp).clickable(onClick = onClick).padding(vertical = 14.dp),
    )
}

// ---- Set a stop (mockup `stop`) ----

data class StopPickerUi(
    /** "Hill Ave · wake me within" (plus the size, when it's not one of the choices). */
    val eyebrow: String,
    /** Index into [CircleChoice.LABELS], or -1 for a size set with the slider. */
    val choice: Int,
    val radiusM: Double,
    val alarm: Boolean = true,
    val paste: String = "",
    val pasteError: String? = null,
    val slider: Boolean = false,
    val canRemove: Boolean = false,
    val canForget: Boolean = false,
    val locating: Boolean = false,
)

class StopPickerActions(
    val back: () -> Unit = {},
    val paste: (String) -> Unit = {},
    val choose: (Int) -> Unit = {},
    val showSlider: () -> Unit = {},
    val slide: (Double) -> Unit = {},
    val locate: () -> Unit = {},
    val save: () -> Unit = {},
    val rename: () -> Unit = {},
    val alarm: (Boolean) -> Unit = {},
    val forget: () -> Unit = {},
    val remove: () -> Unit = {},
)

/**
 * Set a stop: the map moves under a fixed pin; pick how close to ring; save (PLAN Run 9, M5). [map] draws the
 * map (the real `PickerMap`, or a flat box in screenshot tests).
 */
@Composable
fun StopPicker(ui: StopPickerUi, a: StopPickerActions, map: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalN.current
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            map(Modifier.fillMaxSize())
            CentrePin(Modifier.align(Alignment.Center))
            Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasteBar(ui, a)
                ui.pasteError?.let { Bubble(it, c.danger, Color.White) }
            }
            IconCircle(
                NIcons.Locate, if (ui.locating) "Finding you" else "Use my location", a.locate, floating = true,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 100.dp),
            )
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)) { Bubble("Drag the map to place your stop", c.fg, c.bg) }
        }
        Sheet(Modifier.offsetUp(28)) {
            // Tap the stop's name to change it (PLAN B1: defaults first, changes on request).
            Box(Modifier.clickable(onClickLabel = "Rename stop", onClick = a.rename)) { Eyebrow(ui.eyebrow) }
            if (ui.alarm) {
                Spacer(Modifier.height(2.dp))
                Segmented(CircleChoice.LABELS, ui.choice, a.choose)
                if (ui.slider) {
                    Slider(
                        ZoneScale.toPos(ui.radiusM), { a.slide(ZoneScale.toMeters(it)) },
                        colors = SliderDefaults.colors(thumbColor = c.fg, activeTrackColor = c.fg, inactiveTrackColor = c.line),
                    )
                } else {
                    Text(
                        "Other size", style = NType.secondary, color = c.muted,
                        modifier = Modifier.clickable(onClick = a.showSlider).padding(vertical = 8.dp),
                    )
                }
            } else {
                Hint("No alarm for this stop: it only marks where this bus ends.", Modifier.padding(vertical = 12.dp))
            }
            PrimaryButton("Save stop", a.save)
        }
    }
}

/** The fixed pin at the map's centre: the place that gets saved. */
@Composable
private fun CentrePin(modifier: Modifier) {
    // Google's red pin (PLAN Run 11, B2), its point exactly on the map's centre: the place that gets saved.
    val pal = app.nodstop.map.MapPalette.colors(LocalN.current.dark)
    Canvas(modifier.offset(y = (-21.5).dp).size(width = 27.dp, height = 43.dp).semantics { contentDescription = "Your stop" }) {
        val w = size.width
        val r = w / 2
        val path = androidx.compose.ui.graphics.Path().apply {
            arcTo(androidx.compose.ui.geometry.Rect(0f, 0f, w, w), 150f, 240f, false)
            lineTo(r, size.height)
            close()
        }
        drawPath(path, Color.Black.copy(alpha = .18f), alpha = 1f)
        translate(top = -1.dp.toPx()) {
            drawPath(path, Color(pal.pin.toInt()))
            drawCircle(Color(pal.pinDot.toInt()), r * 0.36f, center = Offset(r, r))
        }
    }
}

/** The floating field: back, paste a Maps link or coordinates, and the stop's other options. */
@Composable
private fun PasteBar(ui: StopPickerUi, a: StopPickerActions) {
    val c = LocalN.current
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier.fillMaxWidth().height(52.dp).shadow(6.dp, shape).clip(shape).background(c.bg).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = a.back) { Icon(NIcons.Back, "Back", tint = c.fg) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (ui.paste.isEmpty()) Text("Paste a Maps link or coordinates", style = NType.body, color = c.muted, maxLines = 1)
            BasicTextField(
                ui.paste, a.paste, singleLine = true, textStyle = NType.body.copy(color = c.fg), cursorBrush = SolidColor(c.fg),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(),
            )
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(NIcons.More, "More", tint = c.fg) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename stop") }, onClick = { menu = false; a.rename() })
                DropdownMenuItem(text = { Text(if (ui.alarm) "Turn alarm off" else "Turn alarm on") }, onClick = { menu = false; a.alarm(!ui.alarm) })
                if (ui.canForget) DropdownMenuItem(text = { Text("Forget learned rides") }, onClick = { menu = false; a.forget() })
                if (ui.canRemove) DropdownMenuItem(text = { Text("Remove this bus", color = c.danger) }, onClick = { menu = false; a.remove() })
            }
        }
    }
}

@Composable
private fun Bubble(text: String, bg: Color, fg: Color) {
    Text(
        text, style = NType.secondary.copy(fontSize = NType.eyebrow.fontSize), color = fg,
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

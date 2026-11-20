package app.nodstop.ui.v2

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ---- buttons ----

/** The one main action on a screen: full width, black (white at night), at the bottom. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LocalN.current
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(Tokens.RADIUS_BUTTON.dp),
        modifier = modifier.fillMaxWidth().height(Tokens.BUTTON.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.primary, contentColor = c.onPrimary,
            disabledContainerColor = c.fill, disabledContentColor = c.muted,
        ),
    ) { Text(text, style = NType.button) }
}

/** The secondary action on a sheet ("I'm off this bus"). */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalN.current
    Button(
        onClick = onClick, shape = RoundedCornerShape(Tokens.RADIUS_BUTTON.dp),
        modifier = modifier.fillMaxWidth().height(Tokens.BUTTON.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.fill, contentColor = c.fg),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    ) { Text(text, style = NType.button) }
}

/** A small action at the end of a row ("Allow"). */
@Composable
fun Pill(text: String, onClick: () -> Unit) {
    val c = LocalN.current
    Box(
        Modifier.heightIn(min = Tokens.TAP.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = NType.secondary.copy(fontWeight = NType.button.fontWeight), color = c.fg,
            modifier = Modifier.clip(CircleShape).background(c.fill).padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

/** A round icon button (add, back, recentre). [floating] adds the map's shadow. */
@Composable
fun IconCircle(icon: ImageVector, label: String, onClick: () -> Unit, floating: Boolean = false, modifier: Modifier = Modifier) {
    val c = LocalN.current
    Box(
        modifier
            .size(if (floating) Tokens.TAP.dp else 44.dp)
            .let { if (floating) it.shadow(6.dp, CircleShape) else it }
            .clip(CircleShape)
            .background(if (floating) c.bg else c.fill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = c.fg, modifier = Modifier.size(22.dp)) }
}

// ---- lists ----

/**
 * The building block of every non-map screen: a title, a second line, and what it does at the end. Rows
 * divide with a hairline ([divider]); no card behind them.
 */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    subtitleDanger: Boolean = false,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalN.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = if (big) 76.dp else Tokens.ROW.dp)
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(vertical = Tokens.SPACE_4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.SPACE_4.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = if (big) NType.rowBig else NType.row, color = c.fg)
                if (subtitle != null) Text(subtitle, style = NType.secondary, color = if (subtitleDanger) c.danger else c.muted)
            }
            trailing?.invoke()
        }
        if (divider) HorizontalDivider(thickness = 1.dp, color = c.line)
    }
}

@Composable
fun Chevron() {
    Icon(NIcons.Chevron, contentDescription = null, tint = LocalN.current.faint, modifier = Modifier.size(20.dp))
}

/** Pick one route: the likely one is already selected; the Start button below starts it. */
@Composable
fun RadioRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit, divider: Boolean = true) {
    val c = LocalN.current
    ListRow(
        title, subtitle, big = true, divider = divider,
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        leading = {
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .border(if (selected) 7.dp else 2.dp, if (selected) c.fg else c.faint, CircleShape),
            )
        },
    )
}

/** An on/off setting: on is black (white at night), off is the hairline grey. */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, divider: Boolean = true) {
    ListRow(
        title, subtitle, divider = divider,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        trailing = { NSwitch(checked) },
    )
}

@Composable
fun NSwitch(checked: Boolean) {
    val c = LocalN.current
    Box(Modifier.width(51.dp).height(31.dp).clip(CircleShape).background(if (checked) c.fg else c.line)) {
        Box(
            Modifier.offset(x = if (checked) 22.dp else 2.dp, y = 2.dp).size(27.dp).shadow(2.dp, CircleShape)
                .clip(CircleShape).background(if (checked) c.bg else Color.White),
        )
    }
}

/** Choose one of a few options (a stop's circle: Auto, 150 m, 250 m, 400 m). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalN.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.fill).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp)
                    .let { if (on) it.shadow(2.dp, RoundedCornerShape(Tokens.RADIUS_CONTROL.dp)) else it }
                    .clip(RoundedCornerShape(Tokens.RADIUS_CONTROL.dp))
                    .background(if (on) c.bg else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = NType.secondary.copy(fontWeight = NType.button.fontWeight), color = if (on) c.fg else c.muted) }
        }
    }
}

// ---- screens ----

/** A screen title (32 sp bold) with the design's spacing. */
@Composable
fun ScreenTitle(text: String, xl: Boolean = false) {
    Text(text, style = if (xl) NType.titleXl else NType.title, color = LocalN.current.fg, modifier = Modifier.padding(top = if (xl) 4.dp else 16.dp, bottom = 4.dp))
}

@Composable
fun Lead(text: String, modifier: Modifier = Modifier) {
    Text(text, style = NType.body, color = LocalN.current.muted, modifier = modifier.padding(top = 4.dp, bottom = 20.dp))
}

@Composable
fun Eyebrow(text: String) {
    Text(text, style = NType.eyebrow, color = LocalN.current.muted, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        text, style = NType.secondary.copy(fontSize = NType.eyebrow.fontSize), color = LocalN.current.muted,
        textAlign = if (center) TextAlign.Center else null, modifier = modifier,
    )
}

/** The sheet over the map: rounded top, a grab handle, the screen's words. */
@Composable
fun Sheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalN.current
    val shape = RoundedCornerShape(topStart = Tokens.RADIUS_SHEET.dp, topEnd = Tokens.RADIUS_SHEET.dp)
    Column(
        modifier.fillMaxWidth().shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .12f))
            .clip(shape).background(c.bg).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp).width(36.dp).height(5.dp).clip(CircleShape).background(c.line))
        content()
    }
}

/** What the trip sheet says (built from the trip by [TripSheetText]). */
data class TripSheetUi(
    val eyebrow: String,
    val stop: String,
    val figure: String,
    val figureMuted: Boolean,
    val alarm: String?,
    val alarmDanger: Boolean,
    val action: String,
)

/** The sheet while riding: where you get off, how long, that the alarm is set, one button. */
@Composable
fun TripSheet(ui: TripSheetUi, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalN.current
    Sheet(modifier) {
        Eyebrow(ui.eyebrow)
        Text(ui.stop, style = NType.stop, color = c.fg)
        Text(
            ui.figure,
            style = if (ui.figureMuted) NType.body.copy(fontWeight = NType.eyebrow.fontWeight) else NType.figure,
            color = if (ui.figureMuted) c.muted else c.fg,
        )
        if (ui.alarm != null) Row(
            Modifier.padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (ui.alarmDanger) NIcons.Warn else NIcons.Bell, null, tint = if (ui.alarmDanger) c.danger else c.muted, modifier = Modifier.size(18.dp))
            Text(
                ui.alarm, style = if (ui.alarmDanger) NType.secondary.copy(fontWeight = NType.button.fontWeight) else NType.secondary,
                color = if (ui.alarmDanger) c.danger else c.muted,
            )
        } else Spacer(Modifier.height(12.dp))
        GhostButton(ui.action, onAction)
    }
}

/**
 * The alarm's only control: a round button you hold for [holdMs]. The ring fills in the accent as you hold; a
 * tap or a brush does nothing. [shownProgress] fixes the ring for screenshots.
 */
@Composable
fun HoldToStop(holdMs: Int, onDone: () -> Unit, shownProgress: Float? = null) {
    val c = LocalN.current
    val progress = remember { Animatable(shownProgress ?: 0f) }
    val scope = rememberCoroutineScope()
    val done = remember { booleanArrayOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(Tokens.HOLD.dp)
            .semantics { contentDescription = "Hold to stop the alarm" }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val job = scope.launch {
                        progress.animateTo(1f, tween(durationMillis = holdMs, easing = LinearEasing))
                        if (!done[0]) {
                            done[0] = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDone()
                        }
                    }
                    tryAwaitRelease()
                    if (!done[0]) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(150)) }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(Tokens.HOLD.dp)) {
            val stroke = 8.dp.toPx()
            drawCircle(Color(0xFF161616), radius = size.minDimension / 2 - stroke / 2)
            val inset = stroke / 2
            drawArc(
                c.accent, startAngle = -90f, sweepAngle = 360f * progress.value, useCenter = false,
                topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text("Hold", style = NType.holdLabel, color = Color(0xFFF5F5F5))
    }
}

// ---- navigation ----

enum class Tab(val label: String) { NOW("Now"), ROUTES("Routes"), SETTINGS("Settings") }

/** The three places. The active tab is `fg`; while a trip is live, Now carries the accent dot. */
@Composable
fun TabBar(selected: Tab, live: Boolean, onSelect: (Tab) -> Unit) {
    val c = LocalN.current
    Column(Modifier.fillMaxWidth().background(c.bg)) {
        HorizontalDivider(thickness = 1.dp, color = c.line)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp)) {
            for (t in Tab.entries) {
                val on = t == selected
                val tint = if (on) c.fg else c.muted
                Column(
                    Modifier.weight(1f).heightIn(min = Tokens.TAP.dp)
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(t) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box {
                        Icon(
                            when (t) { Tab.NOW -> NIcons.Now; Tab.ROUTES -> NIcons.Routes; Tab.SETTINGS -> NIcons.Settings },
                            contentDescription = null, tint = tint, modifier = Modifier.size(24.dp),
                        )
                        if (t == Tab.NOW && live) LiveDot(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-1).dp))
                    }
                    Text(t.label, style = NType.tab, color = tint)
                }
            }
        }
    }
}

@Composable
fun LiveDot(modifier: Modifier = Modifier, size: Dp = 12.dp) {
    val c = LocalN.current
    Box(modifier.size(size).clip(CircleShape).background(c.bg).padding(2.dp).clip(CircleShape).background(c.accent))
}

/** A row of actions laid out at the end of a title bar. */
@Composable
fun TitleBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = Tokens.TAP.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

package app.nodstop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.nodstop.ui.v2.IconCircle
import app.nodstop.ui.v2.LocalN
import app.nodstop.ui.v2.NIcons
import app.nodstop.ui.v2.NType
import app.nodstop.ui.v2.Tokens

/*
 * The few building blocks Debug and the Briefing lab use, in the v2 look (PLAN Run 9, M7). These screens are
 * tools for testing, behind a long press on the build line: plain, not designed.
 */

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconCircle(NIcons.Back, "Back", onBack)
            Spacer(Modifier.width(16.dp))
        }
        Text(title, style = NType.title, color = LocalN.current.fg)
    }
}

/** A group on a `fill` background, with an optional title. */
@Composable
fun Section(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalN.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.RADIUS_BUTTON.dp)).background(c.fill).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) Text(title, style = NType.row, color = c.fg)
        content()
    }
}

@Composable
fun Confirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = LocalN.current.fg) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = LocalN.current.fg) } },
    )
}

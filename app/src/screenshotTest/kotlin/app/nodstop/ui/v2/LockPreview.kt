package app.nodstop.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the trip notification looks like on the lock screen (mockup `lock`), drawn from the same [TripNoteUi]
 * the real notification uses. The system draws the real one; this is only for the design comparison.
 */
@Composable
fun LockPreview(note: TripNoteUi, time: String, date: String) {
    val c = LocalN.current
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1D22), Color.Black))).padding(bottom = 32.dp)) {
        Spacer(Modifier.height(92.dp))
        Text(time, style = NType.alarm.copy(fontSize = 84.sp, lineHeight = 90.sp), color = c.fg, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(date, style = NType.body, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = .1f))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
                Text("Sleepers", style = NType.tab.copy(fontSize = 13.sp, fontWeight = NType.button.fontWeight), color = c.fg, modifier = Modifier.padding(start = 6.dp).weight(1f))
                Text("now", style = NType.tab.copy(fontSize = 13.sp), color = c.muted)
            }
            Text(note.title, style = NType.row, color = c.fg)
            Text(note.text, style = NType.secondary, color = c.muted)
            note.progress?.let { p ->
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .18f))) {
                    Box(Modifier.fillMaxWidth(p / 100f).height(4.dp).background(c.accent))
                }
            }
        }
    }
}

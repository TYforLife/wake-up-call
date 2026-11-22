package app.nodstop.ui.v2

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ---- Setup (mockup `setup`) ----

class SetupActions(val allow: (String) -> Unit = {}, val done: () -> Unit = {}, val notNow: () -> Unit = {})

/** Before your first ride: one list, an Allow on each row that's off, one Continue (PLAN Run 9, M6). */
@Composable
fun SetupScreen(rows: List<SetupRowUi>, samsung: Boolean, a: SetupActions, modifier: Modifier = Modifier) {
    val c = LocalN.current
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ScreenTitle("Before your first ride")
            Lead("Allow these so the alarm always reaches you.")
            rows.forEachIndexed { i, r ->
                ListRow(
                    r.title, r.subtitle, divider = i < rows.lastIndex,
                    trailing = { if (r.ok) Icon(NIcons.Check, "Allowed", tint = c.fg) else Pill("Allow", { a.allow(r.id) }) },
                )
            }
            if (samsung) {
                Spacer(Modifier.height(24.dp))
                Eyebrow("On Samsung, do these once")
                SetupText.SAMSUNG_STEPS.forEachIndexed { i, s ->
                    Hint("${i + 1}. $s", Modifier.padding(top = 8.dp))
                }
            }
        }
        val ready = SetupText.canContinue(rows)
        PrimaryButton("Continue", a.done, enabled = ready)
        if (!ready) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextLinkButton("Not now", a.notNow) }
    }
}

// ---- Settings (mockup `settings`) ----

data class SettingsUi(
    val quiet: Boolean,
    val speaker: Boolean,
    val ask: Boolean,
    val contacts: String,
    val build: String,
    /** Android 16's lock-screen progress is off (optional, so only shown when it is). */
    val liveOff: Boolean = false,
    /** The credit the bundled stop data asks for (CC BY 4.0, PLAN Run 12), when this build has it. */
    val credits: String? = null,
    /** "12 Example Rd · 1 Queen St", or how to set them (PLAN Run 13, T5). */
    val homeWork: String = "Set them to plan your commute",
)

class SettingsActions(
    val quiet: (Boolean) -> Unit = {},
    val speaker: (Boolean) -> Unit = {},
    val ask: (Boolean) -> Unit = {},
    val contacts: () -> Unit = {},
    val report: () -> Unit = {},
    val live: () -> Unit = {},
    val debug: () -> Unit = {},
    val homeWork: () -> Unit = {},
)

/** Settings: six rows; everything technical is in Debug, behind a long press on the build line (PLAN Run 9, M6). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(ui: SettingsUi, a: SettingsActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 12.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ScreenTitle("Settings")
            Spacer(Modifier.height(12.dp))
            ListRow("Home and work", ui.homeWork, onClick = a.homeWork, trailing = { Chevron() })
            SwitchRow("Quiet other notifications", "While the screen is off, on a trip", ui.quiet, a.quiet)
            SwitchRow("Ring out loud after 60 s", "If vibration didn't wake you", ui.speaker, a.speaker)
            SwitchRow("Ask how the timing was", "After each ring", ui.ask, a.ask)
            ListRow("Important contacts", ui.contacts, onClick = a.contacts, trailing = { Chevron() })
            ListRow("Report a missed alarm", "Shares the trip log, locations hidden", divider = ui.liveOff, onClick = a.report, trailing = { Chevron() })
            if (ui.liveOff) ListRow("Progress on the lock screen", "Optional, Android 16", divider = false, trailing = { Pill("Allow", a.live) })
        }
        ui.credits?.let { Hint(it, Modifier.fillMaxWidth(), center = true) }
        Hint(
            ui.build,
            Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = a.debug).padding(vertical = 12.dp),
            center = true,
        )
    }
}

// ---- Important contacts (from Settings) ----

class ContactsActions(
    val back: () -> Unit = {},
    val add: (String) -> Unit = {},
    val remove: (String) -> Unit = {},
    val allowReading: () -> Unit = {},
)

/** Who can wake you with an urgent message (While you slept, DESIGN §10). */
@Composable
fun ContactsScreen(names: List<String>, readingOk: Boolean, a: ContactsActions, modifier: Modifier = Modifier) {
    val c = LocalN.current
    var text by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp).verticalScroll(rememberScrollState())) {
        TitleBar { IconCircle(NIcons.Back, "Back", a.back) }
        ScreenTitle("Important contacts")
        Lead("Only their messages can wake you on a trip, and only if they look urgent. Names as they show in your chats.")
        ListRow(
            "Read chat notifications", "During trips only. Kept in memory, never saved or sent.",
            trailing = { if (readingOk) Icon(NIcons.Check, "Allowed", tint = c.fg) else Pill("Allow", a.allowReading) },
        )
        names.forEach { n -> ListRow(n, null, trailing = { Pill("Remove", { a.remove(n) }) }) }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                text, { text = it }, label = { Text("Add a name, e.g. Mum") }, singleLine = true, modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.fg, focusedLabelColor = c.fg, cursorColor = c.fg),
            )
            Pill("Add", { if (text.isNotBlank()) { a.add(text.trim()); text = "" } })
        }
    }
}

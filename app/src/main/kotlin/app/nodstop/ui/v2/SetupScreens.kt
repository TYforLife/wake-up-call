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
    /** "Beeps", "Chime", "Your own: wake.mp3". */
    val sound: String = "Beeps",
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
    val sound: () -> Unit = {},
    val coverage: () -> Unit = {},
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
            ListRow("Alarm sound", ui.sound, onClick = a.sound, trailing = { Chevron() })
            SwitchRow("Quiet other notifications", "While the screen is off, on a trip", ui.quiet, a.quiet)
            SwitchRow("Ring out loud after 60 s", "If vibration didn't wake you", ui.speaker, a.speaker)
            SwitchRow("Ask how the timing was", "After each ring", ui.ask, a.ask)
            ListRow("Important contacts", ui.contacts, onClick = a.contacts, trailing = { Chevron() })
            ListRow("Report a missed alarm", "Shares the trip log, locations hidden", onClick = a.report, trailing = { Chevron() })
            if (ui.liveOff) ListRow("Progress on the lock screen", "Optional, Android 16", trailing = { Pill("Allow", a.live) })
            ListRow("Where Nodstop works", "Auckland, for now", divider = false, onClick = a.coverage, trailing = { Chevron() })
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

// ---- Alarm sound (from Settings, BACKLOG 3) ----

data class SoundsUi(
    /** Each sound's name and whether it's the one, in order (the last is your own). */
    val options: List<Pair<String, Boolean>>,
    /** Your own sound's file, or how to add one. */
    val ownLine: String,
    val note: String? = null,
    /** Off while a trip runs: a test ring would replace the trip's alarm. */
    val canTry: Boolean = true,
)

class SoundsActions(
    val back: () -> Unit = {},
    val pick: (Int) -> Unit = {},
    val chooseFile: () -> Unit = {},
    val tryIt: () -> Unit = {},
    val trySpeaker: () -> Unit = {},
)

/** The alarm's sound: four built in, or your own file. "Try it" is a real test ring through the alarm. */
@Composable
fun SoundsScreen(ui: SoundsUi, a: SoundsActions, modifier: Modifier = Modifier) {
    val c = LocalN.current
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TitleBar { IconCircle(NIcons.Back, "Back", a.back) }
            ScreenTitle("Alarm sound")
            Lead("In your earbuds, with vibration, as always. Without earbuds it only vibrates.")
            ui.options.forEachIndexed { i, (name, on) ->
                ListRow(
                    name, if (i == ui.options.lastIndex) ui.ownLine else null, onClick = { a.pick(i) },
                    trailing = { if (on) Icon(NIcons.Check, "Chosen", tint = c.fg) },
                )
            }
            ListRow("Choose a file…", "mp3, wav or m4a; the first 20 s loop", onClick = a.chooseFile, divider = false, trailing = { Chevron() })
            ui.note?.let { Hint(it, Modifier.fillMaxWidth().padding(top = 12.dp)) }
        }
        if (ui.canTry) {
            PrimaryButton("Try it", a.tryIt)
            Spacer(Modifier.height(8.dp))
            GhostButton("Try it on the phone speaker", a.trySpeaker, Modifier.fillMaxWidth())
            Hint("Rings the real alarm now: in your earbuds, or on the speaker. Hold to stop it.", Modifier.fillMaxWidth().padding(top = 8.dp), center = true)
        } else Hint("Try it after your trip: it would replace the trip's alarm.", Modifier.fillMaxWidth(), center = true)
    }
}

// ---- Where Nodstop works (from Settings; BACKLOG, product direction) ----

data class CoverageUi(
    /** Each region: its name, its operator and stop count, its welcome. */
    val regions: List<Triple<String, String, String>>,
    val note: String? = null,
)

/** A map of the regions Nodstop covers, and each one's welcome. [map]: the real map, or a flat box in tests. */
@Composable
fun CoverageScreen(ui: CoverageUi, back: () -> Unit, map: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalN.current
    Column(modifier.fillMaxSize().padding(top = 12.dp, bottom = 24.dp)) {
        Box(Modifier.padding(horizontal = 24.dp)) { TitleBar { IconCircle(NIcons.Back, "Back", back) } }
        Column(Modifier.padding(horizontal = 24.dp)) { ScreenTitle("Where Nodstop works") }
        map(Modifier.fillMaxWidth().height(300.dp).padding(top = 8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
            ui.regions.forEach { (name, line, welcome) ->
                Text(name, style = NType.row, color = c.fg)
                Text(line, style = NType.secondary, color = c.muted)
                Text(welcome, style = NType.body, color = c.fg, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            }
            ui.note?.let { Hint(it) }
            Hint("More regions, one at a time.", Modifier.padding(top = 8.dp))
        }
    }
}

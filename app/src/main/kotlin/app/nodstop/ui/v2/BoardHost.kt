package app.nodstop.ui.v2

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.nodstop.data.Departures
import app.nodstop.data.Timetable
import app.nodstop.data.TransitData
import app.nodstop.data.TransitIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/** Routes → Departures (BACKLOG F27): any stop's next buses, from the bundled timetable, refreshed each minute. */
@Composable
fun BoardHost(back: () -> Unit) {
    val ctx = LocalContext.current
    var ix by remember { mutableStateOf<TransitIndex?>(null) }
    var tt by remember { mutableStateOf<Timetable?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ix = TransitData.get(ctx); tt = TransitData.timetable(ctx); loaded = true }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<TransitIndex.Hit?>(null) }
    val hits = remember(query, ix) { ix?.search(query, limit = 8).orEmpty() }
    var rows by remember { mutableStateOf<List<BoardRowUi>?>(null) }
    LaunchedEffect(picked, tt) {
        rows = null
        val p = picked ?: return@LaunchedEffect
        val i = ix ?: return@LaunchedEffect
        val t = tt ?: return@LaunchedEffect
        while (true) {
            val nowS = LocalTime.now().toSecondOfDay()
            // A bus a minute late is still on the board.
            rows = withContext(Dispatchers.Default) { BoardText.rows(Departures.board(i, t, p.index, LocalDate.now(), nowS - 60), nowS) }
            delay(60_000)
        }
    }
    val p = picked
    BoardScreen(
        BoardUi(
            query = query,
            results = hits.map { h -> PlaceUi(h.stop.name, PlanText.stopSub(ix?.modeAt(h.index) ?: app.nodstop.engine.Mode.BUS, h.stop.code, null), h.stop.pos, PlaceUi.Kind.STOP) },
            stop = p?.stop?.name,
            rows = rows.orEmpty(),
            note = when {
                loaded && (ix == null || tt == null) -> "Departures need the bundled timetable, which this build doesn't have."
                p != null && rows?.isEmpty() == true -> BoardText.NONE
                else -> null
            },
        ),
        BoardActions(
            back = { if (picked != null) picked = null else back() },
            query = { query = it; picked = null },
            pick = { i -> picked = hits.getOrNull(i) },
        ),
        Modifier.statusBarsPadding().navigationBarsPadding(),
    )
}

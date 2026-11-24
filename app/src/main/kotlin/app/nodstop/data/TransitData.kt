package app.nodstop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The bundled Auckland Transport index (PLAN Run 12, C2), from `assets/gtfs/`, which CI builds from the public feed
 * (`tools/gtfs_index.py`). Loaded once, off the main thread. A build without it (a local build) has no stop search;
 * links and the map still work.
 */
object TransitData {
    @Volatile private var cached: TransitIndex? = null
    @Volatile private var missing = false

    suspend fun get(ctx: Context): TransitIndex? {
        cached?.let { return it }
        if (missing) return null
        return withContext(Dispatchers.IO) {
            // A build without the data (CI couldn't download it) has no files: search then offers the map only.
            try {
                val a = ctx.applicationContext.assets
                // Which routes are trains or ferries (BACKLOG F29): optional, so an older build's data still loads.
                val modes = try { a.open("gtfs/modes.tsv").bufferedReader().use { it.readText() } } catch (_: IOException) { "" }
                TransitIndex.parse(
                    a.open("gtfs/stops.tsv").bufferedReader().use { it.readText() },
                    a.open("gtfs/routes.tsv").bufferedReader().use { it.readText() },
                    modes,
                ).takeIf { it.stops.isNotEmpty() }
            } catch (_: IOException) {
                null
            }.also { if (it == null) missing = true else cached = it }
        }
    }

    @Volatile private var timetable: Timetable? = null
    @Volatile private var noTimetable = false

    /** The timetable for the journey planner (PLAN Run 13), loaded once, off the main thread; null if not bundled. */
    suspend fun timetable(ctx: Context): Timetable? {
        timetable?.let { return it }
        if (noTimetable) return null
        return withContext(Dispatchers.IO) {
            // All or nothing: trips point at profiles by line number, so a timetable missing a line would show wrong
            // times. A build without the files, or with files that don't parse, has no planner instead.
            try {
                val a = ctx.applicationContext.assets
                fun read(n: String) = a.open("gtfs/$n").bufferedReader().use { it.readText() }
                // The road shapes are optional: without them the map draws the bus stop to stop.
                val shapes = try { read("shapes.tsv") } catch (_: IOException) { null }
                Timetable.parse(read("patterns.tsv"), read("profiles.tsv"), read("trips.tsv"), read("services.tsv"), shapes).takeIf { it.patterns.isNotEmpty() }
            } catch (_: IOException) {
                null
            } catch (_: NumberFormatException) {
                null
            } catch (_: IndexOutOfBoundsException) {
                null
            }.also { if (it == null) noTimetable = true else timetable = it }
        }
    }

    /**
     * Today's timetabled departures of [bus] from [board] ("lat, lng"), seconds after midnight, soonest first: read
     * from the timetable once a day per bus and stop and kept, so the widget and Now don't load the whole timetable
     * again for it. Empty without a bundled timetable.
     */
    suspend fun todayDepartures(ctx: Context, bus: String, board: String): List<Int> {
        val today = java.time.LocalDate.now()
        val prefs = ctx.applicationContext.getSharedPreferences("departures", Context.MODE_PRIVATE)
        val key = "$bus|$board"
        prefs.getString(key, null)?.split('|', limit = 2)?.takeIf { it.size == 2 && it[0] == today.toString() }?.let { kept ->
            return kept[1].split(',').mapNotNull { it.toIntOrNull() }
        }
        val pos = app.nodstop.engine.CoordParser.parse(board) ?: return emptyList()
        val ix = get(ctx) ?: return emptyList()
        val tt = timetable(ctx) ?: return emptyList()
        val deps = withContext(Dispatchers.Default) { Departures.next(ix, tt, pos, bus, today, 0, count = Int.MAX_VALUE) }
        prefs.edit().putString(key, "$today|${deps.joinToString(",")}").apply()
        return deps
    }

    /** "Stop and route data: Auckland Transport, CC BY 4.0", or null when there's no data in this build. */
    fun attribution(ctx: Context): String? =
        try { ctx.assets.open("gtfs/meta.txt").bufferedReader().use { it.readLine() } } catch (_: IOException) { null }
}

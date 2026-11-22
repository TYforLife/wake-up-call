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
                TransitIndex.parse(
                    a.open("gtfs/stops.tsv").bufferedReader().use { it.readText() },
                    a.open("gtfs/routes.tsv").bufferedReader().use { it.readText() },
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
                Timetable.parse(read("patterns.tsv"), read("profiles.tsv"), read("trips.tsv"), read("services.tsv")).takeIf { it.patterns.isNotEmpty() }
            } catch (_: IOException) {
                null
            } catch (_: NumberFormatException) {
                null
            } catch (_: IndexOutOfBoundsException) {
                null
            }.also { if (it == null) noTimetable = true else timetable = it }
        }
    }

    /** "Stop and route data: Auckland Transport, CC BY 4.0", or null when there's no data in this build. */
    fun attribution(ctx: Context): String? =
        try { ctx.assets.open("gtfs/meta.txt").bufferedReader().use { it.readLine() } } catch (_: IOException) { null }
}

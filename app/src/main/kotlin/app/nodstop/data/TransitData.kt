package app.nodstop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            runCatching {
                val a = ctx.applicationContext.assets
                TransitIndex.parse(
                    a.open("gtfs/stops.tsv").bufferedReader().use { it.readText() },
                    a.open("gtfs/routes.tsv").bufferedReader().use { it.readText() },
                ).takeIf { it.stops.isNotEmpty() }
            }.getOrNull().also { if (it == null) missing = true else cached = it }
        }
    }

    /** "Stop and route data: Auckland Transport, CC BY 4.0", or null when there's no data in this build. */
    fun attribution(ctx: Context): String? =
        runCatching { ctx.assets.open("gtfs/meta.txt").bufferedReader().use { it.readLine() } }.getOrNull()
}

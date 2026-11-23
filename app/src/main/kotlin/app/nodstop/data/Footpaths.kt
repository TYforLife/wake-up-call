package app.nodstop.data

import android.content.Context
import android.util.Log
import app.nodstop.engine.CoordParser
import app.nodstop.engine.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/**
 * Walking along real footpaths (BACKLOG 1 and 27): OpenStreetMap's walkable ways around a place, from the Overpass
 * API, kept in the app's cache. Only that area is sent (a square of a few km, like the map tiles the map already
 * loads), with a generic client name; never a name or an address.
 *
 * The straight line is the last resort, so each walk tries, in order: the cached paths (younger than [KEEP_DAYS]),
 * each Overpass server, the cached paths however old (a failed refresh never loses them), and the foot router
 * ([FootRouter]). Only when all of those fail is it null, and the planner walks straight lines × 1.25, saying so.
 */
object Footpaths {
    /** The main Overpass server, then public mirrors for when it's busy or refuses (it sometimes does). */
    internal val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )
    private const val KEEP_DAYS = 30L
    /**
     * Room around a walk's reach: a cached place is used for any place within about 100 m of it ([key]), so the box
     * must reach as far from every one of those.
     */
    const val SLACK_M = 150.0

    /** The square fetched for walks of up to [reachM] from a place. */
    fun radius(reachM: Double) = reachM + SLACK_M

    /** Where a place's paths are cached: its position to 3 decimals (about 100 m), and how far walks reach. */
    internal fun key(p: LatLng, reachM: Double) = "%d_%.3f_%.3f".format(Locale.US, reachM.toInt(), p.lat, p.lng)

    /**
     * Walks from [p] of up to [reachM], to [targets] (the stops it may walk to, nearest first; the foot router is
     * asked only for those). Null only when no source answers.
     */
    suspend fun walks(ctx: Context, p: LatLng, reachM: Double, targets: List<LatLng>): Planner.Walks? = withContext(Dispatchers.IO) {
        val f = file(ctx, p, reachM)
        val json = fresh(f) ?: fetch(p, reachM)?.also { save(f, it) } ?: f.takeIf { it.exists() }?.readText()
        json?.let { graph(it, p, reachM) }
            ?: targets.take(FootRouter.MAX_TARGETS).takeIf { it.isNotEmpty() }?.let { FootRouter.walks(p, it) }
    }

    /** Fetches the paths for [places] (a place and its walks' reach) that aren't cached yet, or are old. */
    suspend fun prefetch(ctx: Context, places: List<Pair<LatLng, Double>>) = withContext(Dispatchers.IO) {
        for ((p, reach) in places) {
            val f = file(ctx, p, reach)
            if (!isFresh(f)) fetch(p, reach)?.let { save(f, it) }
        }
    }

    /**
     * The places your commute walks from, to have their paths before you plan: home and work, and each stop of a
     * route where you get off and change buses (every stop but the last).
     */
    fun commute(home: LatLng?, work: LatLng?, routes: List<Route>): List<Pair<LatLng, Double>> =
        listOfNotNull(home, work).map { it to Planner.MAX_WALK_M } +
            routes.flatMap { r -> r.legs.dropLast(1).mapNotNull { CoordParser.parse(it.coords) } }.distinct().map { it to Planner.CHANGE_WALK_M }

    private suspend fun graph(json: String, p: LatLng, reachM: Double): Planner.Walks? = withContext(Dispatchers.Default) {
        try {
            WalkGraph.parse(json).from(p, reachM)
        } catch (e: SerializationException) {
            Log.w("Nodstop", "footpaths: unreadable reply", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w("Nodstop", "footpaths: unreadable reply", e)
            null
        }
    }

    private fun file(ctx: Context, p: LatLng, reachM: Double) =
        File(File(ctx.cacheDir, "footpaths").apply { mkdirs() }, "${key(p, reachM)}.json")

    private fun isFresh(f: File) = f.exists() && System.currentTimeMillis() - f.lastModified() < KEEP_DAYS * 86_400_000L
    private fun fresh(f: File): String? = if (isFresh(f)) f.readText() else null

    private fun save(f: File, json: String) {
        try {
            f.writeText(json)
        } catch (e: IOException) {
            Log.w("Nodstop", "footpaths: not cached", e)
        }
    }

    /**
     * The footpaths, not a refusal: a refusal is an HTML page, or a 200 whose JSON carries a "remark" (a timeout or
     * an error) instead of the ways. Only these are used and cached.
     */
    internal fun usable(reply: String): Boolean = reply.contains("\"elements\"") && !reply.contains("\"remark\"") && reply.contains("\"way\"")

    private fun fetch(p: LatLng, reachM: Double): String? {
        val body = "data=" + URLEncoder.encode(WalkGraph.query(p, radius(reachM)), "UTF-8")
        return SERVERS.firstNotNullOfOrNull { Http.post(it, body)?.takeIf(::usable) }
    }
}

package app.nodstop.data

import android.content.Context
import android.util.Log
import app.nodstop.engine.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Walking along real footpaths (BACKLOG 1): OpenStreetMap's walkable ways around one end of a trip, from the Overpass
 * API, kept in the app's cache for [KEEP_DAYS]. Only that area is sent (a square about 2 km across, like the map
 * tiles the map already loads), with a generic client name; never a name or an address. Offline, or if Overpass
 * doesn't answer: null, and the planner walks straight lines × 1.25 as before.
 */
object Footpaths {
    private const val URL_OVERPASS = "https://overpass-api.de/api/interpreter"
    /** Around each end: the planner's walks start within 800 m (straight), so 1 km covers their way there. */
    private const val RADIUS_M = 1_000.0
    private const val KEEP_DAYS = 30L

    suspend fun walks(ctx: Context, p: LatLng): Planner.Walks? = withContext(Dispatchers.IO) {
        val json = cached(ctx, p) ?: fetch(p)?.also { save(ctx, p, it) } ?: return@withContext null
        try {
            withContext(Dispatchers.Default) { WalkGraph.parse(json).from(p) }
        } catch (e: SerializationException) {
            Log.w("Nodstop", "footpaths: unreadable reply", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w("Nodstop", "footpaths: unreadable reply", e)
            null
        }
    }

    /** One file per place, by its position to about 100 m (home and work come back to the same file). */
    private fun file(ctx: Context, p: LatLng) =
        File(File(ctx.cacheDir, "footpaths").apply { mkdirs() }, "%.3f_%.3f.json".format(Locale.US, p.lat, p.lng))

    private fun cached(ctx: Context, p: LatLng): String? {
        val f = file(ctx, p)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < KEEP_DAYS * 86_400_000L
        return if (fresh) f.readText() else null
    }

    private fun save(ctx: Context, p: LatLng, json: String) {
        try {
            file(ctx, p).writeText(json)
        } catch (e: IOException) {
            Log.w("Nodstop", "footpaths: not cached", e)
        }
    }

    private fun fetch(p: LatLng): String? {
        val body = "data=" + URLEncoder.encode(WalkGraph.query(p, RADIUS_M), "UTF-8")
        return try {
            val c = (URL(URL_OVERPASS).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Nodstop")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            try {
                c.outputStream.use { it.write(body.toByteArray()) }
                if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            Log.w("Nodstop", "footpaths: no answer", e)
            null
        }
    }
}

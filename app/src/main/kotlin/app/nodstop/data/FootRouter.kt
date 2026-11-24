package app.nodstop.data

import app.nodstop.engine.LatLng
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * Walking by a public foot router (FOSSGIS's OSRM, foot profile, on OpenStreetMap data), when the footpaths themselves
 * can't be had (BACKLOG 27): the walking metres from a place to the stops around it in one request, and the way a
 * chosen walk goes. Only the points of the walk are sent, never a name. Each length counts the steps from the point
 * onto the path, as `WalkGraph` does. Parsing is pure: tested on the JVM with OSRM's replies.
 */
object FootRouter {
    private const val SERVER = "https://routing.openstreetmap.de/routed-foot"
    /** The most stops asked for at once (the server's table limit is larger). */
    const val MAX_TARGETS = 50

    private fun coords(ps: List<LatLng>) = ps.joinToString(";") { "%.6f,%.6f".format(Locale.US, it.lng, it.lat) }

    fun tableUrl(from: LatLng, targets: List<LatLng>): String =
        "$SERVER/table/v1/foot/${coords(listOf(from) + targets)}?sources=0&annotations=distance"

    fun routeUrl(from: LatLng, to: LatLng): String =
        "$SERVER/route/v1/foot/${coords(listOf(from, to))}?overview=full&geometries=geojson"

    /** Walks from [from] to the stops at [targets] (the same list the table was asked for); null if it isn't one. */
    fun walks(from: LatLng, targets: List<LatLng>): Planner.Walks? =
        Http.get(tableUrl(from, targets))?.let { parseTable(it, targets) }

    /** The way from [from] to [to]; null if the router doesn't answer. */
    fun route(from: LatLng, to: LatLng): Planner.Path? = Http.get(routeUrl(from, to))?.let { parseRoute(it, from, to) }

    private fun JsonElement.num(): Double? = if (this is JsonNull) null else jsonPrimitive.doubleOrNull
    private fun JsonObject.ok() = this["code"]?.jsonPrimitive?.content == "Ok"
    /** How far each point is from where the router stepped onto the path. */
    private fun snaps(o: JsonObject, key: String): List<Double>? = (o[key] as? JsonArray)?.map { it.jsonObject["distance"]?.num() ?: 0.0 }

    /** OSRM's table reply: metres from the first point to each of [targets]; unreachable ones (null) can't be walked. */
    fun parseTable(json: String, targets: List<LatLng>): Planner.Walks? = read {
        val o = Json.parseToJsonElement(json).jsonObject
        if (!o.ok()) return@read null
        val row = o["distances"]?.jsonArray?.firstOrNull()?.jsonArray ?: return@read null
        val start = snaps(o, "sources")?.firstOrNull() ?: 0.0
        val ends = snaps(o, "destinations").orEmpty()
        if (row.size != targets.size + 1) return@read null
        val metres = HashMap<LatLng, Double>()
        targets.forEachIndexed { i, t -> row[i + 1].num()?.let { metres[t] = it + start + ends.getOrElse(i + 1) { 0.0 } } }
        Planner.Walks { p -> metres[p]?.let { Planner.Path(it, emptyList()) } }
    }

    /** OSRM's route reply: the walk's metres and its way, from [from] to [to]. */
    fun parseRoute(json: String, from: LatLng, to: LatLng): Planner.Path? = read {
        val o = Json.parseToJsonElement(json).jsonObject
        if (!o.ok()) return@read null
        val r = o["routes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@read null
        val m = r["distance"]?.num() ?: return@read null
        val line = r["geometry"]?.jsonObject?.get("coordinates")?.jsonArray?.map { c ->
            val xy = c.jsonArray
            LatLng(xy[1].jsonPrimitive.content.toDouble(), xy[0].jsonPrimitive.content.toDouble())
        } ?: return@read null
        Planner.Path(m + snaps(o, "waypoints").orEmpty().sum(), listOf(from) + line + to)
    }

    /** A reply that isn't OSRM's (an error page, a cut-off body) is no answer. */
    private inline fun <T> read(block: () -> T?): T? = try {
        block()
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }
}

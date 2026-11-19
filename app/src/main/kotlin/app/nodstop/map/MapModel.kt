package app.nodstop.map

import app.nodstop.data.Route
import app.nodstop.engine.Config
import app.nodstop.engine.CoordParser
import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import app.nodstop.engine.Learned
import app.nodstop.engine.Phase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the map shows, as plain data (no Android), so it can be unit tested. */
object MapModel {
    enum class Me { PERSON, BUS }

    /** You're shown as a bus once on it (or still moving at bus speed while ringing / awake); otherwise as you. */
    fun meKind(phase: Phase?, speedKmh: Double?): Me = when (phase) {
        Phase.RIDING -> Me.BUS
        Phase.RINGING, Phase.AWAKE -> if ((speedKmh ?: 0.0) >= 15.0) Me.BUS else Me.PERSON
        else -> Me.PERSON
    }

    data class Pin(
        val pos: LatLng,
        val label: String,
        val current: Boolean,
        val alarm: Boolean,
        val routeId: String = "",
        val legIndex: Int = 0,
        /** Where it will ring: your radius if set, else the automatic one. */
        val zoneM: Double = 0.0,
        val zoneIsYours: Boolean = false,
    )

    data class Layers(
        val pins: List<Pin>,
        val tracks: List<List<LatLng>>,
    )

    /**
     * Which routes the map shows (PLAN R4): during a trip, only that trip's; otherwise the one you picked,
     * else the one you last used, else the first. Never every stop of every route at once.
     */
    fun visibleRoutes(routes: List<Route>, tripRouteName: String?, chosenId: String?, lastUsedId: String?): List<Route> {
        if (tripRouteName != null) return routes.filter { it.name == tripRouteName }.take(1)
        val pick = routes.firstOrNull { it.id == chosenId } ?: routes.firstOrNull { it.id == lastUsedId } ?: routes.firstOrNull()
        return listOfNotNull(pick)
    }

    /**
     * Every pinned stop of every route (the running trip's current stop highlighted) and the learned routes.
     */
    fun layers(
        routes: List<Route>,
        learned: (LatLng) -> Learned?,
        currentRouteName: String? = null,
        currentLeg: Int? = null,
    ): Layers {
        val pins = ArrayList<Pin>()
        val tracks = ArrayList<List<LatLng>>()
        val seen = HashSet<String>()
        for (r in routes) r.legs.forEachIndexed { i, leg ->
            val p = CoordParser.parse(leg.coords) ?: return@forEachIndexed
            val current = r.name == currentRouteName && i == currentLeg
            val l = learned(p)
            val zone = leg.wakeRadiusM ?: autoZoneM(p, l)
            pins += Pin(p, leg.name.ifBlank { "${r.name} ${i + 1}" }, current, leg.alarmEnabled, r.id, i, zone, leg.wakeRadiusM != null)
            if (!seen.add(Route.stopKey(p))) return@forEachIndexed // a stop shared by two routes: once is enough
            l ?: return@forEachIndexed
            l.tracks.forEach { t -> if (t.points.size >= 2) tracks += t.points.map { it.pos } }
        }
        return Layers(pins, tracks)
    }

    /**
     * The automatic wake zone: how far out (straight line) the bus usually is when this stop's lead is
     * left, from past rides (median); before any ride, lead × 25 km/h (the engine's floor).
     */
    fun autoZoneM(pin: LatLng, learned: Learned?, cfg: Config = Config()): Double {
        val lead = learned?.leadS?.coerceIn(cfg.minLeadS, cfg.maxLeadS) ?: cfg.leadS
        val ds = learned?.tracks.orEmpty().mapNotNull { t ->
            t.points.firstOrNull { it.toEndS <= lead }?.let { Geo.distanceM(it.pos, pin) }
        }.sorted()
        return if (ds.isEmpty()) lead * cfg.vFloor else ds[ds.size / 2]
    }

    /** A circle of [radiusM] around [c] as a closed polygon ring (for drawing a zone in metres). */
    fun circle(c: LatLng, radiusM: Double, n: Int = 64): List<LatLng> {
        val dLat = radiusM / (Math.PI * Geo.EARTH_RADIUS_M / 180)
        val dLng = dLat / kotlin.math.cos(Math.toRadians(c.lat))
        return (0..n).map { k ->
            val a = 2 * Math.PI * (k % n) / n
            LatLng(c.lat + dLat * kotlin.math.sin(a), c.lng + dLng * kotlin.math.cos(a))
        }
    }

    fun zonesJson(l: Layers): String = collection(l.pins.filter { it.alarm }.map { p ->
        feature(buildJsonObject {
            put("type", "Polygon")
            put("coordinates", JsonArray(listOf(JsonArray(circle(p.pos, p.zoneM).map { buildJsonArray { add(JsonPrimitive(it.lng)); add(JsonPrimitive(it.lat)) } }))))
        }, mapOf("yours" to JsonPrimitive(p.zoneIsYours), "current" to JsonPrimitive(p.current)))
    })

    /** Points the camera should frame first: you and the current stop, else every pin. */
    fun frame(me: LatLng?, layers: Layers): List<LatLng> {
        val current = layers.pins.filter { it.current }.map { it.pos }
        return when {
            me != null && current.isNotEmpty() -> listOf(me) + current
            me != null -> listOf(me)
            else -> layers.pins.map { it.pos }
        }
    }

    // ---- GeoJSON (MapLibre takes it as a string) ----

    private fun point(p: LatLng): JsonObject = buildJsonObject {
        put("type", "Point")
        put("coordinates", buildJsonArray { add(JsonPrimitive(p.lng)); add(JsonPrimitive(p.lat)) })
    }

    private fun line(ps: List<LatLng>): JsonObject = buildJsonObject {
        put("type", "LineString")
        put("coordinates", JsonArray(ps.map { buildJsonArray { add(JsonPrimitive(it.lng)); add(JsonPrimitive(it.lat)) } }))
    }

    private fun feature(geometry: JsonObject, props: Map<String, JsonPrimitive> = emptyMap()) = buildJsonObject {
        put("type", "Feature")
        put("geometry", geometry)
        put("properties", JsonObject(props))
    }

    private fun collection(features: List<JsonObject>) = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }.toString()

    fun pinsJson(l: Layers): String = collection(l.pins.map {
        feature(point(it.pos), mapOf(
            "label" to JsonPrimitive(it.label), "current" to JsonPrimitive(it.current), "alarm" to JsonPrimitive(it.alarm),
            "route" to JsonPrimitive(it.routeId), "leg" to JsonPrimitive(it.legIndex),
        ))
    })

    fun tracksJson(l: Layers): String = collection(l.tracks.map { feature(line(it)) })

    fun meJson(me: LatLng?, kind: Me): String =
        collection(listOfNotNull(me?.let { feature(point(it), mapOf("icon" to JsonPrimitive(kind.name.lowercase()))) }))
}

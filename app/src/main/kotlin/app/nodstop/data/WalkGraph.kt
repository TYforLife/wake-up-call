package app.nodstop.data

import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/**
 * Footpaths and streets you can walk, from OpenStreetMap (BACKLOG 1 and 27), as a graph. Walking from a place is the
 * shortest way along it (Dijkstra), not a straight line, and each walk comes with the way it goes, for the map.
 * Long segments are split every [STEP_M], so stepping onto the graph at its nearest point is within a few metres of
 * the path. Pedestrian areas (a plaza, a station concourse) are crossed, not walked round, and a locked or private
 * gate (any barrier closed to walkers) isn't walked through.
 * Pure: tested on the JVM with a small street fixture and, in CI, real paths.
 */
class WalkGraph private constructor(
    private val lat: DoubleArray,
    private val lng: DoubleArray,
    private val adj: Array<IntArray>,
    private val len: Array<DoubleArray>,
) {
    val size: Int get() = lat.size
    private fun pos(i: Int) = LatLng(lat[i], lng[i])

    private val grid = HashMap<Long, MutableList<Int>>().also { g ->
        for (i in lat.indices) g.getOrPut(key(cell(lat[i]), cell(lng[i]))) { ArrayList() } += i
    }

    /** Which joined-up network each point is on, and how many points that network has. */
    private val network = IntArray(size) { -1 }
    private val networkSize = ArrayList<Int>()

    init {
        val stack = ArrayDeque<Int>()
        for (s in 0 until size) {
            if (network[s] >= 0) continue
            val id = networkSize.size
            var n = 0
            network[s] = id; stack.addLast(s)
            while (stack.isNotEmpty()) {
                val u = stack.removeLast(); n++
                for (v in adj[u]) if (network[v] < 0) { network[v] = id; stack.addLast(v) }
            }
            networkSize += n
        }
    }
    private val largest = networkSize.indices.maxByOrNull { networkSize[it] } ?: -1

    /** A street network, not a lane inside a building or a path fragment on its own. */
    private fun isStreets(i: Int) = network[i] == largest || networkSize[network[i]] >= MIN_STREETS_POINTS

    private fun cell(deg: Double) = floor(deg / CELL).toLong()
    private fun key(cy: Long, cx: Long): Long = (cy shl 32) xor (cx and 0xffffffffL)

    /** Every point within [maxM] of [p] that [ok] accepts, nearest first. */
    private fun near(p: LatLng, maxM: Double, ok: (Int) -> Boolean = { true }): List<Pair<Int, Double>> {
        val cellM = CELL * M_PER_DEG * cos(Math.toRadians(p.lat))
        val r = ceil(maxM / cellM).toLong()
        val cy = cell(p.lat)
        val cx = cell(p.lng)
        val out = ArrayList<Pair<Int, Double>>()
        for (dy in -r..r) for (dx in -r..r) {
            grid[key(cy + dy, cx + dx)]?.forEach { i ->
                if (!ok(i)) return@forEach
                val d = Geo.distanceM(p, pos(i))
                if (d <= maxM) out += i to d
            }
        }
        return out.sortedBy { it.second }
    }

    /**
     * Walks from [from] along the paths, up to [maxM]; null if [from] is more than [PLACE_SNAP_M] from any street.
     * The walk starts at the nearest point of a street network, so a lane beside you that leads nowhere doesn't trap it.
     */
    fun from(from: LatLng, maxM: Double = Planner.MAX_WALK_M): Planner.Walks? {
        val (start, snap) = near(from, PLACE_SNAP_M, ::isStreets).firstOrNull() ?: return null
        val dist = DoubleArray(size) { Double.MAX_VALUE }
        val prev = IntArray(size) { -1 }
        dist[start] = snap
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        queue += snap to start
        while (queue.isNotEmpty()) {
            val (d, u) = queue.poll() ?: break
            if (d > dist[u] || d > maxM) continue
            for (k in adj[u].indices) {
                val v = adj[u][k]
                val nd = d + len[u][k]
                if (nd < dist[v]) { dist[v] = nd; prev[v] = u; queue += nd to v }
            }
        }
        fun reached(i: Int) = dist[i] <= maxM
        return Planner.Walks { p ->
            // The stop's own kerb: its nearest point, or a reached one barely further (a lane that isn't joined up
            // lies right beside the street). A reached point well beyond that is across something you can't walk.
            val nearest = near(p, STOP_SNAP_M).firstOrNull() ?: return@Walks null
            val (end, endSnap) = (if (reached(nearest.first)) nearest else near(p, nearest.second + STOP_SNAP_SLACK_M, ::reached).firstOrNull())
                ?: return@Walks null
            val way = ArrayList<LatLng>()
            way += p
            var i = end
            while (i >= 0) { way += pos(i); i = prev[i] }
            way += from
            way.reverse()
            Planner.Path(dist[end] + endSnap, way)
        }
    }

    companion object {
        /** About 220 m north to south: a point is looked up in the cells around it. */
        private const val CELL = 0.002
        /** Metres in a degree of latitude (and of longitude at the equator). */
        private const val M_PER_DEG = 111_320.0
        /** How far a place may be from the streets: a park, a campus, a big building. */
        const val PLACE_SNAP_M = 250.0
        /** How far a stop may be from the paths (a stop on the kerb, a bay in a station). */
        const val STOP_SNAP_M = 80.0
        /** A reached point this much further than the stop's nearest point is still the stop's kerb. */
        const val STOP_SNAP_SLACK_M = 20.0
        /** Points in a network of streets (25 m apart: 2.5 km of path), not a fragment. */
        const val MIN_STREETS_POINTS = 100
        /** Segments longer than this get points in between. */
        const val STEP_M = 25.0
        /** An area's outline with more points than this is walked round its edge (keeps the crossings bounded). */
        private const val MAX_AREA_POINTS = 120

        /**
         * What may be walked: every way but cars-only and buses-only roads, and corridors inside buildings (a walk goes
         * round a hospital, not through its wards, as Google's does).
         */
        private const val WALKABLE =
            "way[\"highway\"][\"highway\"!~\"^(motorway|motorway_link|trunk|trunk_link|construction|proposed|bus_guideway|busway|raceway|corridor)$\"]" +
                "[\"foot\"!~\"^(no|private)$\"][\"access\"!~\"^(no|private)$\"]"
        /**
         * State highways in town are often trunk roads with their footpath on the road itself (`sidewalk=*`), not drawn
         * apart: walkable, unless walking is barred, it's a motor road, or its footpaths are drawn separately.
         */
        private const val TRUNK =
            "way[\"highway\"~\"^(trunk|trunk_link)$\"][\"foot\"!~\"^(no|private|use_sidepath)$\"][\"access\"!~\"^(no|private)$\"]" +
                "[\"motorroad\"!=\"yes\"][\"sidewalk\"!~\"^(no|none|separate)$\"]"

        /** Barriers on the ways that walkers can't pass: closed to them, or locked. */
        private const val CLOSED =
            "node(w.w)[\"barrier\"][\"access\"~\"^(no|private)$\"][\"foot\"!~\"^(yes|designated|permissive)$\"];out ids qt;" +
                "node(w.w)[\"barrier\"][\"foot\"~\"^(no|private)$\"];out ids qt;" +
                "node(w.w)[\"barrier\"][\"locked\"=\"yes\"];out ids qt;"

        /**
         * The query for the walkable ways around [p] (a square of [radiusM]): ways and their points with no tags, then
         * the ids of the ways that are areas and of the barriers you can't pass.
         */
        fun query(p: LatLng, radiusM: Double): String {
            val dLat = radiusM / M_PER_DEG
            val dLng = radiusM / (M_PER_DEG * cos(Math.toRadians(p.lat)))
            val bbox = "%.5f,%.5f,%.5f,%.5f".format(Locale.US, p.lat - dLat, p.lng - dLng, p.lat + dLat, p.lng + dLng)
            return "[out:json][timeout:25];($WALKABLE($bbox);$TRUNK($bbox);)->.w;(.w;>;);out skel qt;way.w[\"area\"=\"yes\"];out ids qt;$CLOSED"
        }

        /** Builds the graph from an Overpass reply to [query]: points, ways as lists of points, area and barrier ids. */
        fun parse(json: String): WalkGraph {
            val elements = Json.parseToJsonElement(json).jsonObject["elements"]?.jsonArray.orEmpty().map { it.jsonObject }
            val index = HashMap<Long, Int>()
            val la = ArrayList<Double>()
            val ln = ArrayList<Double>()
            // A barrier you can't pass comes again on its own (no position) after the points.
            val closed = HashSet<Long>()
            for (o in elements) {
                if (o.str("type") != "node") continue
                val id = o["id"]?.jsonPrimitive?.long ?: continue
                if ("lat" !in o) { closed += id; continue }
                val a = o["lat"]?.jsonPrimitive?.double ?: continue
                val b = o["lon"]?.jsonPrimitive?.double ?: continue
                index[id] = la.size; la += a; ln += b
            }
            val ways = elements.filter { it.str("type") == "way" }
            // An area's id comes on its own (no points) after the ways.
            val areas = ways.filter { "nodes" !in it }.mapNotNull { it["id"]?.jsonPrimitive?.long }.toSet()
            val edges = ArrayList<ArrayList<Pair<Int, Double>>>().apply { repeat(la.size) { add(ArrayList()) } }
            fun join(u: Int, v: Int, m: Double) { edges[u] += v to m; edges[v] += u to m }
            val shut = closed.mapNotNull { index[it] }.toSet()
            for (o in ways) {
                val refs = o["nodes"]?.jsonArray?.mapNotNull { index[it.jsonPrimitive.long] } ?: continue
                for ((u, v) in refs.zipWithNext()) {
                    if (u == v || u in shut || v in shut) continue
                    val d = Geo.distanceM(LatLng(la[u], ln[u]), LatLng(la[v], ln[v]))
                    val parts = maxOf(1, ceil(d / STEP_M).toInt())
                    var prev = u
                    for (k in 1..parts) {
                        val next = if (k == parts) v else {
                            val f = k.toDouble() / parts
                            la += la[u] + (la[v] - la[u]) * f; ln += ln[u] + (ln[v] - ln[u]) * f
                            edges.add(ArrayList())
                            la.size - 1
                        }
                        join(prev, next, d / parts)
                        prev = next
                    }
                }
                // Across an area: straight from each corner to every other.
                val id = o["id"]?.jsonPrimitive?.long
                val corners = refs.distinct()
                if (id in areas && corners.size <= MAX_AREA_POINTS && corners.none { it in shut }) {
                    for (a in corners.indices) for (b in a + 1 until corners.size) {
                        val u = corners[a]
                        val v = corners[b]
                        join(u, v, Geo.distanceM(LatLng(la[u], ln[u]), LatLng(la[v], ln[v])))
                    }
                }
            }
            return WalkGraph(
                la.toDoubleArray(), ln.toDoubleArray(),
                Array(edges.size) { i -> IntArray(edges[i].size) { edges[i][it].first } },
                Array(edges.size) { i -> DoubleArray(edges[i].size) { edges[i][it].second } },
            )
        }

        private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
    }
}

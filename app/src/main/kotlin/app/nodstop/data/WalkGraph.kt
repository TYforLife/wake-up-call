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
import java.util.PriorityQueue
import kotlin.math.floor

/**
 * Footpaths and streets you can walk, from OpenStreetMap (BACKLOG: "does it know road walk paths?"), as a graph.
 * Walking from a place is the shortest way along it (Dijkstra), not a straight line. You step onto the graph at
 * the nearest point of a path (long segments are split every [STEP_M], so that's within a few metres of the path).
 * Pure: tested on the JVM with a small street fixture.
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
        for (i in lat.indices) g.getOrPut(key(lat[i], lng[i])) { ArrayList() } += i
    }

    private fun key(la: Double, ln: Double): Long = (floor(la / CELL).toLong() shl 32) xor (floor(ln / CELL).toLong() and 0xffffffffL)

    /** The graph's nearest point to [p] within [maxM], and how far it is. */
    fun nearest(p: LatLng, maxM: Double = SNAP_M): Pair<Int, Double>? {
        val cy = floor(p.lat / CELL).toLong()
        val cx = floor(p.lng / CELL).toLong()
        var best = -1
        var bestD = maxM
        for (dy in -1L..1L) for (dx in -1L..1L) {
            grid[((cy + dy) shl 32) xor ((cx + dx) and 0xffffffffL)]?.forEach { i ->
                val d = Geo.distanceM(p, pos(i))
                if (d <= bestD) { best = i; bestD = d }
            }
        }
        return if (best < 0) null else best to bestD
    }

    /** Walking metres from [from] to everywhere within [maxM] along the paths; null if [from] isn't near a path. */
    fun from(from: LatLng, maxM: Double = Planner.MAX_WALK_M): Planner.Walks? {
        val (start, snap) = nearest(from) ?: return null
        val dist = DoubleArray(size) { Double.MAX_VALUE }
        dist[start] = snap
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        queue += snap to start
        while (queue.isNotEmpty()) {
            val (d, u) = queue.poll()
            if (d > dist[u] || d > maxM) continue
            for (k in adj[u].indices) {
                val v = adj[u][k]
                val nd = d + len[u][k]
                if (nd < dist[v]) { dist[v] = nd; queue += nd to v }
            }
        }
        return Planner.Walks { p ->
            nearest(p)?.let { (i, s) -> (dist[i] + s).takeIf { dist[i] <= maxM } }
        }
    }

    companion object {
        /** About 220 m: a stop or a place is looked up in its cell and the 8 around it. */
        private const val CELL = 0.002
        /** How far from a path you can still step onto it (a stop on the kerb, a house set back). */
        const val SNAP_M = 80.0
        /** Segments longer than this get points in between. */
        const val STEP_M = 25.0

        /** What may be walked (the Overpass query asks for these; the rest of `highway` is cars or buses only). */
        const val OVERPASS_FILTER =
            "[\"highway\"][\"highway\"!~\"^(motorway|motorway_link|trunk|trunk_link|construction|proposed|bus_guideway|busway|raceway)$\"]" +
                "[\"foot\"!~\"^(no|private)$\"][\"access\"!~\"^(no|private)$\"]"

        /** The query for the walkable ways around [p] (a square of [radiusM]), nodes included, no tags. */
        fun query(p: LatLng, radiusM: Double): String {
            val dLat = radiusM / 111_320.0
            val dLng = radiusM / (111_320.0 * Math.cos(Math.toRadians(p.lat)))
            val bbox = "%.5f,%.5f,%.5f,%.5f".format(java.util.Locale.US, p.lat - dLat, p.lng - dLng, p.lat + dLat, p.lng + dLng)
            return "[out:json][timeout:25];way$OVERPASS_FILTER($bbox);(._;>;);out skel qt;"
        }

        /** Builds the graph from an Overpass `out skel` reply: nodes with positions, ways as lists of nodes. */
        fun parse(json: String): WalkGraph {
            val elements = Json.parseToJsonElement(json).jsonObject["elements"]?.jsonArray.orEmpty()
            val index = HashMap<Long, Int>()
            val la = ArrayList<Double>()
            val ln = ArrayList<Double>()
            for (e in elements) {
                val o = e.jsonObject
                if (o.str("type") != "node") continue
                val id = o["id"]?.jsonPrimitive?.long ?: continue
                val a = o["lat"]?.jsonPrimitive?.double ?: continue
                val b = o["lon"]?.jsonPrimitive?.double ?: continue
                index[id] = la.size; la += a; ln += b
            }
            val edges = ArrayList<ArrayList<Pair<Int, Double>>>().apply { repeat(la.size) { add(ArrayList()) } }
            for (e in elements) {
                val o = e.jsonObject
                if (o.str("type") != "way") continue
                val refs = o["nodes"]?.jsonArray?.mapNotNull { index[it.jsonPrimitive.long] } ?: continue
                for ((u, v) in refs.zipWithNext()) {
                    if (u == v) continue
                    val d = Geo.distanceM(LatLng(la[u], ln[u]), LatLng(la[v], ln[v]))
                    val parts = maxOf(1, Math.ceil(d / STEP_M).toInt())
                    var prev = u
                    for (k in 1..parts) {
                        val next = if (k == parts) v else {
                            val f = k.toDouble() / parts
                            la += la[u] + (la[v] - la[u]) * f; ln += ln[u] + (ln[v] - ln[u]) * f
                            edges.add(ArrayList())
                            la.size - 1
                        }
                        edges[prev] += next to d / parts; edges[next] += prev to d / parts
                        prev = next
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

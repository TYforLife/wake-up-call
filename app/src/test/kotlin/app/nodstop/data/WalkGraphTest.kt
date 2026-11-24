package app.nodstop.data

import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class WalkGraphTest {
    private val o = LatLng(-36.86, 174.76)
    private fun at(northM: Double, eastM: Double) =
        LatLng(o.lat + northM / 111_320.0, o.lng + eastM / (111_320.0 * Math.cos(Math.toRadians(o.lat))))
    private fun node(id: Int, p: LatLng) = """{"type":"node","id":$id,"lat":${p.lat},"lon":${p.lng}}"""
    private fun way(id: Int, vararg nodes: Int) = """{"type":"way","id":$id,"nodes":[${nodes.joinToString(",")}]}"""
    private fun graph(vararg elements: String) = WalkGraph.parse("""{"elements":[${elements.joinToString(",")}]}""")
    private fun length(ps: List<LatLng>) = ps.zipWithNext().sumOf { (a, b) -> Geo.distanceM(a, b) }

    // An L of streets: west (0,0) to the corner (300 m north), then 300 m east; and a lane far away, on its own.
    private val corner = at(300.0, 0.0)
    private val east = at(300.0, 300.0)
    private val streets = arrayOf(
        node(1, o), node(2, corner), node(3, east), node(9, at(2_000.0, 2_000.0)), node(10, at(2_000.0, 2_100.0)),
        way(100, 1, 2), way(101, 2, 3), way(102, 9, 10),
    )
    private val json = """{"elements":[${streets.joinToString(",")}]}"""

    @Test
    fun `walking goes round the corner, not across - and steps onto a path from a few metres off it`() {
        val g = WalkGraph.parse(json)
        val start = at(0.0, 5.0)
        val w = g.from(start)!!
        val stop = at(305.0, 300.0)
        val toEast = w.to(stop)!!
        val straight = Geo.distanceM(start, stop)
        assertTrue(toEast.meters in 595.0..625.0, "round the corner: ${toEast.meters} m (straight: ${straight.toInt()} m)")
        // Halfway up the first street (between two OSM points): close to 150 m, not to the nearest end.
        assertEquals(150.0, w.to(at(150.0, 3.0))!!.meters, 30.0)
    }

    @Test
    fun `a walk's way - from the place, along the paths, round the corner, to the stop, as long as its metres`() {
        val start = at(0.0, 5.0)
        val stop = at(305.0, 300.0)
        val walk = WalkGraph.parse(json).from(start)!!.to(stop)!!
        assertEquals(start, walk.points.first())
        assertEquals(stop, walk.points.last())
        assertTrue(walk.points.any { Geo.distanceM(it, corner) < 1.0 }, "by the corner")
        assertEquals(walk.meters, length(walk.points), walk.meters * 0.005)
    }

    @Test
    fun `no way there along the paths - it can't be walked, not a straight line`() {
        val g = WalkGraph.parse(json)
        assertNull(g.from(at(-1_000.0, -1_000.0)), "nowhere near a street")
        assertNull(g.from(at(0.0, 0.0))!!.to(at(2_000.0, 2_050.0)), "a lane not joined to this street")
        assertNull(g.from(at(0.0, 0.0))!!.to(at(-900.0, 0.0)), "nowhere near a path")
        // A walk longer than it may be.
        assertNull(g.from(at(0.0, 0.0), maxM = 400.0)!!.to(east))
    }

    @Test
    fun `a place off the streets - a park or a campus - still walks, from its nearest street`() {
        val inPark = at(150.0, -200.0)
        val walk = WalkGraph.parse(json).from(inPark)!!.to(corner)!!
        assertEquals(200.0 + 150.0, walk.meters, 15.0)
        assertEquals(inPark, walk.points.first())
        // Too far from any street to say.
        assertNull(WalkGraph.parse(json).from(at(150.0, -300.0)))
    }

    @Test
    fun `a lane right beside the street that leads nowhere - the stop is still on the street`() {
        // A 40 m lane 4 m east of the first street, not joined to it; the stop is between them.
        val g = graph(*streets, node(20, at(100.0, 4.0)), node(21, at(140.0, 4.0)), way(103, 20, 21))
        val walk = g.from(o)!!.to(at(120.0, 2.5))
        assertNotNull(walk)
        assertEquals(120.0, walk!!.meters, 15.0)
    }

    @Test
    fun `a stop across something you can't walk over - not reached by stepping across it`() {
        // A stop 60 m east of the first street, on a lane of its own (a busway's platform, say).
        val g = graph(*streets, node(20, at(100.0, 60.0)), node(21, at(140.0, 60.0)), way(103, 20, 21))
        assertNull(g.from(o)!!.to(at(120.0, 61.0)))
    }

    @Test
    fun `you start on the streets, not on a fragment beside you`() {
        // A 30 m path fragment 3 m from where you stand; the streets are 40 m away.
        val g = graph(*streets, node(20, at(100.0, 43.0)), node(21, at(130.0, 43.0)), way(103, 20, 21))
        val walk = g.from(at(115.0, 40.0))!!.to(corner)
        assertNotNull(walk, "walks from the street")
        assertEquals(40.0 + 185.0, walk!!.meters, 15.0)
    }

    @Test
    fun `a locked or private gate isn't walked through - the walk goes round`() {
        // A short cut from the first street to the east end, through a gate; the streets go round the corner.
        val gate = at(150.0, 150.0)
        val ways = arrayOf(*streets, node(31, gate), way(104, 1, 31, 3))
        val open = graph(*ways)
        assertTrue(open.from(o)!!.to(east)!!.meters < 450.0, "the short cut, while the gate is open")
        val shut = graph(*ways, """{"type":"node","id":31}""")
        assertEquals(600.0, shut.from(o)!!.to(east)!!.meters, 10.0)
        assertTrue(WalkGraph.query(o, 1_000.0).contains("\"locked\"=\"yes\""))
    }

    @Test
    fun `a plaza is crossed, not walked round its edge`() {
        val a = at(0.0, 0.0)
        val b = at(0.0, 100.0)
        val c = at(100.0, 100.0)
        val d = at(100.0, 0.0)
        val g = graph(node(1, a), node(2, b), node(3, c), node(4, d), way(200, 1, 2, 3, 4, 1), """{"type":"way","id":200}""")
        assertEquals(Geo.distanceM(a, c), g.from(a)!!.to(c)!!.meters, 2.0)
        // The same outline as a plain way (a ring road) is walked round.
        val ring = graph(node(1, a), node(2, b), node(3, c), node(4, d), way(200, 1, 2, 3, 4, 1))
        assertEquals(200.0, ring.from(a)!!.to(c)!!.meters, 2.0)
    }

    @Test
    fun `the query asks for walkable ways and areas, trunk roads with footpaths included, motorways never`() {
        val q = WalkGraph.query(o, 1_000.0)
        assertTrue(q.contains("motorway") && q.contains("!~") && q.contains("out skel qt") && q.contains("out ids qt"))
        assertTrue(q.contains("\"highway\"~\"^(trunk|trunk_link)$\"") && q.contains("\"motorroad\"!=\"yes\"") && q.contains("use_sidepath"), q)
        assertTrue(q.contains("\"area\"=\"yes\""), q)
        // Not through buildings: their corridors aren't walked (Auckland City Hospital's wards, CI's real walks).
        assertTrue(q.contains("raceway|corridor)$"), q)
        assertTrue(q.contains("-36.86898") && q.contains("-36.85102"), q)
    }

    @Test
    fun `the box reaches every walk from every place that shares its cache file`() {
        // Two places with the same cache file are at most a cell of 0.001 degrees apart, corner to corner.
        val a = LatLng(-36.86049, 174.76049)
        val b = LatLng(-36.85951, 174.75951)
        assertEquals(Footpaths.key(a, Planner.MAX_WALK_M), Footpaths.key(b, Planner.MAX_WALK_M))
        val apart = Geo.distanceM(a, b)
        for (reach in listOf(Planner.MAX_WALK_M, Planner.CHANGE_WALK_M)) {
            assertTrue(Footpaths.radius(reach) - apart >= reach, "box ${Footpaths.radius(reach)} m for walks of $reach m, places $apart m apart")
        }
        // Walks to the first stop reach every stop the planner may walk to.
        assertTrue(Planner.MAX_WALK_M >= Planner.ACCESS_M && Planner.CHANGE_WALK_M >= Planner.CHANGE_M * Planner.DETOUR)
    }

    @Test
    fun `CI fetches the footpaths with the app's own query`() {
        val ci = File("../.github/workflows/android.yml").readText()
        val qs = Regex("""q_\w+='([^']*)'""").findAll(ci).map { it.groupValues[1] }.toList()
        assertTrue(WalkGraph.query(RealWalkTest.HOSPITAL, Footpaths.radius(Planner.MAX_WALK_M)) in qs, "the hospital's query in CI is the app's")
        assertTrue(WalkGraph.query(RealWalkTest.BRITOMART, Footpaths.radius(Planner.MAX_WALK_M)) in qs, "Britomart's query in CI is the app's")
    }

    @Test
    fun `the commute's footpaths - home, work, and the stops where you change buses`() {
        val home = LatLng(-36.78, 174.75)
        val work = LatLng(-36.85, 174.76)
        val r = Route(name = "To work", legs = listOf(LegDraft("Harbour", "-36.844, 174.768"), LegDraft("Queen St", "-36.8485, 174.7622")))
        assertEquals(
            listOf(home to Planner.MAX_WALK_M, work to Planner.MAX_WALK_M, LatLng(-36.844, 174.768) to Planner.CHANGE_WALK_M),
            Footpaths.commute(home, work, listOf(r)),
        )
    }

    @Test
    fun `only real footpaths are kept - not a refusal page, nor a reply that timed out`() {
        assertTrue(Footpaths.usable(json))
        assertTrue(!Footpaths.usable("<html><body>Too many requests</body></html>"))
        assertTrue(!Footpaths.usable("""{"elements":[],"remark":"runtime error: Query timed out"}"""))
        assertTrue(!Footpaths.usable("""{"elements":[]}"""), "nothing to walk on")
    }
}

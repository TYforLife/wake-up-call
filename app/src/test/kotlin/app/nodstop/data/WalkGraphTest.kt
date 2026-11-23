package app.nodstop.data

import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WalkGraphTest {
    private val o = LatLng(-36.86, 174.76)
    private fun at(northM: Double, eastM: Double) =
        LatLng(o.lat + northM / 111_320.0, o.lng + eastM / (111_320.0 * Math.cos(Math.toRadians(o.lat))))

    // An L of streets: west (0,0) to the corner (300 m north), then 300 m east; and a separate lane far away.
    private val corner = at(300.0, 0.0)
    private val east = at(300.0, 300.0)
    private val json = """
        {"elements":[
          {"type":"node","id":1,"lat":${o.lat},"lon":${o.lng}},
          {"type":"node","id":2,"lat":${corner.lat},"lon":${corner.lng}},
          {"type":"node","id":3,"lat":${east.lat},"lon":${east.lng}},
          {"type":"node","id":9,"lat":${at(2_000.0, 2_000.0).lat},"lon":${at(2_000.0, 2_000.0).lng}},
          {"type":"node","id":10,"lat":${at(2_000.0, 2_100.0).lat},"lon":${at(2_000.0, 2_100.0).lng}},
          {"type":"way","id":100,"nodes":[1,2]},
          {"type":"way","id":101,"nodes":[2,3]},
          {"type":"way","id":102,"nodes":[9,10]}
        ]}
    """.trimIndent()

    @Test
    fun `walking goes round the corner, not across - and steps onto a path from a few metres off it`() {
        val g = WalkGraph.parse(json)
        val w = g.from(at(0.0, 5.0))!!
        val toEast = w.to(at(305.0, 300.0))!!
        val straight = Geo.distanceM(at(0.0, 5.0), at(305.0, 300.0))
        assertTrue(toEast in 595.0..625.0, "round the corner: $toEast m (straight: ${straight.toInt()} m)")
        // Halfway up the first street (between two OSM points): close to 150 m, not to the nearest end.
        assertEquals(150.0, w.to(at(150.0, 3.0))!!, 30.0)
    }

    @Test
    fun `no path near, or not joined up - unknown, so the planner falls back to the straight line`() {
        val g = WalkGraph.parse(json)
        assertNull(g.from(at(-1_000.0, -1_000.0)), "nowhere near a path")
        assertNull(g.from(at(0.0, 0.0))!!.to(at(2_000.0, 2_050.0)), "a lane not joined to this street")
    }

    @Test
    fun `the query asks only for walkable ways in a small square`() {
        val q = WalkGraph.query(o, 1_000.0)
        assertTrue(q.contains("motorway") && q.contains("!~") && q.contains("out skel qt"))
        assertTrue(q.contains("-36.86898") && q.contains("-36.85102"), q)
    }

    @Test
    fun `only real footpaths are kept - not a refusal page, nor a reply that timed out`() {
        assertTrue(Footpaths.usable(json))
        assertTrue(!Footpaths.usable("<html><body>Too many requests</body></html>"))
        assertTrue(!Footpaths.usable("""{"elements":[],"remark":"runtime error: Query timed out"}"""))
        assertTrue(!Footpaths.usable("""{"elements":[]}"""), "nothing to walk on")
    }
}

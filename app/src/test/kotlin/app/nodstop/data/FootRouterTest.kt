package app.nodstop.data

import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The foot router, the last source before the straight line (BACKLOG 27): its replies, as OSRM sends them. */
class FootRouterTest {
    private val from = LatLng(-36.8601, 174.7697)
    private val stops = listOf(LatLng(-36.8611, 174.7701), LatLng(-36.8580, 174.7650), LatLng(-36.8700, 174.7800))

    @Test
    fun `the requests carry only the points, longitude first`() {
        val t = FootRouter.tableUrl(from, stops)
        assertTrue(t.startsWith("https://routing.openstreetmap.de/routed-foot/table/v1/foot/174.769700,-36.860100;174.770100,-36.861100;"), t)
        assertTrue(t.endsWith("?sources=0&annotations=distance"), t)
        assertTrue(FootRouter.routeUrl(from, stops[0]).contains("/route/v1/foot/174.769700,-36.860100;174.770100,-36.861100?overview=full&geometries=geojson"))
    }

    @Test
    fun `the table - metres to each stop, counting the steps onto the path - a stop it can't reach can't be walked`() {
        val reply = """
            {"code":"Ok","distances":[[0,180.5,512.0,null]],
             "sources":[{"hint":"a","distance":4.5,"name":"Park Road","location":[174.7697,-36.8601]}],
             "destinations":[{"distance":4.5},{"distance":2.0},{"distance":0.5},{"distance":9.0}]}
        """.trimIndent()
        val w = FootRouter.parseTable(reply, stops)!!
        assertEquals(180.5 + 4.5 + 2.0, w.to(stops[0])!!.meters, 1e-9)
        assertEquals(512.0 + 4.5 + 0.5, w.to(stops[1])!!.meters, 1e-9)
        assertNull(w.to(stops[2]), "unreachable")
        assertNull(w.to(LatLng(-36.0, 174.0)), "a stop it wasn't asked about")
        assertTrue(w.to(stops[0])!!.points.isEmpty(), "the table has lengths, not ways")
    }

    @Test
    fun `the route - its metres and the way it goes, from the place to the stop`() {
        val reply = """
            {"code":"Ok","routes":[{"geometry":{"coordinates":[[174.7698,-36.8602],[174.7700,-36.8608],[174.7701,-36.8610]],"type":"LineString"},
              "distance":130.2,"duration":93.1}],
             "waypoints":[{"distance":3.0,"location":[174.7698,-36.8602]},{"distance":1.5,"location":[174.7701,-36.8610]}]}
        """.trimIndent()
        val p = FootRouter.parseRoute(reply, from, stops[0])!!
        assertEquals(130.2 + 3.0 + 1.5, p.meters, 1e-9)
        assertEquals(listOf(from, LatLng(-36.8602, 174.7698), LatLng(-36.8608, 174.77), LatLng(-36.861, 174.7701), stops[0]), p.points)
    }

    @Test
    fun `anything but OSRM's answer is no answer`() {
        assertNull(FootRouter.parseTable("""{"code":"TooBig","message":"Too many table coordinates"}""", stops))
        assertNull(FootRouter.parseTable("<html>502 Bad Gateway</html>", stops))
        assertNull(FootRouter.parseTable("""{"code":"Ok","distances":[[0,1.0]]}""", stops), "a row that doesn't match the stops")
        assertNull(FootRouter.parseRoute("""{"code":"NoRoute","routes":[]}""", from, stops[0]))
    }
}

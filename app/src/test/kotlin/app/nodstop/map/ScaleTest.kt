package app.nodstop.map

import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScaleTest {
    @Test
    fun `the scale bar - the longest round distance that fits`() {
        assertEquals(100 to 50.0, Scale.pick(2.0))
        assertEquals(500 to 50.0, Scale.pick(10.0))
        assertEquals(50, Scale.pick(1.0).first)
        assertEquals(10, Scale.pick(0.01).first, "zoomed right in: the smallest step")
        assertEquals("200 m", Scale.label(200))
        assertEquals("2 km", Scale.label(2_000))
    }

    @Test
    fun `a trip's circles - the stop it's watching, then the stops to come fainter, none behind you`() {
        fun pin(i: Int, current: Boolean, route: String = "r") = MapModel.Pin(LatLng(-36.8 - i * 0.01, 174.7), "S$i", current, true, route, i, 200.0)
        val pins = listOf(pin(0, false), pin(1, true), pin(2, false), pin(0, false, "other"))
        val zones = MapModel.tripZones(MapModel.Layers(pins, emptyList()))
        assertEquals(listOf(1 to true, 2 to false), zones.map { it.legIndex to it.current })
        assertEquals(emptyList<MapModel.Pin>(), MapModel.tripZones(MapModel.Layers(listOf(pin(0, false)), emptyList())))
    }

    @Test
    fun `the trip legs on the map - the bus stops, else the learned ride, else a straight line, the current one marked`() {
        val a = LatLng(-36.80, 174.75); val b = LatLng(-36.85, 174.76); val start = LatLng(-36.78, 174.75)
        val bus = listOf(start, LatLng(-36.79, 174.75), a)
        val learned = listOf(a, LatLng(-36.82, 174.76), b)
        val paths = MapModel.legPaths(listOf(a, b), current = 1, start = start, bus = { if (it == 0) bus else null }, learned = { if (it == 1) learned else null })
        assertEquals(listOf(bus to false, learned to true), paths.map { it.points to it.current })
        val straight = MapModel.legPaths(listOf(a, b), current = 0, start = null, bus = { null }, learned = { null })
        assertEquals(listOf(listOf(a, b) to false), straight.map { it.points to it.current }, "no start known: the first leg has no line yet")
        assertTrue(MapModel.legPathsJson(MapModel.Layers(emptyList(), emptyList(), paths)).contains("\"current\":true"))
    }
}

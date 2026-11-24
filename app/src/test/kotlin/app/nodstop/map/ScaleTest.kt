package app.nodstop.map

import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScaleTest {
    @Test
    fun `the scale bar - always the same length, its label in m under 1 km and km above`() {
        assertEquals("140 m", Scale.label(2.0)) // 72 dp at 2 m per dp
        assertEquals("700 m", Scale.label(9.7)) // 698 m, to the nearest 50
        assertEquals("1.4 km", Scale.label(20.0))
        assertEquals("14 km", Scale.label(200.0))
        assertEquals("10 m", Scale.label(0.01), "zoomed right in: never 0")
    }

    @Test
    fun `editing a circle - tap its stop first, then only that circle's edge can be dragged`() {
        // A tap selects the stop nearest it on screen, within a finger's width; a tap elsewhere selects none.
        assertEquals(1, CircleDrag.pickStop(listOf(80.0, 12.0, 30.0), 32.0))
        assertEquals(null, CircleDrag.pickStop(listOf(80.0, 45.0), 32.0), "inside a circle but not on its stop: nothing")
        // The selected circle is drawn bold.
        val a = MapModel.Pin(LatLng(-36.80, 174.75), "A", true, true, "r", 0, 400.0)
        val b = MapModel.Pin(LatLng(-36.82, 174.76), "B", false, true, "r", 1, 300.0)
        val json = MapModel.zonesJson(MapModel.Layers(listOf(a, b), emptyList()), selectedLeg = 1)
        assertEquals(1, Regex("\"selected\":true").findAll(json).count())
        // Of the edges under a finger, the nearest (px: touch distance from centre, radius).
        assertEquals(1, CircleDrag.pickEdge(listOf(200.0 to 180.0, 95.0 to 100.0), 28.0))
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

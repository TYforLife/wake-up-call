package app.nodstop.map

import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
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
}

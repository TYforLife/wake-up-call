package app.nodstop.map

import app.nodstop.map.MapPalette.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapPaletteTest {
    @Test
    fun `liberty's layers get Google's roles - land, water, parks, yellow main roads, white streets`() {
        assertEquals(Role.LAND, MapPalette.role("background", "background"))
        assertEquals(Role.WATER, MapPalette.role("fill", "water"))
        assertEquals(Role.PARK, MapPalette.role("fill", "park"))
        assertEquals(Role.PARK, MapPalette.role("fill", "landcover_wood"))
        assertEquals(Role.BUILDING, MapPalette.role("fill", "building"))
        assertEquals(Role.MOTORWAY, MapPalette.role("line", "road_motorway"))
        assertEquals(Role.MOTORWAY_CASING, MapPalette.role("line", "bridge_motorway_casing"))
        assertEquals(Role.MAJOR, MapPalette.role("line", "road_trunk_primary"))
        assertEquals(Role.MAJOR_CASING, MapPalette.role("line", "road_secondary_tertiary_casing"))
        assertEquals(Role.STREET, MapPalette.role("line", "road_minor"))
        assertEquals(Role.STREET_CASING, MapPalette.role("line", "road_minor_casing"))
        assertEquals(Role.PATH, MapPalette.role("line", "road_path_pedestrian"))
        assertEquals(Role.RAIL, MapPalette.role("line", "road_major_rail"))
        assertEquals(Role.WATER, MapPalette.role("line", "waterway_river"))
        assertEquals(Role.BOUNDARY, MapPalette.role("line", "boundary_3"))
        assertEquals(Role.LABEL_PLACE, MapPalette.role("symbol", "label_city"))
        assertEquals(Role.LABEL_WATER, MapPalette.role("symbol", "water_name_point_label"))
        assertEquals(Role.LABEL, MapPalette.role("symbol", "poi_r20"))
        assertNull(MapPalette.role("line", "aeroway_runway"))
        assertNull(MapPalette.role("raster", "hillshade"))
    }

    @Test
    fun `day is Google's light map, night is its dark one - every role has a colour in both`() {
        for (c in listOf(MapPalette.day, MapPalette.night)) assertEquals(Role.entries.toSet(), c.base.keys)
        val d = MapPalette.day.base
        // Grey-beige land, blue water, green parks, white streets, yellow-orange main roads.
        assertEquals(0xFFF2EFE9, d[Role.LAND])
        assertEquals(0xFFAADAFF, d[Role.WATER])
        assertEquals(0xFFFFFFFF, d[Role.STREET])
        assertTrue(yellowish(d.getValue(Role.MAJOR)) && yellowish(d.getValue(Role.MOTORWAY)))
        assertTrue(greenish(d.getValue(Role.PARK)))
        // Night: Google's navy.
        assertEquals(0xFF242F3E, MapPalette.night.base[Role.LAND])
        // Google's red pin and route blue.
        assertEquals(0xFFEA4335, MapPalette.day.pin)
        assertEquals(0xFF4285F4, MapPalette.day.route)
        assertEquals(MapPalette.night, MapPalette.colors(dark = true))
    }

    @Test
    fun `bus stops show - POIs of class bus, from street zoom`() {
        assertTrue(MapPalette.isBusStop("bus", "bus_stop"))
        assertTrue(MapPalette.isBusStop(null, "bus_station"))
        assertFalse(MapPalette.isBusStop("cafe", "cafe"))
        assertTrue(MapPalette.BUS_STOP_MIN_ZOOM <= 15f)
        assertTrue(MapPalette.BUS_STOP_LABEL_MIN_ZOOM > MapPalette.BUS_STOP_MIN_ZOOM)
    }

    private fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toInt()
    private fun yellowish(c: Long) = ch(c, 16) > 220 && ch(c, 8) > 150 && ch(c, 0) < 180
    private fun greenish(c: Long) = ch(c, 8) > ch(c, 16) && ch(c, 8) > ch(c, 0)
}

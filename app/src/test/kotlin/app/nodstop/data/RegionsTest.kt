package app.nodstop.data

import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegionsTest {
    @Test
    fun `the outline is the hull of the stops - corners only, closed, nothing outside it`() {
        val corners = listOf(LatLng(-37.0, 174.5), LatLng(-37.0, 175.0), LatLng(-36.5, 175.0), LatLng(-36.5, 174.5))
        val inside = (1..50).map { LatLng(-37.0 + it * 0.009, 174.5 + (it * 37 % 50) * 0.009) }
        val hull = Regions.hull(corners + inside + corners.first())
        assertEquals(5, hull.size, "four corners and back to the first")
        assertEquals(hull.first(), hull.last())
        assertEquals(corners.toSet(), hull.toSet())
        assertEquals(2, Regions.hull(listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0))).size, "too few for an outline")
    }

    @Test
    fun `Auckland first, with its welcome`() {
        assertEquals("Auckland Transport", Regions.all.first().operator)
        assertTrue(Regions.all.all { it.welcome.isNotBlank() })
    }
}

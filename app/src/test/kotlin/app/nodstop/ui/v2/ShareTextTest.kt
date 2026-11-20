package app.nodstop.ui.v2

import app.nodstop.data.LegDraft
import app.nodstop.data.MapsLink
import app.nodstop.data.MapsLink.Place
import app.nodstop.data.Route
import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShareTextTest {
    private val sky = Place("Sky Tower", LatLng(-36.8485, 174.7622))
    private val routes = listOf(
        Route("w", "To work", listOf(LegDraft("A", "-36.8, 174.7"))),
        Route("h", "From work", List(3) { LegDraft("S$it", "-36.8, 174.7") }),
    )

    @Test
    fun `a shared place - add it to a route as its next stop, or start a new route`() {
        val ui = ShareText.of(MapsLink.Result.One(sky), routes)
        assertEquals("Add Sky Tower", ui.title)
        assertEquals(listOf("Add to To work", "Add to From work", "New route"), ui.options.map { it.label })
        assertEquals("As stop 2", ui.options[0].sub)
        assertFalse(ui.options[1].enabled, "a route has three stops at most")
        assertEquals(ShareOption.Action.AddTo("w"), ui.options[0].action)
        assertEquals(ShareOption.Action.NewRoute, ui.options.last().action)
    }

    @Test
    fun `shared directions - a new route with the stops you get off at, named`() {
        val dir = MapsLink.Result.Directions(listOf(Place("Britomart", LatLng(-36.8443, 174.7678)), Place("Museum", LatLng(-36.86, 174.77)), Place(null, LatLng(-36.87, 174.78))))
        val ui = ShareText.of(dir, routes)
        assertEquals("A route with 2 stops", ui.title)
        assertEquals("Museum → Stop 2", ui.options.single().sub)
        assertEquals(ShareOption.Action.NewRouteFromDirections, ui.options.single().action)
    }

    @Test
    fun `while a short link opens, and when it isn't a place`() {
        assertTrue(ShareText.of(MapsLink.Result.Short("https://maps.app.goo.gl/x", null), routes).busy)
        assertTrue(ShareText.of(MapsLink.Result.None, routes).options.isEmpty())
    }
}

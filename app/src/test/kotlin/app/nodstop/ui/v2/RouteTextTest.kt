package app.nodstop.ui.v2

import app.nodstop.data.LegDraft
import app.nodstop.data.Route
import app.nodstop.engine.Learned
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RouteTextTest {
    private val work = Route("w", "To work", listOf(LegDraft("Central Station", "-36.80, 174.70"), LegDraft("Harbour St", "-36.81, 174.71", wakeRadiusM = 250.0)))
    private val home = Route("h", "From work", listOf(LegDraft("Central Station", "-36.80, 174.70"), LegDraft("Park Rd", "-36.82, 174.72", alarmEnabled = false), LegDraft("Hill Ave", "")))

    @Test
    fun `route rows - buses and rides, or the problem in words`() {
        assertEquals(RouteRowUi("w", "To work", "2 buses · 12 rides", false), RouteText.row(work) { Learned(it, emptyList(), 12) })
        assertEquals(RouteRowUi("w", "To work", "2 buses", false), RouteText.row(work) { null })
        assertEquals(RouteRowUi("h", "From work", "Hill Ave needs a stop", true), RouteText.row(home) { null })
        assertEquals("Add a bus", RouteText.problem(Route("n", "New route", emptyList())))
        assertEquals("Bus 1 needs a stop", RouteText.problem(Route("n", "New", listOf(LegDraft()))))
    }

    @Test
    fun `stops on the line - alarm, no alarm, not set, and the next thing it needs`() {
        val stops = RouteText.stops(home) { if (it.lat == -36.80) Learned(it, emptyList(), 3, leadS = 40.0) else null }
        assertEquals(StopRowUi(0, "Central Station", "Alarm 40 s before", StopRowUi.Kind.SET), stops[0])
        assertEquals(StopRowUi(1, "Park Rd", "No alarm", StopRowUi.Kind.NO_ALARM), stops[1])
        assertEquals(StopRowUi(2, "Hill Ave", "Set the stop", StopRowUi.Kind.NOT_SET), stops[2])
        assertEquals("Alarm 45 s before · within 250 m", RouteText.stops(work) { null }[1].detail)
        assertEquals("Set Hill Ave", RouteText.next(home))
        assertNull(RouteText.next(work))
        // Start from a later bus: only once the route is ready, and never the first bus.
        assertEquals(listOf("Harbour St"), RouteDetailUi("To work", RouteText.stops(work) { null }, RouteText.next(work), true).startFrom.map { it.title })
        assertEquals(emptyList<StopRowUi>(), RouteDetailUi("From work", stops, RouteText.next(home), false).startFrom)
    }

    @Test
    fun `circle choices - Auto, presets, and custom sizes`() {
        assertEquals(0, CircleChoice.index(null))
        assertEquals(2, CircleChoice.index(250.0))
        assertEquals(-1, CircleChoice.index(700.0))
        assertNull(CircleChoice.radius(0))
        assertEquals(400.0, CircleChoice.radius(3))
    }
}

class StopTextTest {
    @Test
    fun `picker words, paste errors, and where it opens`() {
        assertEquals("Hill Ave · wake me within", StopText.eyebrow("Hill Ave", null))
        assertEquals("Hill Ave · wake me within 700 m", StopText.eyebrow("Hill Ave", 700.0))
        assertNull(StopText.pasteError(""))
        assertNull(StopText.pasteError("-36.78, 174.75"))
        assertEquals("That's outside New Zealand.", StopText.pasteError("51.5, -0.12"))
        assertEquals("Can't read that. Paste \"-36.78, 174.75\" or a Maps link.", StopText.pasteError("Hill Ave"))
        assertEquals(true, StopText.pasteError("https://maps.app.goo.gl/abc")!!.startsWith("Short links"))
        assertEquals("-36.800000, 174.700000", StopText.coords(app.nodstop.engine.LatLng(-36.8, 174.7)))
        // Only the circle changed: the stop stays exactly as typed; moved, or new: the map's centre.
        assertEquals("-36.80, 174.70", StopText.saved("-36.80, 174.70", app.nodstop.engine.LatLng(-36.8000001, 174.7000001)))
        assertEquals("-36.801000, 174.700000", StopText.saved("-36.80, 174.70", app.nodstop.engine.LatLng(-36.801, 174.7)))
        assertEquals("-36.801000, 174.700000", StopText.saved("", app.nodstop.engine.LatLng(-36.801, 174.7)))

        val r = Route("r", "R", listOf(LegDraft("A", "-36.80, 174.70"), LegDraft("B", "")))
        val here = app.nodstop.engine.LatLng(-36.9, 174.8)
        assertEquals(app.nodstop.engine.LatLng(-36.80, 174.70), StopText.centre(r, 0, here))
        assertEquals(app.nodstop.engine.LatLng(-36.80, 174.70), StopText.centre(r, 1, here))
        assertEquals(here, StopText.centre(Route("n", "N", listOf(LegDraft())), 0, here))
        assertEquals(StopText.FALLBACK, StopText.centre(Route("n", "N", listOf(LegDraft())), 0, null))
    }
}

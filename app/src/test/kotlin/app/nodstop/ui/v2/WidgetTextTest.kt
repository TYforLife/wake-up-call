package app.nodstop.ui.v2

import app.nodstop.engine.AlarmReason
import app.nodstop.engine.LatLng
import app.nodstop.engine.Phase
import app.nodstop.engine.Status
import app.nodstop.trip.TripService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** BACKLOG F10: the home screen widget says what Now, the trip sheet and the notification say. */
class WidgetTextTest {
    private val toWork = RouteOption("r1", "To work", "Smales Farm, then Symonds St")
    private fun s(h: Int, m: Int) = h * 3600 + m * 60

    private fun status(
        phase: Phase = Phase.RIDING, d: Double? = 2_340.0, eta: Double? = 372.0, gpsOk: Boolean = true, age: Double? = 1.0,
    ) = Status(
        legIndex = 0, legCount = 2, legName = null, phase = phase, distanceM = d, etaS = eta, vEstKmh = null,
        speedKmh = null, lastFixAgeS = age, gpsOk = gpsOk, alarmAtMs = 1_000L, alarmReason = AlarmReason.WAKE,
        ringReason = null, onLearnedRoute = false, learnedRides = 0, startDistanceM = null,
    )

    private fun trip(st: Status, leg: Int = 0, radius: Double? = null) = TripService.TripUi(
        "To work", listOf("Smales Farm", "Symonds St"), listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.0)), leg, st, false, 0,
        listOf(radius, null),
    )

    private val clock: (Long) -> String = { "7:45" }

    @Test
    fun `idle - the likely route, its next two buses and Start`() {
        val ui = WidgetText.idle(toWork, "866", listOf(s(7, 35), s(7, 43), s(7, 51), s(7, 59)), s(7, 40))
        assertEquals(WidgetUi("To work · 866 7:43, 7:51", "Smales Farm, then Symonds St", WidgetUi.Action("Start", WidgetUi.Kind.START, "r1")), ui)
        assertFalse(ui.live)
    }

    @Test
    fun `idle - a bus leaving now is still shown, one gone is not`() {
        assertEquals("To work · 866 7:43", WidgetText.idle(toWork, "866", listOf(s(7, 43)), s(7, 43)).title)
        assertEquals("To work", WidgetText.idle(toWork, "866", listOf(s(7, 43)), s(7, 43) + 1).title)
    }

    @Test
    fun `idle - no bus known or no timetable, just the route`() {
        assertEquals("To work", WidgetText.idle(toWork, null, listOf(s(7, 43)), s(7, 40)).title)
        assertEquals("To work", WidgetText.idle(toWork, "866", emptyList(), s(7, 40)).title)
        assertEquals(WidgetUi.Kind.START, WidgetText.idle(toWork, null, emptyList(), 0).action?.kind)
    }

    @Test
    fun `idle - a route that can't start says why and opens the app, never Start`() {
        val ui = WidgetText.idle(toWork.copy(problem = "Stop 2 has no place"), "866", listOf(s(7, 43)), s(7, 40))
        assertEquals("Stop 2 has no place", ui.line)
        assertEquals(WidgetUi.Kind.OPEN, ui.action?.kind)
    }

    @Test
    fun `idle - no routes yet, set up home and work`() {
        val ui = WidgetText.idle(null, null, emptyList(), 0)
        assertEquals("Set up home and work", ui.line)
        assertEquals(WidgetUi.Kind.OPEN, ui.action?.kind)
    }

    @Test
    fun `a trip the app lost while its alarm is set says so, as Now does`() {
        assertEquals("Its alarm is set for 7:49", WidgetText.interrupted("7:49").line)
        assertEquals("The app was closed during it", WidgetText.interrupted("").line)
        assertTrue(WidgetText.interrupted("7:49").live)
    }

    @Test
    fun `riding - the folded trip sheet, the route and I'm off this bus`() {
        val t = trip(status())
        val ui = WidgetText.riding(t, clock, 0)
        assertEquals(CompactSheetText.of(t, clock, 0).line, ui.title)
        assertEquals("To work · bus 1 of 2", ui.line)
        assertEquals(WidgetUi.Action("I'm off this bus", WidgetUi.Kind.OFF), ui.action)
        assertTrue(ui.live)
        assertFalse(ui.danger)
    }

    @Test
    fun `riding - its button is the notification's, for every phase`() {
        for (p in Phase.entries) for (leg in 0..1) {
            val t = trip(status(phase = p), leg)
            val off = TripNoteText.actions(t, null).firstOrNull { it.kind == TripActionUi.Kind.OFF }
            assertEquals(off?.label, WidgetText.riding(t, clock, 0).action?.label, "$p, leg $leg")
        }
    }

    @Test
    fun `riding - waiting for the second bus is Skip this bus`() {
        assertEquals("Skip this bus", WidgetText.riding(trip(status(phase = Phase.WAITING), leg = 1), clock, 0).action?.label)
    }

    @Test
    fun `riding - GPS lost is red`() {
        assertTrue(WidgetText.riding(trip(status(gpsOk = false, age = 40.0)), clock, 0).danger)
    }

    @Test
    fun `a trip done has no button`() {
        assertNull(WidgetText.riding(trip(status(phase = Phase.DONE)), clock, 0).action)
    }

    @Test
    fun `the widget redraws a minute after the bus it shows leaves`() {
        assertEquals(s(7, 44), WidgetText.redrawAtS(listOf(s(7, 35), s(7, 43)), s(7, 40)))
        assertNull(WidgetText.redrawAtS(listOf(s(7, 35)), s(7, 40)))
    }
}

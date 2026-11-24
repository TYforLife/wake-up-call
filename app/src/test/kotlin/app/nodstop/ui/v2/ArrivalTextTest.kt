package app.nodstop.ui.v2

import app.nodstop.engine.AlarmReason
import app.nodstop.engine.LatLng
import app.nodstop.engine.Phase
import app.nodstop.engine.Status
import app.nodstop.trip.TripService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** BACKLOG F15: "Work about 8:22" on the sheet, from the journey's own times. */
class ArrivalTextTest {
    private fun trip(leg: Int, route: String = "To work") = TripService.TripUi(
        route, listOf("Smales Farm", "Symonds St"), listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.0)), leg,
        Status(
            legIndex = leg, legCount = 2, legName = null, phase = Phase.RIDING, distanceM = 2_000.0, etaS = 300.0, vEstKmh = null,
            speedKmh = null, lastFixAgeS = 1.0, gpsOk = true, alarmAtMs = 1_000L, alarmReason = AlarmReason.WAKE,
            ringReason = null, onLearnedRoute = false, learnedRides = 0, startDistanceM = null,
        ),
        false, 0,
    )

    private val rows = listOf(
        JourneyRowUi("~7:58", "Smales Farm", "NX1 · next stop", JourneyRowUi.State.NOW),
        JourneyRowUi("~8:22", "Symonds St", "866 · leaves 8:04", JourneyRowUi.State.LATER),
    )

    @Test
    fun `on the first bus - when the whole trip gets to work`() {
        assertEquals("Work about 8:22", JourneyText.arrival(trip(0), rows))
    }

    @Test
    fun `a route not named To - its last stop`() {
        assertEquals("Symonds St about 8:22", JourneyText.arrival(trip(0, "Route 1"), rows))
    }

    @Test
    fun `on the last bus the sheet already says it, and an unknown bus says nothing`() {
        assertNull(JourneyText.arrival(trip(1), rows))
        assertNull(JourneyText.arrival(trip(0), rows.dropLast(1) + JourneyRowUi("", "Symonds St", "Stop 2", JourneyRowUi.State.LATER)))
    }
}

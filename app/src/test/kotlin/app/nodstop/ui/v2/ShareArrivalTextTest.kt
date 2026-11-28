package app.nodstop.ui.v2

import app.nodstop.engine.AlarmReason
import app.nodstop.engine.LatLng
import app.nodstop.engine.Mode
import app.nodstop.engine.Phase
import app.nodstop.engine.Status
import app.nodstop.trip.TripService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** BACKLOG F16: "On the 866, at Symonds St about 8:22 · Sleepers". Text only, the sheet's own time. */
class ShareArrivalTextTest {
    private fun trip(phase: Phase = Phase.RIDING, eta: Double? = 600.0, modes: List<Mode> = emptyList()) = TripService.TripUi(
        "To work", listOf("Symonds St"), listOf(LatLng(-36.85, 174.76)), 0,
        Status(
            legIndex = 0, legCount = 1, legName = null, phase = phase, distanceM = 4_000.0, etaS = eta, vEstKmh = null,
            speedKmh = null, lastFixAgeS = 1.0, gpsOk = true, alarmAtMs = 1_000L, alarmReason = AlarmReason.WAKE,
            ringReason = null, onLearnedRoute = false, learnedRides = 0, startDistanceM = null,
        ),
        false, 0, legModes = modes,
    )

    /** "8:22" for 10 minutes after 8:12 (the sheet's clock). */
    private val clock: (Long) -> String = { ms -> if (ms == 600_000L) "8:22" else "?" }

    @Test
    fun `riding - the bus, the stop and the sheet's time`() {
        assertEquals("On the 866, at Symonds St about 8:22 · Sleepers", ShareArrivalText.of(trip(), "866", clock, 0))
    }

    @Test
    fun `the time is the sheet's - now plus the time left it shows`() {
        val t = trip()
        val sheet = TripSheetText.of(t, clock, nowMs = 0)
        assertEquals(true, sheet.alarm?.contains("stop ~8:22"))
        assertEquals(true, ShareArrivalText.of(t, "866", clock, 0).contains("about 8:22"))
    }

    @Test
    fun `no bus number, a train, no time yet, waiting`() {
        assertEquals("On the bus, at Symonds St about 8:22 · Sleepers", ShareArrivalText.of(trip(), null, clock, 0))
        assertEquals("On the WEST train, at Symonds St about 8:22 · Sleepers", ShareArrivalText.of(trip(modes = listOf(Mode.TRAIN)), "WEST", clock, 0))
        assertEquals("On the 866 to Symonds St · Sleepers", ShareArrivalText.of(trip(eta = null), "866", clock, 0))
        assertEquals("Waiting for the 866 to Symonds St · Sleepers", ShareArrivalText.of(trip(Phase.WAITING), "866", clock, 0))
    }

    @Test
    fun `never a position or a link`() {
        val line = ShareArrivalText.of(trip(), "866", clock, 0)
        assertFalse(line.contains("-36.85") || line.contains("174.76") || line.contains("http"))
    }
}

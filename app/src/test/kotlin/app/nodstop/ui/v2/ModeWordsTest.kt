package app.nodstop.ui.v2

import app.nodstop.engine.AlarmReason
import app.nodstop.engine.LatLng
import app.nodstop.engine.Mode
import app.nodstop.engine.Phase
import app.nodstop.engine.Status
import app.nodstop.trip.TripService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** BACKLOG F29: the words say train or ferry wherever the trip knows its mode, and bus otherwise. */
class ModeWordsTest {
    private fun status(phase: Phase, leg: Int = 0) = Status(
        legIndex = leg, legCount = 2, legName = null, phase = phase, distanceM = 2_000.0, etaS = 300.0, vEstKmh = null,
        speedKmh = null, lastFixAgeS = 1.0, gpsOk = true, alarmAtMs = 1_000L, alarmReason = AlarmReason.WAKE,
        ringReason = null, onLearnedRoute = false, learnedRides = 0, startDistanceM = null,
    )

    private fun trip(phase: Phase, leg: Int = 0, modes: List<Mode> = listOf(Mode.TRAIN, Mode.FERRY)) = TripService.TripUi(
        "To work", listOf("Britomart", "Devonport"), listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.0)), leg, status(phase, leg),
        false, 0, legModes = modes,
    )

    private val clock: (Long) -> String = { "7:45" }

    @Test
    fun `on a train it's the train`() {
        assertEquals("I'm off this train", TripSheetText.of(trip(Phase.RIDING), clock).action)
        assertEquals("I'm off this train", CompactSheetText.of(trip(Phase.RIDING), clock).action)
        assertEquals("Britomart · waiting for the train", TripNoteText.of(trip(Phase.WAITING), clock).title)
        assertTrue(TripNoteText.actions(trip(Phase.RIDING), null).any { it.label == "I'm off this train" })
        assertEquals("Waiting for the train", TripService.phaseLabel(trip(Phase.WAITING)))
    }

    @Test
    fun `waiting for the ferry after the train - skip this ferry`() {
        val t = trip(Phase.WAITING, leg = 1)
        assertEquals("Skip this ferry", TripSheetText.of(t, clock).action)
        assertEquals("Waiting for ferry 2 · Devonport", TripNoteText.of(t, clock).title)
    }

    @Test
    fun `awake on the train, the next ride's own word`() {
        assertEquals("About 5 min away. Then ferry 2 to Devonport.", AwakeText.of(trip(Phase.AWAKE)).line)
        assertEquals("Stay on the train", AwakeText.of(trip(Phase.RIDING).let { it.copy(status = it.status.copy(ringReason = AlarmReason.FAILSAFE)) }).headline)
    }

    @Test
    fun `no mode known - still the bus, as before`() {
        assertEquals("I'm off this bus", TripSheetText.of(trip(Phase.RIDING, modes = emptyList()), clock).action)
        assertEquals("bus", trip(Phase.RIDING, modes = emptyList()).vehicle())
    }
}

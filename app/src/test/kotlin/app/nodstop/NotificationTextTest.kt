package app.nodstop

import app.nodstop.engine.AlarmReason
import org.junit.jupiter.api.Assertions.assertEquals
import app.nodstop.ui.v2.RingTextUi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PLAN U2: the words on the ring heads-up and the trip "island". */
class NotificationTextTest {
    @Test
    fun `the ring notification says what the ring screen says`() {
        assertEquals(RingTextUi("Wake up", "Harbour St is close"), Notifications.ringText("Harbour St", AlarmReason.WAKE, false))
        assertEquals(RingTextUi("Wake up", "Test ring"), Notifications.ringText(null, null, true))
        assertEquals("Urgent message", Notifications.ringText("Harbour St", AlarmReason.URGENT, false).title)
        assertTrue(Notifications.ringText("Harbour St", AlarmReason.OVERSHOOT, false).danger)
        for (r in AlarmReason.entries.filter { it != AlarmReason.WAKE }) {
            assertTrue(Notifications.ringText("Harbour St", r, false).line != "Harbour St is close", "$r says why")
        }
    }

    @Test
    fun `the island's action is Stop trip`() = assertEquals("Stop trip", Notifications.TRIP_STOP_LABEL)

    @Test
    fun `the trip notification says when it's a quiet trip (PLAN R2)`() {
        assertEquals(null, Notifications.quietLine(false, 6))
        assertEquals("Quiet trip", Notifications.quietLine(true, null))
        assertEquals("Quiet trip", Notifications.quietLine(true, 0))
        assertEquals("Quiet trip: 6 held", Notifications.quietLine(true, 6))
    }
}

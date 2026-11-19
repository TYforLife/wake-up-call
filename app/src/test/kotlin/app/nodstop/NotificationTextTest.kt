package app.nodstop

import app.nodstop.engine.AlarmReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PLAN U2: the words on the ring heads-up and the trip "island". */
class NotificationTextTest {
    @Test
    fun `ring titles say why`() {
        assertEquals("Wake up! Your stop is close", Notifications.ringTitle(AlarmReason.WAKE))
        assertEquals("You passed your stop!", Notifications.ringTitle(AlarmReason.OVERSHOOT))
        assertEquals("Urgent message", Notifications.ringTitle(AlarmReason.URGENT))
        assertEquals("Wake up! Your stop is close", Notifications.ringTitle(null))
    }

    @Test
    fun `every reason that needs explaining has a line, the normal wake doesn't`() {
        assertNull(Notifications.reasonText(AlarmReason.WAKE))
        for (r in AlarmReason.entries.filter { it != AlarmReason.WAKE }) assertTrue(Notifications.reasonText(r) != null, "$r")
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

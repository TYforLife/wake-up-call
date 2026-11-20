package app.nodstop.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlarmChannelTest {
    @Test
    fun `a channel made before Do Not Disturb access, or turned down, or silent to vibration, gets replaced`() {
        assertTrue(AlarmChannel.healthy(exists = true, importance = 4, canBypassDnd = true, dndAccess = true, vibrates = true))
        // No access yet: bypass can't be had, the rest must hold.
        assertTrue(AlarmChannel.healthy(exists = true, importance = 4, canBypassDnd = false, dndAccess = false, vibrates = true))
        // The owner's case: access granted after the channel was made, so it can't get through Do Not Disturb.
        assertFalse(AlarmChannel.healthy(exists = true, importance = 4, canBypassDnd = false, dndAccess = true, vibrates = true))
        assertFalse(AlarmChannel.healthy(exists = true, importance = 3, canBypassDnd = true, dndAccess = true, vibrates = true))
        assertFalse(AlarmChannel.healthy(exists = true, importance = 0, canBypassDnd = true, dndAccess = true, vibrates = true))
        // The pre-Run 10 channel didn't vibrate itself.
        assertFalse(AlarmChannel.healthy(exists = true, importance = 4, canBypassDnd = true, dndAccess = true, vibrates = false))
        assertFalse(AlarmChannel.healthy(exists = false, importance = 0, canBypassDnd = false, dndAccess = false, vibrates = false))
    }

    @Test
    fun `new IDs never repeat`() {
        assertEquals("alarm_2", AlarmChannel.nextId("alarm"))
        assertEquals("alarm_3", AlarmChannel.nextId("alarm_2"))
        assertEquals("alarm_10", AlarmChannel.nextId("alarm_9"))
    }
}

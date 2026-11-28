package app.nodstop.alarm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlarmScreenTest {
    @Test
    fun `the alarm screen opens when you're not using the phone, or Nodstop is in front - never over another app`() {
        assertTrue(AlarmScreen.opens(screenOn = false, locked = true, appInFront = false), "asleep: screen off")
        assertTrue(AlarmScreen.opens(screenOn = true, locked = true, appInFront = false), "on the lock screen")
        assertTrue(AlarmScreen.opens(screenOn = true, locked = false, appInFront = true), "Sleepers is open")
        assertFalse(AlarmScreen.opens(screenOn = true, locked = false, appInFront = false), "using another app: banner, buzz and sound only")
    }
}

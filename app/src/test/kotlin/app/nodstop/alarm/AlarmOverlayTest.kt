package app.nodstop.alarm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The owner's couch test: using another app, the ring was only a banner. Now it's drawn over that app. */
class AlarmOverlayTest {
    @Test
    fun `drawn over the app you're using - only then`() {
        assertTrue(AlarmOverlay.shouldShow(screenOn = true, locked = false, allowed = true, appInFront = false))
        // Locked or screen off: the full-screen alarm shows over the lock screen instead.
        assertFalse(AlarmOverlay.shouldShow(screenOn = false, locked = false, allowed = true, appInFront = false))
        assertFalse(AlarmOverlay.shouldShow(screenOn = true, locked = true, allowed = true, appInFront = false))
        // Without "Display over other apps": the banner, as before.
        assertFalse(AlarmOverlay.shouldShow(screenOn = true, locked = false, allowed = false, appInFront = false))
        // Nodstop open: its own screen already shows the ring.
        assertFalse(AlarmOverlay.shouldShow(screenOn = true, locked = false, allowed = true, appInFront = true))
    }
}

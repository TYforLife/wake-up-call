package app.nodstop.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** BACKLOG F3: the watch buzzes at the ring's start and every few seconds until you're awake. */
class WatchBuzzTest {
    @Test
    fun `at once, then every 6 s`() {
        assertTrue(WatchBuzz.due(0, null))
        assertFalse(WatchBuzz.due(500, 0))
        assertFalse(WatchBuzz.due(5_999, 0))
        assertTrue(WatchBuzz.due(6_000, 0))
    }

    @Test
    fun `the ring's 500 ms loop buzzes it about ten times a minute`() {
        var last: Long? = null
        var n = 0
        for (t in 0L until 60_000L step 500) if (WatchBuzz.due(t, last)) { last = t; n++ }
        assertEquals(10, n)
    }
}

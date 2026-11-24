package app.nodstop.ui.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** BACKLOG F30: "You slept 14 h on the bus this month". */
class SleepTextTest {
    private val min = 60_000L

    @Test
    fun `the month, the week and the last ride`() {
        assertEquals(
            SleepUi("You slept 14 h on the bus this month", "3 h 20 min this week · last ride 25 min"),
            SleepText.of(14 * 60 * min + 10 * min, 200 * min, 25 * min),
        )
    }

    @Test
    fun `nothing to say before any sleep has counted this month`() {
        assertNull(SleepText.of(0, 0, null))
        assertNull(SleepText.of(59_000, 59_000, 59_000))
    }

    @Test
    fun `a month with no ride this week yet`() {
        assertEquals("0 min this week", SleepText.of(90 * min, 0, null)?.line)
    }

    @Test
    fun `durations read like the rest of the app`() {
        assertEquals("0 min", SleepText.duration(30_000))
        assertEquals("25 min", SleepText.duration(25 * min))
        assertEquals("1 h", SleepText.duration(60 * min))
        assertEquals("2 h 10 min", SleepText.duration(130 * min))
        assertEquals("10 h", SleepText.duration(600 * min))
        assertEquals("15 h", SleepText.duration(14 * 60 * min + 30 * min))
    }
}

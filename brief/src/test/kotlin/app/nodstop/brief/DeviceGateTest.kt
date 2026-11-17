package app.nodstop.brief

import app.nodstop.brief.DeviceGate.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeviceGateTest {
    private val gb = 1024L * 1024 * 1024
    private val e2b = 3 * gb

    private fun check(battery: Int? = 80, thermal: Int? = 0, avail: Long? = 6 * gb, low: Boolean = false, model: Long = e2b, loaded: Boolean = false) =
        DeviceGate.check(battery, thermal, avail, low, model, loaded)

    @Test
    fun `a healthy phone runs the model`() = assertEquals(Verdict.OK, check())

    @Test
    fun `low battery stops it`() {
        assertEquals(Verdict.LOW_BATTERY, check(battery = 19))
        assertEquals(Verdict.OK, check(battery = 20))
        assertEquals(Verdict.OK, check(battery = null))
    }

    @Test
    fun `severe heat stops it, moderate doesn't`() {
        assertEquals(Verdict.OK, check(thermal = 2))
        assertEquals(Verdict.HOT, check(thermal = 3))
        assertEquals(Verdict.OK, check(thermal = null))
    }

    @Test
    fun `loading needs room for the model plus headroom, capped for big files`() {
        // Gemma 4 E2B (3 GB file): needs 2 GB + 512 MB free to load.
        assertEquals(Verdict.LOW_MEMORY, check(avail = 2 * gb + 511L * 1024 * 1024))
        assertEquals(Verdict.OK, check(avail = 2 * gb + 512L * 1024 * 1024))
        // Gemma 3 1B (0.5 GB file): needs 1 GB free.
        assertEquals(Verdict.OK, check(avail = gb, model = gb / 2))
        assertEquals(Verdict.LOW_MEMORY, check(avail = gb - 1, model = gb / 2))
    }

    @Test
    fun `once loaded only the system's low-memory flag stops it`() {
        assertEquals(Verdict.OK, check(avail = gb / 2, loaded = true))
        assertEquals(Verdict.LOW_MEMORY, check(low = true, loaded = true))
    }
}

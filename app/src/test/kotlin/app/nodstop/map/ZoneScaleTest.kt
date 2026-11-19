package app.nodstop.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ZoneScaleTest {
    @Test
    fun `ends are 50 m and 2 km`() {
        assertEquals(50.0, ZoneScale.toMeters(0f))
        assertEquals(2_000.0, ZoneScale.toMeters(1f))
    }

    @Test
    fun `most of the slider is the low range`() {
        assertTrue(ZoneScale.toMeters(0.5f) < 600, "half way is ${ZoneScale.toMeters(0.5f)} m")
        assertTrue(ZoneScale.toPos(1_000.0) > 0.65f, "1 km sits at ${ZoneScale.toPos(1_000.0)}")
    }

    @Test
    fun `positions and metres round-trip, rounded nicely`() {
        for (m in listOf(50.0, 150.0, 250.0, 400.0, 700.0, 1_500.0, 2_000.0)) assertEquals(m, ZoneScale.toMeters(ZoneScale.toPos(m)))
        assertEquals(330.0, ZoneScale.round(333.0))
        assertEquals(750.0, ZoneScale.round(760.0))
    }

    @Test
    fun `labels`() {
        assertEquals("250 m", ZoneScale.label(250.0))
        assertEquals("1.5 km", ZoneScale.label(1_500.0))
        assertEquals("2 km", ZoneScale.label(2_000.0))
    }
}

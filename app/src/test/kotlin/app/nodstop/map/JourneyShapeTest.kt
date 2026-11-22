package app.nodstop.map

import app.nodstop.data.Planner
import app.nodstop.data.Planner.Leg
import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JourneyShapeTest {
    private val pos = mapOf(1 to LatLng(-36.78, 174.75), 2 to LatLng(-36.80, 174.76), 3 to LatLng(-36.84, 174.76), 4 to LatLng(-36.85, 174.77))
    private val from = LatLng(-36.779, 174.749)
    private val to = LatLng(-36.851, 174.771)
    private val j = Planner.Journey(
        listOf(
            Leg.Walk(null, 1, 120.0, 96),
            Leg.Bus("866", "Newmarket", 1, 3, 100, 200, 2, path = listOf(1, 2, 3)),
            Leg.Walk(3, 4, 60.0, 120),
            Leg.Bus("70", "Botany", 4, 4, 300, 400, 0, path = emptyList()),
            Leg.Walk(4, null, 90.0, 72),
        ),
        0, 500,
    )

    @Test
    fun `a ride on the map - buses through every stop, walks dotted, on and off marked, the end pinned`() {
        val s = JourneyShape.of(j, from, to, { pos.getValue(it) }, { "Stop $it" })
        assertEquals(listOf(true, false, true, false, true), s.lines.map { it.walk })
        assertEquals(listOf(pos[1], pos[2], pos[3]), s.lines[1].points)
        assertEquals(listOf(from, pos[1]), s.lines[0].points)
        assertEquals(listOf(pos[4], to), s.lines[4].points)
        assertEquals(JourneyShape.Mark.Kind.START, s.marks.first().kind)
        assertEquals(JourneyShape.Mark.Kind.END, s.marks.last().kind)
        assertEquals(listOf("Stop 1", "Stop 3", "Stop 4"), s.marks.filter { it.kind == JourneyShape.Mark.Kind.STOP }.map { it.label })
        assertTrue(JourneyShape.linesJson(s).contains("\"walk\":true"))
        assertTrue(JourneyShape.marksJson(s).contains("\"kind\":\"end\""))
    }
}

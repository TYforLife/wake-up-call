package app.nodstop.data

import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** On the fixture feed with its timetable (`tools/testdata/gtfs`; CI checks the index matches it). */
class PlannerTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource("gtfs/$name")!!.readText()
    private val ix = TransitIndex.parse(res("stops.tsv"), res("routes.tsv"))
    private val tt = Timetable.parse(res("patterns.tsv"), res("profiles.tsv"), res("trips.tsv"), res("services.tsv"))
    private fun at(name: String) = ix.stops.first { it.name == name }.pos
    private fun name(i: Int) = ix.stops[i].name
    private fun hm(h: Int, m: Int) = h * 3600 + m * 60
    private val wednesday = LocalDate.of(2026, 11, 25)
    private val saturday = LocalDate.of(2026, 11, 28)
    private fun plan(from: String, to: String, date: LocalDate, departS: Int) = Planner.plan(ix, tt, at(from), at(to), date, departS)

    @Test
    fun `one bus, leaving now - this one, then the next departures, in order`() {
        val js = plan("Test Farm Stop D", "Test Queen St", wednesday, hm(6, 50))
        assertEquals(listOf(hm(7, 0) to hm(7, 20), hm(7, 30) to hm(7, 50)), js.map { it.leaveS to it.arriveS })
        assertEquals(listOf("866"), js[0].buses.map { it.route })
        assertEquals(listOf("Test Queen St"), js[0].getOffs.map(::name))
        assertEquals(20, js[0].minutes)
    }

    @Test
    fun `leaving later - only buses that haven't gone`() {
        val js = plan("Test Farm Stop D", "Test Queen St", wednesday, hm(7, 10))
        assertEquals(listOf(hm(7, 30)), js.map { it.leaveS })
    }

    @Test
    fun `two buses - change where they meet, with time to change, and real times`() {
        val j = plan("Test Farm Stop D", "Test Hill Ave", wednesday, hm(6, 50)).first()
        assertEquals(listOf("866", "70"), j.buses.map { it.route })
        assertEquals(listOf("Test Harbour Station A", "Test Hill Ave"), j.getOffs.map(::name))
        assertEquals(hm(7, 0) to hm(7, 45), j.leaveS to j.arriveS)
        assertEquals(hm(7, 30), j.buses[1].departS)
    }

    @Test
    fun `three buses, like From work - every stop you get off at, and waits included`() {
        val j = plan("Test Farm Stop D", "Test Domain Gate", wednesday, hm(6, 50)).first()
        assertEquals(3, j.buses.size)
        assertEquals("Test Domain Gate", name(j.getOffs.last()))
        assertEquals(hm(8, 30), j.arriveS)
        // Times only go forward, bus after bus.
        j.buses.zipWithNext().forEach { (a, b) -> assertTrue(b.departS >= a.arriveS) }
    }

    @Test
    fun `the calendar - no weekday buses on Christmas, and Saturday-only buses only on Saturdays`() {
        assertTrue(plan("Test Farm Stop D", "Test Queen St", LocalDate.of(2026, 12, 25), hm(6, 50)).isEmpty())
        val sat = plan("Test Harbour Station A", "Test Hill Ave", saturday, hm(9, 0))
        assertEquals(listOf(hm(10, 0) to hm(10, 15)), sat.map { it.leaveS to it.arriveS })
        assertTrue(plan("Test Harbour Station A", "Test Hill Ave", wednesday, hm(9, 0)).none { it.leaveS == hm(10, 0) })
    }

    @Test
    fun `after midnight - Friday's 24-30 bus is Saturday's 00-30`() {
        val j = plan("Test Harbour Station A", "Test Hill Ave", saturday, hm(0, 10)).first()
        assertEquals(hm(0, 30) to hm(0, 45), j.leaveS to j.arriveS)
    }

    @Test
    fun `nothing within walking distance - no rides`() {
        assertTrue(Planner.plan(ix, tt, LatLng(-36.70, 174.60), at("Test Hill Ave"), wednesday, hm(7, 0)).isEmpty())
    }
}

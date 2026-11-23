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

    @Test
    fun `arrive by - the rides that get there in time, the latest last`() {
        val js = Planner.arriveBy(ix, tt, at("Test Farm Stop D"), at("Test Queen St"), wednesday, hm(7, 55))
        assertEquals(hm(7, 30) to hm(7, 50), js.last().leaveS to js.last().arriveS)
        assertTrue(js.all { it.arriveS <= hm(7, 55) })
        val early = Planner.arriveBy(ix, tt, at("Test Farm Stop D"), at("Test Queen St"), wednesday, hm(7, 45))
        assertEquals(listOf(hm(7, 0)), early.map { it.leaveS })
    }

    @Test
    fun `walks - one at each end and one between buses, never two in a row, and the bus's every stop for the map`() {
        for ((a, b) in listOf("Test Farm Stop D" to "Test Hill Ave", "Test Farm Stop D" to "Test Domain Gate", "Test Farm Stop D" to "Test Queen St")) {
            for (j in plan(a, b, wednesday, hm(6, 50))) {
                j.legs.zipWithNext().forEach { (x, y) -> assertTrue(!(x is Planner.Leg.Walk && y is Planner.Leg.Walk), "two walks in a row: $j") }
                j.buses.forEach { bus -> assertEquals(bus.stops + 1, bus.path.size); assertEquals(bus.board, bus.path.first()); assertEquals(bus.alight, bus.path.last()) }
            }
        }
    }

    private fun ride(leave: Int, arrive: Int, buses: Int, walkS: Int = 0): Planner.Journey {
        val legs = ArrayList<Planner.Leg>()
        if (walkS > 0) legs += Planner.Leg.Walk(null, 0, walkS * 1.25, walkS)
        repeat(buses) { legs += Planner.Leg.Bus("R$it", "", 0, 1, leave + walkS + it, arrive - 1, 3) }
        return Planner.Journey(legs, leave, arrive)
    }

    @Test
    fun `the list, like Google's - a change must save time, and leaving a minute later doesn't excuse a worse ride`() {
        val direct = ride(hm(8, 3), hm(8, 31), 1)
        // Two buses, a minute later and a minute sooner: not worth the change.
        val hop = ride(hm(8, 4), hm(8, 30), 2)
        // Two buses that save 10 minutes: kept.
        val fast = ride(hm(8, 3), hm(8, 21), 2)
        // The next bus, leaving much later: kept.
        val later = ride(hm(8, 33), hm(9, 1), 1)
        assertEquals(listOf(fast, later), Planner.best(listOf(direct, hop, fast, later)))
        assertTrue(hop !in Planner.best(listOf(direct, hop)))
        assertTrue(direct in Planner.best(listOf(direct, hop)))
    }

    @Test
    fun `footpaths - the walks at each end are the walking distance and the way it goes, when it's known`() {
        val from = at("Test Farm Stop D")
        val to = at("Test Queen St")
        val bend = LatLng(-36.85, 174.763)
        val far = Planner.Walks { p -> Planner.Path(700.0, listOf(from, p)) }
        val toEnd = Planner.Walks { p -> Planner.Path(700.0, listOf(to, bend, p)) }
        val j = Planner.plan(ix, tt, from, to, wednesday, hm(6, 50), walkFrom = far, walkTo = toEnd).first()
        val walks = j.legs.filterIsInstance<Planner.Leg.Walk>()
        assertEquals(listOf(700.0, 700.0), walks.map { it.meters })
        assertEquals(560, walks.first().seconds)
        assertTrue(walks.none { it.estimated })
        // The walk from the last stop goes the other way round: from the stop, by the bend, to where you're going.
        assertEquals(listOf(ix.stops[j.getOffs.last()].pos, bend, to), walks.last().points)
        // Too far along the paths: that stop isn't walked to at all.
        assertTrue(Planner.plan(ix, tt, from, to, wednesday, hm(6, 50), walkTo = Planner.Walks { Planner.Path(5_000.0, emptyList()) }).isEmpty())
    }

    @Test
    fun `footpaths that can't reach a stop - it isn't walked to, never the straight line instead`() {
        val from = at("Test Farm Stop D")
        val to = at("Test Queen St")
        assertTrue(Planner.plan(ix, tt, from, to, wednesday, hm(6, 50), walkTo = Planner.Walks { null }).isEmpty())
        // Without footpaths at all: the straight line × 1.25, marked as an estimate.
        val j = Planner.plan(ix, tt, LatLng(-36.7805, 174.7505), to, wednesday, hm(6, 50)).first()
        assertTrue(j.legs.filterIsInstance<Planner.Leg.Walk>().all { it.estimated && it.points.isEmpty() })
    }

    @Test
    fun `changing buses on foot - along the footpaths, and a change the real walk can't make isn't offered`() {
        val from = at("Test Farm Stop D")
        val to = at("Test Ponsonby Rd")
        val queen = ix.stops.indexOfFirst { it.name == "Test Queen St" }
        // Straight: 145 m × 1.25, 2.5 minutes: the 7:23 from Wellesley St is made.
        val est = Planner.plan(ix, tt, from, to, wednesday, hm(6, 50)).first()
        assertEquals(listOf("866", "77"), est.buses.map { it.route })
        assertEquals(hm(7, 35), est.arriveS)
        assertEquals(listOf(queen), Planner.estimatedChanges(est))
        // Along the footpaths it's 400 m round the block, 5 min 20 s: the 7:23 has gone, so it's the 7:53.
        val round = Planner.Walks { p -> Planner.Path(400.0, listOf(ix.stops[queen].pos, LatLng(-36.848, 174.764), p)) }
        val real = Planner.plan(ix, tt, from, to, wednesday, hm(6, 50), changeWalks = { if (it == queen) round else null })
        assertTrue(real.none { it.arriveS == hm(7, 35) }, "a change that the real walk misses: ${real.map { it.arriveS }}")
        val j = real.first()
        assertEquals(hm(8, 5), j.arriveS)
        val change = j.legs.filterIsInstance<Planner.Leg.Walk>().single { it.from != null && it.to != null }
        assertEquals(400.0, change.meters)
        assertEquals(3, change.points.size)
        assertTrue(Planner.estimatedChanges(j).isEmpty())
        // No way there along the footpaths: no change on foot at all.
        assertTrue(Planner.plan(ix, tt, from, to, wednesday, hm(6, 50), changeWalks = { Planner.Walks { null } }).isEmpty())
    }

    @Test
    fun `a walk's way from the foot router fills in only walks whose length is known but not their way`() {
        val known = Planner.Leg.Walk(null, 1, 300.0, 240)
        val drawn = Planner.Leg.Walk(1, 2, 100.0, 80, points = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.001)))
        val estimate = Planner.Leg.Walk(2, null, 100.0, 80, estimated = true)
        val j = Planner.Journey(listOf(known, drawn, estimate), 0, 600)
        val way = listOf(LatLng(1.0, 1.0), LatLng(1.0, 1.001))
        val out = Planner.withWays(j) { way }.legs.filterIsInstance<Planner.Leg.Walk>()
        assertEquals(listOf(way, drawn.points, emptyList()), out.map { it.points })
    }

    @Test
    fun `the coming bus while you wait - its next departures from your stop, by the calendar`() {
        assertEquals(listOf(hm(7, 0), hm(7, 30)), Departures.next(ix, tt, at("Test Farm Stop D"), "866", wednesday, hm(6, 50)))
        assertEquals(listOf(hm(7, 30)), Departures.next(ix, tt, at("Test Farm Stop D"), "866", wednesday, hm(7, 1)))
        assertTrue(Departures.next(ix, tt, at("Test Farm Stop D"), "866", LocalDate.of(2026, 12, 25), hm(6, 50)).isEmpty(), "Christmas")
        assertTrue(Departures.next(ix, tt, at("Test Farm Stop D"), "70", wednesday, hm(6, 50)).isEmpty(), "not this stop's bus")
    }
}

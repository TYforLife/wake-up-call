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
    private val tt = Timetable.parse(res("patterns.tsv"), res("profiles.tsv"), res("trips.tsv"), res("services.tsv"), res("shapes.tsv"))
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
    fun `footpaths - the walks at each end are the walking distance when it's known`() {
        val far = Planner.Walks { 700.0 }
        val j = Planner.plan(ix, tt, at("Test Farm Stop D"), at("Test Queen St"), wednesday, hm(6, 50), walkFrom = far, walkTo = far).first()
        val walks = j.legs.filterIsInstance<Planner.Leg.Walk>()
        assertEquals(listOf(700.0, 700.0), walks.map { it.meters })
        assertEquals(560, walks.first().seconds)
        // Too far along the paths: that stop isn't walked to at all.
        assertTrue(Planner.plan(ix, tt, at("Test Farm Stop D"), at("Test Queen St"), wednesday, hm(6, 50), walkTo = Planner.Walks { 5_000.0 }).isEmpty())
    }

    @Test
    fun `the coming bus while you wait - its next departures from your stop, by the calendar`() {
        assertEquals(listOf(hm(7, 0), hm(7, 30)), Departures.next(ix, tt, at("Test Farm Stop D"), "866", wednesday, hm(6, 50)))
        assertEquals(listOf(hm(7, 30)), Departures.next(ix, tt, at("Test Farm Stop D"), "866", wednesday, hm(7, 1)))
        assertTrue(Departures.next(ix, tt, at("Test Farm Stop D"), "866", LocalDate.of(2026, 12, 25), hm(6, 50)).isEmpty(), "Christmas")
        assertTrue(Departures.next(ix, tt, at("Test Farm Stop D"), "70", wednesday, hm(6, 50)).isEmpty(), "not this stop's bus")
    }

    @Test
    fun `a leg's bus path and its timetabled ride, for the trip map and the journey list`() {
        val path = Departures.path(ix, tt, "866", at("Test Farm Stop D"), at("Test Queen St"))!!
        assertEquals(at("Test Farm Stop D"), path.first())
        assertEquals(at("Test Queen St"), path.last())
        assertEquals(hm(7, 0) to hm(7, 20), Departures.ride(ix, tt, "866", at("Test Farm Stop D"), at("Test Queen St"), wednesday, hm(6, 50)))
        assertEquals(hm(7, 30) to hm(7, 50), Departures.ride(ix, tt, "866", at("Test Farm Stop D"), at("Test Queen St"), wednesday, hm(7, 2)))
        assertEquals(null, Departures.path(ix, tt, "866", at("Test Queen St"), at("Test Farm Stop D")), "not that way")
    }

    @Test
    fun `the bus's ETA from the timetable - from where you are on its line, the run due there now`() {
        val farm = at("Test Farm Stop D"); val queen = at("Test Queen St")
        fun rem(here: LatLng, nowS: Int) = Departures.remainingS(ix, tt, "866", farm, queen, here, wednesday, nowS)
        val half = LatLng((farm.lat + queen.lat) / 2, (farm.lng + queen.lng) / 2)
        assertEquals(1_200.0, rem(farm, hm(7, 0))!!, 1.0, "at the stop you got on: the whole 20 min")
        assertEquals(600.0, rem(half, hm(7, 12))!!, 1.0, "halfway, 2 min late: still 10 min to go")
        assertEquals(0.0, rem(queen, hm(7, 20))!!, 1.0)
        assertEquals(600.0, rem(half, hm(7, 40))!!, 1.0, "the 7:30 run")
        assertEquals(null, rem(LatLng(-36.70, 174.60), hm(7, 10)), "not on the 866's line")
        assertEquals(null, rem(half, hm(12, 0)), "no run anywhere near now")
    }

    @Test
    fun `the bus's road - decoded from the feed's shape, not stop to stop`() {
        // Google's own example polyline.
        assertEquals(
            listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453)),
            Timetable.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@"),
        )
        // The fixture 866 bends west between Test Farm Stop D and Test Queen St.
        val road = Departures.path(ix, tt, "866", at("Test Farm Stop D"), at("Test Queen St"))!!
        assertTrue(road.size > 2, "more than the two stops: $road")
        assertTrue(road.any { it.lng < 174.745 }, "it follows the road west, not the straight line")
        assertEquals(at("Test Farm Stop D").lat, road.first().lat, 1e-4)
        assertEquals(at("Test Queen St").lat, road.last().lat, 1e-4)
        // The planner's bus leg carries the same road, for the ride's map.
        val bus = plan("Test Farm Stop D", "Test Queen St", wednesday, hm(6, 50)).first().buses.first()
        assertEquals(road, bus.road)
        // A pattern without a shape: stop to stop, as before.
        assertTrue(Departures.path(ix, tt, "70", at("Test Harbour Station A"), at("Test Hill Ave"))!!.size == 2)
    }

    @Test
    fun `the first and last walks carry their footpaths, from where you start and to where you're going`() {
        val from = LatLng(-36.7790, 174.7490)
        val to = LatLng(-36.8495, 174.7630)
        val bend = LatLng(-36.7795, 174.7500)
        val paths = object : Planner.Walks {
            override fun to(p: LatLng) = 150.0
            override fun path(p: LatLng) = listOf(bend, p)
        }
        val j = Planner.plan(ix, tt, from, to, wednesday, hm(6, 50), walkFrom = paths, walkTo = paths).first()
        val first = j.legs.first() as Planner.Leg.Walk
        val last = j.legs.last() as Planner.Leg.Walk
        assertEquals(from, first.path.first())
        assertEquals(bend, first.path[1])
        assertEquals(to, last.path.last())
        assertEquals(bend, last.path[last.path.size - 2], "the way from the destination's search, turned round")
    }
}

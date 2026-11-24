package app.nodstop.data

import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * The planner on Auckland Transport's real timetable (PLAN Run 13, T2). CI builds the index before the tests; a
 * local build without it skips this. Public places only, a weekday at 8 am.
 */
class RealTimetableTest {
    private val dir = File("src/main/assets/gtfs")
    private fun f(n: String) = File(dir, n).readText()

    @Test
    fun `three public trips on the real timetable - rides under 2 hours, times only going forward`() {
        assumeTrue(File(dir, "patterns.tsv").exists(), "no AT index in this build")
        val t0 = System.nanoTime()
        val ix = TransitIndex.parse(f("stops.tsv"), f("routes.tsv"))
        val tt = Timetable.parse(f("patterns.tsv"), f("profiles.tsv"), f("trips.tsv"), f("services.tsv"), f("shapes.tsv"))
        // The buses' roads (owner, 2026-11-24: "just a straight line between stations").
        val shaped = tt.shapes.count { it != null }
        println("shapes: $shaped of ${tt.patterns.size} patterns, ${tt.shapes.sumOf { it?.points?.size ?: 0 }} points, ${File(dir, "shapes.tsv").length() / 1024} KB")
        assertTrue(shaped >= tt.patterns.size * 0.9, "most patterns have their road: $shaped of ${tt.patterns.size}")
        println("real index: ${ix.stops.size} stops, ${tt.patterns.size} timetable patterns, loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
        val day = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY))
        val britomart = LatLng(-36.8443, 174.7678)
        val museum = LatLng(-36.8604, 174.7778)
        val smales = LatLng(-36.7790, 174.7470)
        val takapuna = LatLng(-36.7870, 174.7700)
        val westlake = LatLng(-36.7785, 174.7501)
        val hospital = LatLng(-36.8601, 174.7697)
        for ((name, from, to) in listOf(
            Triple("Britomart to Smales Farm", britomart, smales),
            Triple("Auckland Museum to Britomart", museum, britomart),
            Triple("Takapuna to Britomart", takapuna, britomart),
            Triple("Westlake Boys High School to Auckland City Hospital", westlake, hospital),
        )) {
            val s = System.nanoTime()
            val js = Planner.plan(ix, tt, from, to, day, 8 * 3600)
            val ms = (System.nanoTime() - s) / 1_000_000
            println("$name on $day: ${js.size} rides in $ms ms")
            if (name.startsWith("Westlake")) assertTrue(js.any { j -> j.buses.any { it.route == "866" } }, "$name: the 866 (Smales Farm to the hospital)")
            // Every bus leg follows its road: more points than stops, and the road ends where you get on and off.
            for (b in js.flatMap { it.buses }) {
                assertTrue(b.road.size > b.path.size, "$name: ${b.route} has no road (${b.road.size} points for ${b.path.size} stops)")
                assertTrue(app.nodstop.engine.Geo.distanceM(b.road.first(), ix.stops[b.board].pos) < 150.0, "$name: ${b.route}'s road starts at its stop")
                assertTrue(app.nodstop.engine.Geo.distanceM(b.road.last(), ix.stops[b.alight].pos) < 150.0, "$name: ${b.route}'s road ends at its stop")
            }
            js.forEach { j ->
                println("  ${j.leaveS / 3600}:${"%02d".format(j.leaveS % 3600 / 60)} -> ${j.arriveS / 3600}:${"%02d".format(j.arriveS % 3600 / 60)} " +
                    "(${j.minutes} min) ${j.buses.joinToString(" > ") { it.route }}")
            }
            assertTrue(js.isNotEmpty(), "$name: no ride")
            // A slow phone is several times slower than CI: keep each search under 1.5 s here.
            assertTrue(ms < 1500, "$name: $ms ms")
            assertTrue(js.first().minutes < 120, "$name: ${js.first().minutes} min")
            for (j in js) {
                assertTrue(j.leaveS >= 8 * 3600 - 60 && j.arriveS > j.leaveS, "$name: times")
                // One walk at a time (owner's feedback: a far stop, then a 13-minute walk).
                j.legs.zipWithNext().forEach { (a, b) -> assertTrue(!(a is Planner.Leg.Walk && b is Planner.Leg.Walk), "$name: two walks in a row") }
                assertTrue((j.legs.last() as Planner.Leg.Walk).meters <= Planner.ACCESS_M * Planner.DETOUR + 1, "$name: the last walk")
                j.buses.zipWithNext().forEach { (a, b) -> assertTrue(b.departS >= a.arriveS, "$name: change goes back in time") }
            }
        }
        val s = System.nanoTime()
        val by = Planner.arriveBy(ix, tt, westlake, hospital, day, 9 * 3600)
        println("Westlake to the hospital, arrive by 9:00: ${by.size} rides in ${(System.nanoTime() - s) / 1_000_000} ms, the last leaving " +
            "${by.lastOrNull()?.let { "${it.leaveS / 3600}:${"%02d".format(it.leaveS % 3600 / 60)} arriving ${it.arriveS / 3600}:${"%02d".format(it.arriveS % 3600 / 60)}" }}")
        assertTrue(by.isNotEmpty() && by.all { it.arriveS <= 9 * 3600 }, "arrive by")
        assertTrue(by.last().arriveS >= 8 * 3600 + 30 * 60, "arrive by: the last ride gets there late enough to be the last")
    }
}

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
        val tt = Timetable.parse(f("patterns.tsv"), f("profiles.tsv"), f("trips.tsv"), f("services.tsv"))
        println("real index: ${ix.stops.size} stops, ${tt.patterns.size} timetable patterns, loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
        val day = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY))
        val britomart = LatLng(-36.8443, 174.7678)
        val museum = LatLng(-36.8604, 174.7778)
        val smales = LatLng(-36.7790, 174.7470)
        val takapuna = LatLng(-36.7870, 174.7700)
        for ((name, from, to) in listOf(
            Triple("Britomart to Smales Farm", britomart, smales),
            Triple("Auckland Museum to Britomart", museum, britomart),
            Triple("Takapuna to Britomart", takapuna, britomart),
        )) {
            val s = System.nanoTime()
            val js = Planner.plan(ix, tt, from, to, day, 8 * 3600)
            val ms = (System.nanoTime() - s) / 1_000_000
            println("$name on $day: ${js.size} rides in $ms ms")
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
                j.buses.zipWithNext().forEach { (a, b) -> assertTrue(b.departS >= a.arriveS, "$name: change goes back in time") }
            }
        }
    }
}

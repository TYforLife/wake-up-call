package app.nodstop.data

import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Walking on real OpenStreetMap footpaths (BACKLOG 1). CI fetches the paths around Auckland City Hospital (a public
 * place) and the AT index before the tests; a local build without them skips this.
 */
class RealWalkTest {
    private val paths = File("build/footpaths/hospital.json")
    private val gtfs = File("src/main/assets/gtfs")
    private val hospital = LatLng(-36.8601, 174.7697)

    @Test
    fun `walks to the stops near the hospital follow the paths - never shorter than the straight line`() {
        assumeTrue(paths.exists() && paths.length() > 1_000 && File(gtfs, "stops.tsv").exists(), "no footpaths or AT index in this build")
        val t0 = System.nanoTime()
        val g = WalkGraph.parse(paths.readText())
        val w = g.from(hospital)
        println("footpaths: ${g.size} points, ${paths.length() / 1024} KB, built and walked in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue(w != null, "the hospital is near a path")
        val ix = TransitIndex.parse(File(gtfs, "stops.tsv").readText(), File(gtfs, "routes.tsv").readText())
        val near = ix.near(hospital, Planner.ACCESS_M).sortedBy { it.second }.take(8)
        var known = 0
        for ((s, straight) in near) {
            val m = w!!.to(ix.stops[s].pos)
            println("  ${ix.stops[s].name}: straight ${straight.toInt()} m, walking ${m?.toInt()} m")
            if (m != null) {
                known++
                assertTrue(m >= straight * 0.95, "${ix.stops[s].name}: walking $m m is shorter than the straight line $straight m")
                assertTrue(m <= straight * 3 + 150, "${ix.stops[s].name}: walking $m m is far too long for $straight m")
            }
        }
        assertTrue(known >= near.size / 2, "most nearby stops are on the paths ($known of ${near.size})")

        val tt = Timetable.parse(
            File(gtfs, "patterns.tsv").readText(), File(gtfs, "profiles.tsv").readText(),
            File(gtfs, "trips.tsv").readText(), File(gtfs, "services.tsv").readText(),
        )
        val day = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY))
        val js = Planner.plan(ix, tt, LatLng(-36.7785, 174.7501), hospital, day, 8 * 3600, walkTo = w)
        assertTrue(js.isNotEmpty(), "rides to the hospital")
        for (j in js) {
            val last = j.legs.last() as Planner.Leg.Walk
            assertEquals(w!!.to(ix.stops[j.getOffs.last()].pos)!!, last.meters, 1.0, "the last walk is the walking distance")
            println("  ${j.buses.joinToString(" > ") { it.route }}: off at ${ix.stops[j.getOffs.last()].name}, walk ${last.meters.toInt()} m")
        }
        assertTrue(Geo.distanceM(hospital, ix.stops[js.first().getOffs.last()].pos) < Planner.ACCESS_M)
    }
}

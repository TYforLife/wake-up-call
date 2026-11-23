package app.nodstop.data

import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Walking on real OpenStreetMap footpaths (BACKLOG 1 and 27). CI fetches the paths around two public places (Auckland
 * City Hospital and Britomart), a foot router's walks from the hospital to the stops near it, and the AT index, before
 * the tests. In CI a missing fetch fails this test; only a local build without them skips it.
 */
class RealWalkTest {
    companion object {
        val HOSPITAL = LatLng(-36.8601, 174.7697)
        val BRITOMART = LatLng(-36.8442, 174.7676)
    }

    private val dir = File("build/footpaths")
    private val gtfs = File("src/main/assets/gtfs")
    private val inCi = System.getenv("CI") == "true"

    private fun need(f: File, what: String) {
        if (inCi && !f.exists()) fail<Unit>("CI didn't fetch $what (${f.path}): see the footpaths step")
        assumeTrue(f.exists(), "no $what in this build")
    }

    private fun index(): TransitIndex {
        assumeTrue(File(gtfs, "stops.tsv").exists(), "no AT index in this build")
        return TransitIndex.parse(File(gtfs, "stops.tsv").readText(), File(gtfs, "routes.tsv").readText())
    }

    private fun walks(name: String, from: LatLng): Planner.Walks {
        val f = File(dir, "$name.json")
        need(f, "the footpaths around $name")
        val t0 = System.nanoTime()
        val g = WalkGraph.parse(f.readText())
        val t1 = System.nanoTime()
        val w = g.from(from)
        val t2 = System.nanoTime()
        println("footpaths ($name): ${g.size} points, ${f.length() / 1024} KB, built in ${(t1 - t0) / 1_000_000} ms, walked in ${(t2 - t1) / 1_000_000} ms")
        assertTrue(w != null, "$name is near a street")
        return w ?: error("unreachable")
    }

    private fun length(ps: List<LatLng>) = ps.zipWithNext().sumOf { (a, b) -> Geo.distanceM(a, b) }

    @Test
    fun `walks to the stops near the hospital follow the paths - drawn as long as they measure, never shorter than straight`() {
        val ix = index()
        val w = walks("hospital", HOSPITAL)
        val near = ix.near(HOSPITAL, Planner.ACCESS_M).sortedBy { it.second }.take(12)
        var known = 0
        for ((s, straight) in near) {
            val m = w.to(ix.stops[s].pos)
            println("  ${ix.stops[s].name}: straight ${straight.toInt()} m, walking ${m?.meters?.toInt()} m, ${m?.points?.size} points")
            if (m != null) {
                known++
                assertTrue(m.meters >= straight * 0.95, "${ix.stops[s].name}: walking ${m.meters} m is shorter than the straight line $straight m")
                assertTrue(m.meters <= straight * 3 + 150, "${ix.stops[s].name}: walking ${m.meters} m is far too long for $straight m")
                assertEquals(m.meters, length(m.points), m.meters * 0.01, "${ix.stops[s].name}: the drawn way is the walk")
                assertEquals(HOSPITAL, m.points.first())
                assertEquals(ix.stops[s].pos, m.points.last())
            }
        }
        assertTrue(known >= near.size * 3 / 4, "most nearby stops are walkable ($known of ${near.size})")
    }

    @Test
    fun `the walks agree with a second router's - FOSSGIS's OSRM foot profile, on the same map`() {
        index()
        val w = walks("hospital", HOSPITAL)
        val reply = File(dir, "router.json")
        need(reply, "the foot router's walks")
        val stops = File(dir, "router-stops.tsv").readLines().filter { it.isNotBlank() }.map { l ->
            val f = l.split('\t')
            f[1] to LatLng(f[2].toDouble(), f[3].toDouble())
        }
        val router = FootRouter.parseTable(reply.readText(), stops.map { it.second })
        assertTrue(router != null, "the router's reply reads")
        var compared = 0
        val off = ArrayList<String>()
        // Each walk that disagrees, with its way, for CI's next step to show the map's tags along it.
        val disagree = StringBuilder()
        for ((name, p) in stops) {
            val ours = w.to(p)
            val theirs = router?.to(p)?.meters
            println("  $name: ours ${ours?.meters?.toInt()} m, router ${theirs?.toInt()} m")
            if (ours == null || theirs == null) continue
            compared++
            if (Math.abs(ours.meters - theirs) > theirs * 0.15) {
                off += "$name: ours ${ours.meters.toInt()} m, router ${theirs.toInt()} m"
                disagree.append("$name\t${p.lat}\t${p.lng}\t${ours.points.joinToString(";") { "${it.lat},${it.lng}" }}\n")
            }
        }
        File(dir, "disagree.tsv").writeText(disagree.toString())
        assertTrue(compared >= stops.size * 3 / 4, "most stops compared ($compared of ${stops.size})")
        assertTrue(off.isEmpty(), "more than 15% from the router's walk:\n${off.joinToString("\n")}")
    }

    @Test
    fun `a well-known trip - Britomart to the hospital - walks along the paths at both ends`() {
        val ix = index()
        val from = walks("britomart", BRITOMART)
        val to = walks("hospital", HOSPITAL)
        val tt = Timetable.parse(
            File(gtfs, "patterns.tsv").readText(), File(gtfs, "profiles.tsv").readText(),
            File(gtfs, "trips.tsv").readText(), File(gtfs, "services.tsv").readText(),
        )
        val day = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY))
        val js = Planner.plan(ix, tt, BRITOMART, HOSPITAL, day, 8 * 3600, walkFrom = from, walkTo = to)
        assertTrue(js.isNotEmpty(), "rides from Britomart to the hospital")
        for (j in js) {
            val walks = j.legs.filterIsInstance<Planner.Leg.Walk>()
            println(
                "  ${j.buses.joinToString(" > ") { it.route }}: on at ${ix.stops[j.buses.first().board].name}, off at ${ix.stops[j.getOffs.last()].name}; " +
                    walks.joinToString(", ") { "walk ${it.meters.toInt()} m (${it.points.size} points${if (it.estimated) ", estimated" else ""})" },
            )
            val ends = walks.filter { it.from == null || it.to == null }
            assertTrue(ends.none { it.estimated }, "the walks at each end follow the footpaths")
            val last = walks.last()
            assertEquals(to.to(ix.stops[j.getOffs.last()].pos)?.meters ?: -1.0, last.meters, 1.0, "the last walk is the walking distance")
            assertEquals(HOSPITAL, last.points.last(), "the last walk ends where you're going")
        }
        assertTrue(Geo.distanceM(HOSPITAL, ix.stops[js.first().getOffs.last()].pos) < Planner.ACCESS_M)
    }
}

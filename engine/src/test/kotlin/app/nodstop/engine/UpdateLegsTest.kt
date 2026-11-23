package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** PLAN U3: a wake circle changed during the trip applies at once, and can only make the alarm earlier. */
class UpdateLegsTest {
    private val cfg = Config()
    private val dest = Plane.origin
    private fun kmh(v: Double) = v / 3.6

    private fun trip(extra: List<Input> = emptyList()): Pair<Sim, List<Input>> {
        val (path, destAlong) = Path.straight(6_000.0)
        val sim = Sim(path, destAlong) { kmh(40.0) }.run()
        return sim to (FixPlan().inputs(sim, T0, listOf(Trips.leg(dest)), Random(5)) + extra).sortedBy { it.tMs }
    }

    @Test
    fun `a circle you're already inside rings now`() {
        val (sim, _) = trip()
        val at = TestRoute.ms(T0, sim.timeWhen(sim.destAlongM - 1_000))
        val (_, inputs) = trip(listOf(Input.UpdateLegs(at + 1, listOf(Trips.leg(dest).copy(wakeRadiusM = 1_200.0)))))
        val r = Harness(cfg).run(inputs)
        assertEquals(SystemAlarm.firesAt(at + 1, at + 1), r.rings.first(), "now, as Android rings it: 5 s later")
        assertEquals(AlarmReason.WAKE, r.ringReasons.first())
    }

    @Test
    fun `a bigger circle further out rings when you reach it, earlier than without`() {
        val (sim, plain) = trip()
        val baseline = Harness(cfg).run(plain).rings.first()
        val (_, inputs) = trip(listOf(Input.UpdateLegs(T0 + 30_000, listOf(Trips.leg(dest).copy(wakeRadiusM = 1_500.0)))))
        val ring = Harness(cfg).run(inputs).rings.first()
        assertTrue(ring < baseline, "ring ${ring - T0} vs ${baseline - T0}")
        val reach = TestRoute.ms(T0, sim.timeWhen(sim.destAlongM - 1_500))
        assertTrue(ring - reach in -1_000L..5_000L, "rang ${(ring - reach) / 1000.0}s after entering the circle")
    }

    @Test
    fun `a leg whose stop changed is left alone`() {
        val (_, plain) = trip()
        val e = Engine(cfg)
        var s = EngineState()
        for (i in plain.take(40)) s = e.step(s, i).first
        val moved = Trips.leg(Plane.offset(dest, 500.0, 0.0)).copy(wakeRadiusM = 5_000.0)
        val after = e.step(s, Input.UpdateLegs(plain[40].tMs, listOf(moved))).first
        assertEquals(s.legs, after.legs)
        assertEquals(s.alarmAtMs, after.alarmAtMs)
    }

    @Test
    fun `legs survive the trace format`() {
        val legs = listOf(Trips.leg(dest).copy(wakeRadiusM = 300.0))
        val e = Trace.inputEvent(Input.UpdateLegs(9, legs))
        assertEquals("legs", e.k)
        assertEquals(Input.UpdateLegs(9, legs), Trace.toInput(Trace.decode(Trace.encode(e))))
    }

    /**
     * PLAN Run 11, B4: the circle is now dragged and tapped on the map, so it shrinks mid-trip often. A circle only
     * ever adds an earlier ring (DESIGN §2.6): shrinking yours, even to the smallest, never rings later than having
     * no circle at all.
     */
    @Test
    fun `shrinking your circle mid-trip never rings later than no circle, over 200 random trips`() {
        var compared = 0
        for (seed in 1..200) {
            val rnd = Random(seed)
            val (path, destAlong) = Path.random(rnd, rnd.nextDouble(2_000.0, 12_000.0))
            val d = path.at(destAlong)
            val v = rnd.nextDouble(3.0, cfg.vMax)
            val sim = Sim(path, destAlong) { v }.run()
            val none = Harness(cfg).run(FixPlan(intervalS = 1.0..3.0, noiseM = 4.0).inputs(sim, T0, listOf(Trips.leg(d)), Random(seed)))
            val baseline = none.rings.firstOrNull() ?: continue
            val big = listOf(Trips.leg(d).copy(wakeRadiusM = rnd.nextDouble(600.0, 2_000.0)))
            val withBig = FixPlan(intervalS = 1.0..3.0, noiseM = 4.0).inputs(sim, T0, big, Random(seed))
            val shrinkAt = T0 + rnd.nextLong(0, baseline - T0)
            val small = Input.UpdateLegs(shrinkAt, listOf(Trips.leg(d).copy(wakeRadiusM = ZoneScaleMin.pick(rnd))))
            val ring = Harness(cfg).run((withBig + small).sortedBy { it.tMs }).rings.firstOrNull()
            assertTrue(ring != null && ring <= baseline, "seed $seed: shrunk circle rang at ${ring?.minus(T0)}, no circle at ${baseline - T0}")
            compared++
        }
        assertTrue(compared > 150, "only $compared trips compared")
    }

    /** The smallest circles the map allows (50 m) up to a modest one. */
    private object ZoneScaleMin {
        fun pick(rnd: Random) = if (rnd.nextBoolean()) 50.0 else rnd.nextDouble(50.0, 300.0)
    }

    /** Never later: random trips, random circle edits; until either run rings, the edited one is never later. */
    @Test
    fun `circle edits never make the alarm later over 500 random trips`() {
        var compared = 0L
        for (seed in 1..500) {
            val rnd = Random(seed)
            val (path, destAlong) = Path.random(rnd, rnd.nextDouble(2_000.0, 12_000.0))
            val d = path.at(destAlong)
            val v = rnd.nextDouble(3.0, cfg.vMax)
            val sim = Sim(path, destAlong) { v }.run()
            val legs = listOf(Trips.leg(d))
            val plain = FixPlan(intervalS = 1.0..3.0, noiseM = 4.0).inputs(sim, T0, legs, Random(seed))
            val base = Harness(cfg).run(plain)
            val firstRing = base.rings.firstOrNull() ?: continue
            val edits = List(rnd.nextInt(1, 4)) {
                Input.UpdateLegs(T0 + rnd.nextLong(0, firstRing - T0), listOf(Trips.leg(d).copy(wakeRadiusM = if (rnd.nextBoolean()) null else rnd.nextDouble(50.0, 2_000.0))))
            }
            var bi = 0
            var baseAlarm: Long? = null
            var ownRing = false
            Harness(cfg).run((plain + edits).sortedBy { it.tMs }) { st ->
                val t = st.input.tMs
                if (st.input is Input.Rang) ownRing = true
                if (t >= firstRing || ownRing) return@run
                while (bi < base.steps.size && base.steps[bi].input.tMs <= t) {
                    baseAlarm = base.steps[bi].state.alarmAtMs.takeIf { base.steps[bi].state.phase in Harness.ALARM_PHASES }
                    bi++
                }
                val ours = st.state.alarmAtMs ?: return@run
                val theirs = baseAlarm ?: return@run
                compared++
                assertTrue(ours <= theirs + 1_000) { "seed $seed at ${(t - T0) / 1000}s: ${(ours - T0) / 1000} > ${(theirs - T0) / 1000}" }
            }
        }
        assertTrue(compared > 20_000, "too few comparisons: $compared")
    }
}

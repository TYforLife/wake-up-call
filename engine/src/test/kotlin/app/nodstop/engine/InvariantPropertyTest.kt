package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * DESIGN §2 invariant, over 2,000 seeded random trips (random polylines, piecewise speed profiles up to
 * V_MAX, noise, bad and stale fixes, random dropouts and kill points):
 *
 *   at every step, the scheduled alarm <= trueArrival - LEAD + TOLERANCE,
 *   so it holds even if all updates stop at that instant.
 *
 * Precondition (DESIGN §2): inside the last (LEAD + DEADMAN_MIN) × V_MAX ≈ 4.5 km, the bus never goes faster
 * than the fastest 30-s average speed the phone has observed in the last WIN_PEAK (5 min), or V_FLOOR. Outside that radius the speed is unconstrained up to V_MAX: the failsafe covers it.
 */
class InvariantPropertyTest {
    private val cfg = Config()
    private val toleranceMs = 20_000L

    private class Case(val seed: Int, val worstMarginS: Double, val leadS: Double?, val steps: Int)

    @Test
    fun `invariant holds over 2000 seeded random trips`() {
        val cases = (1..2_000).map { runCase(it) }
        val failures = cases.filter { it.worstMarginS < 0 || it.leadS == null }
        assertTrue(failures.isEmpty()) {
            "violations: " + failures.take(10).joinToString { "seed ${it.seed}: margin ${"%.1f".format(it.worstMarginS)} s, lead ${it.leadS}" }
        }
        val worst = cases.minBy { it.worstMarginS }
        println("invariant: 2000 trips, ${cases.sumOf { it.steps }} steps, worst margin ${"%.1f".format(worst.worstMarginS)} s (seed ${worst.seed})")
    }

    /** The test has teeth: the same trips with the recent-peak term or the V_MAX failsafe removed do break it. */
    @Test
    fun `without WIN_PEAK or the failsafe the same generator finds violations`() {
        val worstNoPeak = withEngineConfig(Config(winPeakS = 0.0)) { (1..300).minOf { runCase(it).worstMarginS } }
        assertTrue(worstNoPeak < 0, "expected a violation without WIN_PEAK, worst margin $worstNoPeak")
        val worstNoFailsafe = withEngineConfig(Config(vMaxKmh = 1.0)) { (1..300).minOf { runCase(it).worstMarginS } }
        assertTrue(worstNoFailsafe < 0, "expected a violation without the failsafe, worst margin $worstNoFailsafe")
        println("teeth: worst margin without WIN_PEAK ${"%.1f".format(worstNoPeak)} s, without failsafe ${"%.1f".format(worstNoFailsafe)} s")
    }

    /** Runs trips generated with the default [Config] against an engine built with [engineCfg]. */
    private fun <T> withEngineConfig(engineCfg: Config, block: () -> T): T {
        engineOverride = engineCfg
        try { return block() } finally { engineOverride = null }
    }

    private var engineOverride: Config? = null

    private fun runCase(seed: Int): Case {
        val rnd = Random(seed)
        val startDist = rnd.nextDouble(6_000.0, 25_000.0)
        val (path, destAlong) = Path.random(rnd, startDist)
        val dest = path.at(destAlong)
        val reach = cfg.failsafeReachM + 100

        // Plan observation first (fix times, dropouts, kill), since the precondition depends on what the phone sees.
        val roughEndS = destAlong / 3.0 + 3_600
        val dropouts = List(rnd.nextInt(0, 4)) {
            val a = rnd.nextDouble(0.0, roughEndS * 0.6)
            a..(a + rnd.nextDouble(5.0, 400.0))
        }
        val killAt = if (rnd.nextDouble() < 0.5) rnd.nextDouble(0.0, roughEndS * 0.6) else null
        val planSeed = rnd.nextInt()
        val plan = FixPlan(
            intervalS = 1.0..3.0,
            noiseM = rnd.nextDouble(2.0, 8.0),
            lagS = 0.0..1.5,
            badProb = 0.05,
            staleProb = 0.02,
            dropouts = dropouts,
            killAtS = killAt,
        )
        val fixTimes = plan.fixTimes(Random(planSeed), roughEndS * 3)
        val observedUntil = killAt ?: Double.MAX_VALUE
        fun observed(a: Double, b: Double) = b <= observedUntil && dropouts.none { it.start < b && it.endInclusive > a }

        // Piecewise speed profile: random cruise speeds and stops, unconstrained up to V_MAX.
        val segments = ArrayList<Pair<Double, Double>>() // (endTimeS, target m/s)
        var tSeg = 0.0
        while (tSeg < roughEndS * 3) {
            val stop = rnd.nextDouble() < 0.2
            val dur = if (stop) rnd.nextDouble(10.0, 60.0) else rnd.nextDouble(15.0, 180.0)
            tSeg += dur
            segments += tSeg to (if (stop) 0.0 else rnd.nextDouble(3.0, cfg.vMax))
        }
        val accel = rnd.nextDouble(0.6, 2.0)

        val peakHist = ArrayDeque<Pair<Double, Double>>() // (t, true 30-s average) at observed fix times
        var fixIdx = 0
        var segIdx = 0
        val sim = Sim(path, destAlong, accel = accel, maxS = roughEndS * 3) { v ->
            while (segIdx < segments.size - 1 && segments[segIdx].first < v.tS) segIdx++
            var target = min(segments[segIdx].second, cfg.vMax)
            while (fixIdx < fixTimes.size && fixTimes[fixIdx] <= v.tS) {
                val ft = fixTimes[fixIdx++]
                val w = cfg.peakAvgS
                if (ft >= w && observed(ft - w, ft)) {
                    val sim = simRef!!
                    peakHist.addLast(ft to (sim.sAt(ft) - sim.sAt(ft - w)) / w)
                }
            }
            while (peakHist.isNotEmpty() && peakHist.first().first < v.tS - cfg.winPeakS) peakHist.removeFirst()
            if (v.straightToDestM < reach) {
                // Never faster than the observed recent peak (V_FLOOR is always in v_est, so it is allowed too).
                val peak = max(peakHist.maxOfOrNull { it.second } ?: 0.0, cfg.vFloor)
                target = min(target, peak)
            }
            target
        }
        simRef = sim
        sim.run()
        simRef = null

        val arrivalMs = Trips.arrivalMs(sim)
        val legs = listOf(Leg("Dest", dest, expectedRideMin = max(1, floor(sim.arrivalS / 60).toInt())))
        val inputs = plan.inputs(sim, T0, legs, Random(planSeed), fixTimes.filter { it < sim.endS })
        var worst = Double.MAX_VALUE
        val bound = arrivalMs - cfg.leadS.toLong() * 1000 + toleranceMs
        val r = Harness(engineOverride ?: cfg).run(inputs) { st ->
            val a = st.state.alarmAtMs
            if (st.state.phase == Phase.RIDING && a != null) worst = min(worst, (bound - a) / 1000.0)
        }
        val ring = r.rings.firstOrNull()
        val lead = ring?.let { (arrivalMs - it) / 1000.0 }
        if (ring != null) worst = min(worst, (bound - ring) / 1000.0)
        return Case(seed, worst, lead, r.steps.size)
    }

    private var simRef: Sim? = null
}

package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** While You Slept urgent rings (DESIGN §10, invariant W1): they can only add a ring, never delay or remove one. */
class UrgentTest {
    private val cfg = Config()
    private val dest = Plane.origin
    private fun kmh(v: Double) = v / 3.6

    /** A 50 km/h straight trip of [distM] and its inputs, plus [extra] inputs merged in time order. */
    private fun trip(distM: Double = 8_000.0, extra: List<Input> = emptyList(), seed: Int = 1): Pair<Sim, List<Input>> {
        val (path, destAlong) = Path.straight(distM)
        val sim = Sim(path, destAlong) { kmh(50.0) }.run()
        val inputs = FixPlan().inputs(sim, T0, listOf(Trips.leg(dest)), Random(seed))
        return sim to (inputs + extra).sortedBy { it.tMs }
    }

    @Test
    fun `an urgent message rings now, with reason URGENT`() {
        val at = T0 + 120_000
        val (_, inputs) = trip(extra = listOf(Input.Urgent(at)))
        val r = Harness(cfg).run(inputs)
        assertEquals(at, r.rings.first())
        assertEquals(AlarmReason.URGENT, r.ringReasons.first())
    }

    @Test
    fun `urgent survives the trace format`() {
        val e = Trace.inputEvent(Input.Urgent(42))
        assertEquals("urgent", e.k)
        assertEquals(Input.Urgent(42), Trace.toInput(Trace.decode(Trace.encode(e))))
    }

    @Test
    fun `ignored before Start, while ringing, after the trip, and after two urgent rings`() {
        val e = Engine(cfg)
        assertEquals(EngineState(), e.step(EngineState(), Input.Urgent(T0)).first)

        val (_, inputs) = trip(extra = listOf(Input.Urgent(T0 + 60_000), Input.Urgent(T0 + 61_000), Input.Urgent(T0 + 200_000), Input.Urgent(T0 + 300_000)))
        val r = Harness(cfg).run(inputs, ackAfterMs = 10_000)
        // 60 s rings; 61 s arrives while ringing (ignored); 200 s rings; 300 s is over the limit of 2.
        val urgentRings = r.steps.filter { it.input is Input.Rang && it.state.ringReason == AlarmReason.URGENT }.map { it.input.tMs }
        assertEquals(listOf(T0 + 60_000, T0 + 200_000), urgentRings)
        assertEquals(cfg.maxUrgentRings, r.final.urgentRings)

        val done = e.step(r.final.copy(phase = Phase.DONE, alarmAtMs = null), Input.Urgent(T0 + 9_000_000)).first
        assertEquals(null, done.alarmAtMs)
    }

    @Test
    fun `acking an urgent ring far from the stop keeps tracking and the stop alarm still rings on time`() {
        val (sim, base) = trip()
        val baseline = Harness(cfg).run(base)
        val (_, inputs) = trip(extra = listOf(Input.Urgent(T0 + 60_000)))
        val r = Harness(cfg).run(inputs, ackAfterMs = 8_000)
        val ack = r.steps.first { it.input is Input.Ack }
        assertEquals(Phase.WAITING.takeIf { ack.before.resumePhase == Phase.WAITING } ?: Phase.RIDING, ack.state.phase)
        assertNotEquals(Phase.AWAKE, ack.state.phase)
        val stopRing = r.steps.first { it.input is Input.Rang && it.state.ringReason != AlarmReason.URGENT }.input.tMs
        assertTrue(stopRing <= baseline.rings.first() + 1_000, "stop ring ${stopRing - T0} vs baseline ${baseline.rings.first() - T0}")
        assertTrue(Trips.leadS(sim, baseline) in 30.0..60.0)
    }

    @Test
    fun `acking an urgent ring close to the stop does not count as awake for the stop`() {
        val (sim, base) = trip()
        val arrival = Trips.arrivalMs(sim)
        val baseline = Harness(cfg).run(base)
        // About 2 min out: an ordinary early ring acked here would count as "awake for this stop".
        val (_, inputs) = trip(extra = listOf(Input.Urgent(arrival - 120_000)))
        val r = Harness(cfg).run(inputs, ackAfterMs = 5_000)
        val ack = r.steps.first { it.input is Input.Ack }
        assertNotEquals(Phase.AWAKE, ack.state.phase)
        val stopRing = r.steps.first { it.input is Input.Rang && it.state.ringReason != AlarmReason.URGENT }
        assertTrue(stopRing.input.tMs <= baseline.rings.first() + 1_000)
        assertTrue(arrival - stopRing.input.tMs >= 25_000, "stop ring only ${(arrival - stopRing.input.tMs) / 1000} s before arrival")
    }

    @Test
    fun `a fix never pushes a pending urgent ring later`() {
        val (_, inputs) = trip()
        val e = Engine(cfg)
        var s = EngineState()
        val firstFix = inputs.indexOfFirst { it is Input.FixIn && it.tMs > T0 + 90_000 }
        for (i in inputs.take(firstFix)) s = e.step(s, i).first
        val urgentAt = inputs[firstFix].tMs - 500
        s = e.step(s, Input.Urgent(urgentAt)).first
        s = e.step(s, inputs[firstFix]).first
        assertEquals(urgentAt, s.alarmAtMs)
        assertEquals(AlarmReason.URGENT, s.alarmReason)
    }

    /**
     * W1 as a property: random trips (random routes, speeds and stops, noise, bad and stale fixes,
     * dropouts), each run twice: as is, and with random urgent messages acked after a random delay.
     * Until either run first rings for the stop, the urgent run is at every step either ringing or
     * scheduled no later; and its stop alarm rings no later than the plain run's (or than its own last
     * urgent ack, if that came after).
     */
    @Test
    fun `urgent rings never delay the stop alarm over 1000 random trips`() {
        var compared = 0L
        for (seed in 1..1_000) {
            val rnd = Random(seed)
            val (path, destAlong) = Path.random(rnd, rnd.nextDouble(2_000.0, 12_000.0))
            val dest = path.at(destAlong)
            val segments = ArrayList<Pair<Double, Double>>()
            var tSeg = 0.0
            while (tSeg < 20_000) {
                val stop = rnd.nextDouble() < 0.2
                tSeg += if (stop) rnd.nextDouble(10.0, 60.0) else rnd.nextDouble(15.0, 180.0)
                segments += tSeg to (if (stop) 0.0 else rnd.nextDouble(3.0, cfg.vMax))
            }
            var segIdx = 0
            val sim = Sim(path, destAlong, accel = rnd.nextDouble(0.6, 2.0)) { v ->
                while (segIdx < segments.size - 1 && segments[segIdx].first < v.tS) segIdx++
                segments[segIdx].second
            }.run()
            val planSeed = rnd.nextInt()
            val plan = FixPlan(intervalS = 1.0..3.0, noiseM = rnd.nextDouble(2.0, 8.0), lagS = 0.0..1.5, badProb = 0.05, staleProb = 0.02,
                dropouts = List(rnd.nextInt(0, 3)) { val a = rnd.nextDouble(0.0, sim.endS); a..(a + rnd.nextDouble(5.0, 300.0)) })
            val legs = listOf(Trips.leg(dest))
            val plain = plan.inputs(sim, T0, legs, Random(planSeed))
            val baseline = Harness(cfg).run(plain)
            val firstRing = baseline.rings.firstOrNull() ?: continue

            val urgents = List(rnd.nextInt(1, 4)) { Input.Urgent(T0 + (rnd.nextDouble(0.0, (firstRing - T0).toDouble()) ).toLong()) }
            val ackDelay = rnd.nextLong(2_000, 40_000)
            // Baseline schedule over time, for comparison.
            val baseSteps = baseline.steps
            var bi = 0
            var baseAlarm: Long? = null
            var ownStopRing = false
            val r = Harness(cfg).run((plain + urgents).sortedBy { it.tMs }, ackAfterMs = ackDelay) { st ->
                val t = st.input.tMs
                // Compare until either run first rings for the stop; after that, acks are the rider's own.
                if (st.input is Input.Rang && st.state.ringReason != AlarmReason.URGENT) ownStopRing = true
                if (t >= firstRing || ownStopRing) return@run
                while (bi < baseSteps.size && baseSteps[bi].input.tMs <= t) {
                    baseAlarm = baseSteps[bi].state.alarmAtMs.takeIf { baseSteps[bi].state.phase in Harness.ALARM_PHASES }
                    bi++
                }
                val ours = if (st.state.phase == Phase.RINGING) t else st.state.alarmAtMs
                val theirs = baseAlarm ?: return@run
                compared++
                assertTrue(ours != null && ours <= theirs + 1_000) {
                    "seed $seed at ${(t - T0) / 1000} s: ours ${ours?.let { (it - T0) / 1000 }} later than plain ${(theirs - T0) / 1000}"
                }
            }
            val lastUrgentAck = r.steps.filter { it.input is Input.Ack && it.before.ringReason == AlarmReason.URGENT }.maxOfOrNull { it.input.tMs }
            val stopRing = r.steps.firstOrNull { it.input is Input.Rang && it.state.ringReason != AlarmReason.URGENT }?.input?.tMs
            val limit = maxOf(firstRing, lastUrgentAck ?: firstRing) + 1_000
            assertTrue(stopRing != null && stopRing <= limit) { "seed $seed: stop ring ${stopRing?.let { (it - T0) / 1000 }} s, limit ${(limit - T0) / 1000} s" }
        }
        println("urgent property: $compared steps compared")
        assertTrue(compared > 100_000, "too few comparisons: $compared")
    }
}

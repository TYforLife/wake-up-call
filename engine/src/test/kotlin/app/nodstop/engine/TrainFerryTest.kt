package app.nodstop.engine

import app.nodstop.engine.Trips.T0
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * BACKLOG F29: trains and ferries. A train is faster than any bus, so its failsafe assumes the train's top speed; an
 * underground station loses GPS just before it, so the failsafe must ring in time on its own (rule 4).
 */
class TrainFerryTest {
    private fun kmh(v: Double) = v / 3.6
    private val cfg = Config()

    /** A straight 12 km line at [speedKmh], GPS lost for the last [tunnelM] before the stop. Lead of the first ring, s. */
    private fun ride(mode: Mode, speedKmh: Double, tunnelM: Double = 0.0, seed: Int = 1, lengthM: Double = 12_000.0): Double {
        val path = Path(listOf(Plane.toLatLng(0.0, 0.0), Plane.toLatLng(0.0, lengthM + 500)))
        val sim = Sim(path, lengthM) { kmh(speedKmh) }.run()
        val tunnelFromS = sim.timeWhen(lengthM - tunnelM)
        val plan = FixPlan(dropouts = if (tunnelM > 0) listOf(tunnelFromS..(sim.arrivalS + 600)) else emptyList(), untilS = sim.arrivalS + 60)
        val legs = listOf(Leg("Station", path.at(lengthM), mode = mode))
        val r = Harness(cfg).run(plan.inputs(sim, T0, legs, Random(seed)), ackAfterMs = 5_000)
        return Trips.leadS(sim, r)
    }

    @Test
    fun `a fast train rings 30-60 s before the station`() {
        for (seed in 1..5) {
            val lead = ride(Mode.TRAIN, 110.0, seed = seed)
            assertTrue(lead in 30.0..65.0, "seed $seed: lead $lead s")
        }
    }

    @Test
    fun `a tunnel just before an underground station - the failsafe still rings in time`() {
        for (seed in 1..5) for (tunnel in listOf(1_000.0, 2_000.0, 3_000.0)) {
            val lead = ride(Mode.TRAIN, 110.0, tunnel, seed)
            assertTrue(lead >= cfg.leadS - 5, "seed $seed, tunnel $tunnel m: lead $lead s")
        }
    }

    @Test
    fun `the same tunnel ridden as a bus rings too late - the mode is what keeps it safe`() {
        val asBus = ride(Mode.BUS, 110.0, 3_000.0)
        val asTrain = ride(Mode.TRAIN, 110.0, 3_000.0)
        assertTrue(asBus < 30.0 && asTrain > asBus, "as a bus $asBus s, as a train $asTrain s")
    }

    @Test
    fun `a ferry across open water rings 30-60 s before the wharf`() {
        for (seed in 1..5) {
            val lead = ride(Mode.FERRY, 60.0, seed = seed, lengthM = 8_000.0)
            assertTrue(lead in 30.0..65.0, "seed $seed: lead $lead s")
        }
    }

    @Test
    fun `a bus leg's alarm off, then a train - the failsafe for the train's stop assumes the train`() {
        // A bus to the station (alarm off), then the train; GPS lost from before the change to the end.
        val change = 4_000.0
        val end = 12_000.0
        val path = Path(listOf(Plane.toLatLng(0.0, 0.0), Plane.toLatLng(0.0, end + 500)))
        val sim = Sim(path, end) { v -> kmh(if (v.sM < change) 50.0 else 110.0) }.run()
        val plan = FixPlan(dropouts = listOf(sim.timeWhen(change - 500)..(sim.arrivalS + 600)), untilS = sim.arrivalS + 60)
        val legs = listOf(
            Leg("Change", path.at(change), alarmEnabled = false),
            Leg("Station", path.at(end), mode = Mode.TRAIN),
        )
        val r = Harness(cfg).run(plan.inputs(sim, T0, legs, Random(3)), ackAfterMs = 5_000)
        val lead = Trips.leadS(sim, r)
        assertTrue(lead >= cfg.leadS - 5, "lead $lead s")
    }

    @Test
    fun `the train's top speed is at least the bus's, whatever the config`() {
        assertTrue(Config(vMaxKmh = 150.0).vMaxOf(Mode.TRAIN) >= Config(vMaxKmh = 150.0).vMaxOf(Mode.BUS))
        assertTrue(cfg.vMaxOf(Mode.FERRY) == cfg.vMax)
    }
}

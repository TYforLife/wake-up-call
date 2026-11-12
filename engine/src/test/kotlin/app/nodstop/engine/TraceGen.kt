package app.nodstop.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/** Local plane (metres east/north) around Auckland, for building synthetic routes. */
object Plane {
    val origin = LatLng(-36.80, 174.75)
    private val mPerDegLat = PI * Geo.EARTH_RADIUS_M / 180
    private val mPerDegLng = mPerDegLat * cos(Math.toRadians(origin.lat))

    fun toLatLng(x: Double, y: Double) = LatLng(origin.lat + y / mPerDegLat, origin.lng + x / mPerDegLng)
    fun x(p: LatLng) = (p.lng - origin.lng) * mPerDegLng
    fun y(p: LatLng) = (p.lat - origin.lat) * mPerDegLat

    /** Offset a point by metres, for GPS noise. */
    fun offset(p: LatLng, dx: Double, dy: Double) = LatLng(p.lat + dy / mPerDegLat, p.lng + dx / mPerDegLng)
}

/** A polyline with cumulative great-circle lengths. */
class Path(val pts: List<LatLng>) {
    val cum: DoubleArray = DoubleArray(pts.size).also {
        for (i in 1 until pts.size) it[i] = it[i - 1] + Geo.distanceM(pts[i - 1], pts[i])
    }
    val length get() = cum.last()

    fun at(s: Double): LatLng {
        if (s <= 0) return pts.first()
        if (s >= length) return pts.last()
        var lo = 0
        var hi = pts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= s) lo = mid else hi = mid
        }
        val f = (s - cum[lo]) / (cum[hi] - cum[lo])
        val a = pts[lo]
        val b = pts[hi]
        return LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
    }

    companion object {
        /** Straight north-bound approach: starts [distM] south of the destination at the origin, continues [beyondM] past it. */
        fun straight(distM: Double, beyondM: Double = 2_000.0): Pair<Path, Double> {
            val pts = mutableListOf<LatLng>()
            var y = -distM
            while (y < beyondM) { pts += Plane.toLatLng(0.0, y); y += 100.0 }
            pts += Plane.toLatLng(0.0, beyondM)
            val path = Path(pts)
            return path to path.cum[pts.indexOfFirst { abs(Plane.y(it)) < 1e-6 }]
        }

        /** Zigzag toward the destination with path length = [ratio] × straight line. */
        fun zigzag(straightM: Double, ratio: Double, zigM: Double = 100.0): Pair<Path, Double> {
            val theta = kotlin.math.acos(1 / ratio)
            val dy = zigM * cos(theta)
            val dx = zigM * sin(theta)
            val n = (straightM / dy).toInt()
            val pts = mutableListOf<LatLng>()
            for (i in 0..n) {
                val y = -straightM + i * dy
                val x = if (i % 2 == 0) 0.0 else dx
                pts += Plane.toLatLng(x, y)
            }
            // close exactly on the destination, then continue straight past it
            pts += Plane.toLatLng(0.0, 0.0)
            val destIdx = pts.size - 1
            pts += Plane.toLatLng(0.0, 2_000.0)
            val path = Path(pts)
            return path to path.cum[destIdx]
        }

        /** Random road-like route: bounded turning, steering toward the destination, then 1 km beyond it. */
        fun random(rnd: Random, startDistM: Double): Pair<Path, Double> {
            val bearing0 = rnd.nextDouble(0.0, 2 * PI)
            var x = startDistM * sin(bearing0)
            var y = startDistM * cos(bearing0)
            var heading = atan2(-x, -y) + rnd.nextDouble(-1.0, 1.0)
            val pts = mutableListOf(Plane.toLatLng(x, y))
            val step = 50.0
            val maxTurn = Math.toRadians(25.0)
            val wander = rnd.nextDouble(0.1, 0.6)
            var guard = 0
            while (x * x + y * y > step * step && guard++ < 5_000) {
                val toDest = atan2(-x, -y)
                var diff = toDest - heading
                while (diff > PI) diff -= 2 * PI
                while (diff < -PI) diff += 2 * PI
                val remaining = kotlin.math.sqrt(x * x + y * y)
                val steer = if (remaining < 800) 1.0 else 0.35
                val turn = (diff * steer + rnd.nextDouble(-wander, wander)).coerceIn(-maxTurn, maxTurn)
                heading += turn
                x += step * sin(heading)
                y += step * cos(heading)
                pts += Plane.toLatLng(x, y)
            }
            pts += Plane.toLatLng(0.0, 0.0)
            val destIdx = pts.size - 1
            for (i in 1..20) pts += Plane.toLatLng(i * step * sin(heading), i * step * cos(heading))
            val path = Path(pts)
            return path to path.cum[destIdx]
        }
    }
}

/** True motion along a path, simulated at a fixed step with acceleration limits. */
class Sim(
    val path: Path,
    val destAlongM: Double,
    val dtS: Double = 0.2,
    val accel: Double = 1.5,
    val decel: Double = 2.5,
    val maxS: Double = 4 * 3600.0,
    /** Target speed in m/s given the current view. */
    val control: (View) -> Double,
) {
    inner class View(val tS: Double, val sM: Double, val vMps: Double) {
        val pos: LatLng get() = path.at(sM)
        val straightToDestM: Double get() = Geo.distanceM(pos, path.at(destAlongM))
        /** Distance travelled since [agoS] seconds ago. */
        fun travelledSince(agoS: Double): Double = sM - sAt(tS - agoS)
    }

    private val sHist = ArrayList<Double>()
    var arrivalS: Double = Double.NaN
        private set
    var endS: Double = 0.0
        private set

    fun sAt(tS: Double): Double {
        if (tS <= 0 || sHist.isEmpty()) return 0.0
        val i = (tS / dtS).toInt()
        if (i >= sHist.size - 1) return sHist.last()
        val f = tS / dtS - i
        return sHist[i] + (sHist[i + 1] - sHist[i]) * f
    }

    fun posAt(tS: Double): LatLng = path.at(sAt(tS))

    /** First time the bus reaches [sM] along the path. */
    fun timeWhen(sM: Double): Double {
        for (i in 1 until sHist.size) if (sHist[i] >= sM) {
            val f = (sM - sHist[i - 1]) / (sHist[i] - sHist[i - 1]).coerceAtLeast(1e-9)
            return (i - 1 + f) * dtS
        }
        return Double.NaN
    }

    fun run(): Sim {
        var t = 0.0
        var s = 0.0
        var v = 0.0
        sHist += 0.0
        while (s < path.length - 1 && t < maxS) {
            val target = control(View(t, s, v)).coerceAtLeast(0.0)
            v = if (target > v) min(target, v + accel * dtS) else max(target, v - decel * dtS)
            val sNext = s + v * dtS
            if (arrivalS.isNaN() && sNext >= destAlongM) {
                arrivalS = t + if (v > 0) (destAlongM - s) / v else 0.0
            }
            s = sNext
            t += dtS
            sHist += s
        }
        endS = t
        check(!arrivalS.isNaN()) { "trip never arrived" }
        return this
    }
}

/** Turns a simulated trip into engine inputs: GPS fixes (noise, lag, bad and stale fixes, dropouts) and ticks. */
class FixPlan(
    val intervalS: ClosedFloatingPointRange<Double> = 2.0..2.0,
    val noiseM: Double = 3.0,
    /**
     * GNSS error is correlated in time (multipath, atmosphere): AR(1) per axis with this time constant.
     * 0 = independent noise per fix (harsher than real phones for speed-from-path-length).
     */
    val noiseTauS: Double = 20.0,
    /** Independent per-fix noise on top of the correlated part. */
    val whiteM: Double = 1.0,
    val lagS: ClosedFloatingPointRange<Double> = 0.5..0.5,
    val badProb: Double = 0.0,
    val staleProb: Double = 0.0,
    val dropouts: List<ClosedFloatingPointRange<Double>> = emptyList(),
    val killAtS: Double? = null,
    val untilS: Double? = null,
    val tickS: Double = 5.0,
    val lastKnown: Boolean = true,
) {
    fun fixTimes(rnd: Random, endS: Double): List<Double> {
        val out = ArrayList<Double>()
        var t = rnd.nextDouble(0.5, 2.0)
        val stop = min(untilS ?: endS, killAtS ?: Double.MAX_VALUE)
        while (t < stop) {
            if (dropouts.none { t in it }) out += t
            t += if (intervalS.start == intervalS.endInclusive) intervalS.start
            else rnd.nextDouble(intervalS.start, intervalS.endInclusive)
        }
        return out
    }

    fun inputs(sim: Sim, t0Ms: Long, legs: List<Leg>, rnd: Random, times: List<Double> = fixTimes(rnd, sim.endS)): List<Input> {
        val out = ArrayList<Input>()
        // The phone's last known position when you tap Start (where you are).
        out += Input.Start(t0Ms, legs, lastKnown = if (lastKnown) Fix(t0Ms, t0Ms, sim.posAt(0.0), 10f) else null)
        var ex = rnd.nextGaussian() * noiseM
        var ey = rnd.nextGaussian() * noiseM
        var lastT = 0.0
        for (t in times) {
            if (noiseTauS > 0) {
                val rho = kotlin.math.exp(-(t - lastT) / noiseTauS)
                val k = kotlin.math.sqrt(1 - rho * rho) * noiseM
                ex = rho * ex + k * rnd.nextGaussian()
                ey = rho * ey + k * rnd.nextGaussian()
            } else {
                ex = rnd.nextGaussian() * noiseM
                ey = rnd.nextGaussian() * noiseM
            }
            lastT = t
            val tMs = t0Ms + (t * 1000).roundToLong()
            val roll = rnd.nextDouble()
            val fix = when {
                roll < badProb -> {
                    val err = rnd.nextDouble(60.0, 300.0)
                    val a = rnd.nextDouble(0.0, 2 * PI)
                    val p = Plane.offset(sim.posAt(t), err * sin(a), err * cos(a))
                    Fix(tMs, tMs - 500, p, rnd.nextDouble(51.0, 200.0).toFloat())
                }
                roll < badProb + staleProb -> {
                    val age = rnd.nextDouble(11.0, 60.0)
                    Fix(tMs, tMs - (age * 1000).roundToLong(), sim.posAt(max(0.0, t - age)), 8f)
                }
                else -> {
                    val lag = if (lagS.start == lagS.endInclusive) lagS.start else rnd.nextDouble(lagS.start, lagS.endInclusive)
                    val measured = max(0.0, t - lag)
                    val p = Plane.offset(sim.posAt(measured), ex + rnd.nextGaussian() * whiteM, ey + rnd.nextGaussian() * whiteM)
                    Fix(tMs, t0Ms + (measured * 1000).roundToLong(), p, (noiseM * 1.5 + 2).toFloat())
                }
            }
            out += Input.FixIn(tMs, fix)
        }
        val stop = min(untilS ?: sim.endS, killAtS ?: Double.MAX_VALUE)
        var tick = tickS
        while (tick < stop) {
            out += Input.Tick(t0Ms + (tick * 1000).roundToLong())
            tick += tickS
        }
        return out.sortedBy { it.tMs } // stable: Start stays first
    }
}

fun Random.nextGaussian(): Double {
    // Box-Muller
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * PI * u2)
}

/**
 * Drives the engine like the Android shell would: inputs in time order, and the system alarm firing
 * (a Rang input) whenever time reaches the scheduled alarm. Optionally acks each ring after a delay.
 */
class Harness(val cfg: Config = Config()) {
    val engine = Engine(cfg)

    companion object {
        val ALARM_PHASES = setOf(Phase.WAITING, Phase.RIDING, Phase.AWAKE)
    }

    class Step(val input: Input, val before: EngineState, val state: EngineState, val out: Output)

    class Result(val steps: List<Step>) {
        val rings: List<Long> get() = steps.filter { it.input is Input.Rang }.map { it.input.tMs }
        val arrivals: List<Long> get() = steps.filter { it.out.arrived }.map { it.input.tMs }
        val learned: List<LearnedUpdate> get() = steps.mapNotNull { it.out.learned }
        val ringReasons: List<AlarmReason?> get() = steps.filter { it.input is Input.Rang }.map { it.state.ringReason }
        val overshoots: List<Long> get() = steps.filter { it.out.overshoot }.map { it.input.tMs }
        val final: EngineState get() = steps.last().state
    }

    fun run(inputs: List<Input>, ackAfterMs: Long? = null, horizonMs: Long = 2 * 3_600_000L, onStep: (Step) -> Unit = {}): Result {
        val queue = ArrayDeque(inputs.sortedBy { it.tMs })
        val lastInputMs = queue.lastOrNull()?.tMs ?: 0L
        var state = EngineState()
        var ackAt: Long? = null
        val steps = ArrayList<Step>()
        fun apply(input: Input) {
            val (next, out) = engine.step(state, input)
            val st = Step(input, state, next, out)
            steps += st
            onStep(st)
            state = next
            if (input is Input.Rang && ackAfterMs != null && next.phase == Phase.RINGING) ackAt = input.tMs + ackAfterMs
        }
        while (true) {
            val nextInput = queue.firstOrNull()?.tMs
            val alarm = if (state.phase in ALARM_PHASES) state.alarmAtMs else null
            val candidates = listOfNotNull(nextInput, alarm, ackAt)
            if (candidates.isEmpty()) break
            val t = candidates.min()
            // After the last input, follow alarms for a while (a kill's failsafe), not forever.
            if (nextInput == null && t > lastInputMs + horizonMs) break
            when {
                alarm != null && alarm == t -> apply(Input.Rang(t))
                ackAt != null && ackAt == t -> { ackAt = null; apply(Input.Ack(t)) }
                else -> apply(queue.removeFirst())
            }
            if (steps.size > 2_000_000) error("runaway")
        }
        return Result(steps)
    }
}

/** Common trip setup for scenario tests. */
object Trips {
    const val T0 = 1_731_300_000_000L

    fun leg(dest: LatLng, name: String = "Stop", learned: Learned? = null, alarm: Boolean = true) =
        Leg(name = name, dest = dest, alarmEnabled = alarm, learned = learned)

    /** Learns from a run the way the app does: merge each completed leg's update into the leg it belongs to. */
    fun learnInto(legs: List<Leg>, result: Harness.Result): List<Leg> = legs.map { leg ->
        result.learned.filter { it.dest == leg.dest }.fold(leg) { l, u -> l.copy(learned = Learning.merge(l.learned, u)) }
    }

    fun arrivalMs(sim: Sim, t0: Long = T0) = t0 + (sim.arrivalS * 1000).roundToLong()

    /** Lead time in seconds of the first ring before arrival (positive = early). */
    fun leadS(sim: Sim, result: Harness.Result, t0: Long = T0): Double =
        (arrivalMs(sim, t0) - result.rings.first()) / 1000.0
}

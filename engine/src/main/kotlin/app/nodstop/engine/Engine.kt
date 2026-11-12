package app.nodstop.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** An accepted fix, timed by when it was measured. */
data class Sample(val tMs: Long, val pos: LatLng)

/** A [Config.peakAvgS] window average that counts towards the recent peak. */
data class ShortAvg(val tMs: Long, val mps: Double)

data class EngineState(
    val phase: Phase = Phase.IDLE,
    val legs: List<Leg> = emptyList(),
    val legIndex: Int = 0,
    val alarmAtMs: Long? = null,
    val alarmReason: AlarmReason? = null,
    val ringReason: AlarmReason? = null,
    /** The phase to return to when an early ring (GPS lost, far away) is acked. */
    val resumePhase: Phase? = null,
    val legStartMs: Long = 0,
    val samples: List<Sample> = emptyList(),
    val shortAvgs: List<ShortAvg> = emptyList(),
    /** This leg's ride, about every 25 m from boarding: becomes a learned track. */
    val trail: List<Sample> = emptyList(),
    val boarded: Boolean = false,
    val minD: Double = Double.MAX_VALUE,
    val lastD: Double? = null,
    val startD: Double? = null,
    /** The early-biased ETA the alarm used, and when it was measured. */
    val lastEtaS: Double? = null,
    val lastEtaBaseMs: Long? = null,
    val displayEtaS: Double? = null,
    val lastVest: Double? = null,
    val lastSpeed: Double? = null,
    val lastFixMs: Long? = null,
    val lastPos: LatLng? = null,
    val onLearned: Boolean = false,
    val overshootSent: Boolean = false,
    val arrivedSent: Boolean = false,
    /** Continuous slow period near the stop (getting off). */
    val dwellSinceMs: Long? = null,
    val dwellStartPos: LatLng? = null,
    val ackMs: Long? = null,
) {
    val leg: Leg? get() = legs.getOrNull(legIndex)
}

/**
 * The deterministic engine: step(state, input) -> (state, output). No clocks, no randomness, no I/O.
 *
 * Every accepted fix while waiting or riding schedules min(wakeAt, failsafeAt), clamped to >= now:
 *   d          = distance left along the learned route (× 0.95), else straight line, to the stop
 *   v_est      = max(V_FLOOR, avg15, learned: d / fastest past time from here; else fastest 15-s avg of the last 30 s)
 *   wakeAt     = fixTime + d / v_est - LEAD                       (LEAD = 45 s)
 *   failsafeAt = fixTime + max(DEADMAN_MIN, straight / V_MAX - LEAD)
 */
class Engine(val cfg: Config = Config()) {

    private class Edges {
        var overshoot = false
        var arrived = false
        var boarded = false
        var learned: LearnedUpdate? = null
    }

    fun step(s: EngineState, input: Input): Pair<EngineState, Output> {
        val e = Edges()
        val next: EngineState = when (input) {
            is Input.Start ->
                if (input.legs.isEmpty()) EngineState(phase = Phase.DONE)
                else enterLeg(
                    EngineState(legs = input.legs, lastPos = input.lastKnown?.pos, lastFixMs = input.lastKnown?.fixTimeMs?.takeIf { it <= input.tMs }),
                    0, input.tMs, awake = input.awakeOnFirst,
                ).let { st -> if (input.lastKnown != null) st.copy(lastFixMs = null) else st }

            is Input.FixIn -> onFix(s, input, e)
            is Input.Tick -> s
            is Input.Rang -> when (s.phase) {
                Phase.WAITING, Phase.RIDING, Phase.AWAKE ->
                    s.copy(phase = Phase.RINGING, resumePhase = s.phase, ringReason = s.alarmReason, alarmAtMs = null, alarmReason = null)
                else -> s
            }
            is Input.Ack -> if (s.phase == Phase.RINGING) onAck(s, input.tMs) else s
            is Input.SkipLeg -> when (s.phase) {
                Phase.WAITING, Phase.RIDING, Phase.RINGING, Phase.AWAKE -> advance(s, input.tMs)
                else -> s
            }
            is Input.Stop -> s.copy(phase = Phase.DONE, alarmAtMs = null, alarmReason = null)
        }
        val status = status(next, input.tMs)
        val out = Output(
            alarmAtMs = next.alarmAtMs,
            overshoot = e.overshoot,
            arrived = e.arrived,
            boarded = e.boarded,
            learned = e.learned,
            status = status,
            events = listOf(Trace.inputEvent(input), Trace.outEvent(input.tMs, e.overshoot, e.arrived, e.boarded, status)),
        )
        return next to out
    }

    // ---- legs ----

    private fun advance(s: EngineState, nowMs: Long): EngineState =
        if (s.legIndex + 1 < s.legs.size) enterLeg(s, s.legIndex + 1, nowMs)
        else s.copy(phase = Phase.DONE, alarmAtMs = null, alarmReason = null)

    /**
     * Fresh speed windows and trail per leg (a previous bus's speed says nothing about the next one).
     * The last known position carries over, so the new leg's failsafe is armed straight away.
     */
    private fun enterLeg(s: EngineState, index: Int, nowMs: Long, awake: Boolean = false): EngineState {
        val st = EngineState(
            phase = if (awake) Phase.AWAKE else Phase.WAITING,
            legs = s.legs,
            legIndex = index,
            legStartMs = nowMs,
            lastFixMs = s.lastFixMs,
            lastPos = s.lastPos,
            ackMs = if (awake) nowMs else null,
        )
        return withAlarm(st, entryAlarm(st, nowMs))
    }

    /** The alarm when a leg starts, before any fix of its own. */
    private fun entryAlarm(st: EngineState, nowMs: Long): Pair<Long, AlarmReason>? {
        val target = alarmTarget(st) ?: return null
        val pos = st.lastPos
        val ft = st.lastFixMs
        if (pos == null || ft == null || nowMs - ft > cfg.noFixS * 1000) {
            return nowMs + (cfg.noFixS * 1000).roundToLong() to AlarmReason.NO_GPS
        }
        val reason = if (target == st.legIndex && st.phase != Phase.AWAKE) AlarmReason.FAILSAFE else AlarmReason.NEXT_LEG
        return max(nowMs, failsafeAt(ft, Geo.distanceM(pos, st.legs[target].stop))) to reason
    }

    /**
     * Which leg's stop the alarm protects: this leg while you might sleep on it; the next alarm-on leg
     * once you're awake for this one (or this one's alarm is off). Null: nothing left to protect.
     */
    private fun alarmTarget(st: EngineState): Int? {
        val from = if (st.phase == Phase.AWAKE || st.leg?.alarmEnabled == false) st.legIndex + 1 else st.legIndex
        for (i in from until st.legs.size) if (st.legs[i].alarmEnabled) return i
        return null
    }

    private fun withAlarm(st: EngineState, a: Pair<Long, AlarmReason>?): EngineState =
        st.copy(alarmAtMs = a?.first, alarmReason = a?.second)

    private fun failsafeAt(ftMs: Long, straightM: Double): Long =
        ftMs + (max(cfg.deadmanMinS, straightM / cfg.vMax - cfg.leadS) * 1000).roundToLong()

    // ---- ack ----

    private fun onAck(s: EngineState, nowMs: Long): EngineState {
        val back = s.resumePhase ?: Phase.RIDING
        return when (s.ringReason) {
            // You know you passed it: this leg is over.
            AlarmReason.OVERSHOOT -> advance(s, nowMs)
            // A safety ring for something further on: carry on as you were.
            AlarmReason.NO_GPS, AlarmReason.NEXT_LEG -> rearm(s.copy(phase = back, ackMs = nowMs), nowMs)
            else -> {
                val remaining = remainingS(s, nowMs)
                if (remaining != null && remaining <= cfg.ackCountsWithinS) {
                    rearm(s.copy(phase = Phase.AWAKE, ackMs = nowMs), nowMs)
                } else {
                    // An early ring (e.g. GPS lost far out): keep tracking, ring again near the stop.
                    rearm(s.copy(phase = back, ackMs = nowMs), nowMs)
                }
            }
        }
    }

    /** Alarm after an ack, from the last known fix; never earlier than now + REARM if that fix is older than the ack. */
    private fun rearm(st: EngineState, nowMs: Long): EngineState {
        val target = alarmTarget(st) ?: return withAlarm(st, null)
        val pos = st.lastPos
        val ft = st.lastFixMs
        val floor = nowMs + (cfg.rearmS * 1000).roundToLong()
        if (pos == null || ft == null) return withAlarm(st, max(floor, nowMs + (cfg.noFixS * 1000).roundToLong()) to AlarmReason.NO_GPS)
        val reason = if (target == st.legIndex) AlarmReason.FAILSAFE else AlarmReason.NEXT_LEG
        val straight = Geo.distanceM(pos, st.legs[target].stop)
        var at = failsafeAt(ft, straight)
        if (target == st.legIndex) {
            val eta = st.lastEtaS
            if (eta != null && st.lastEtaBaseMs != null) at = min(at, st.lastEtaBaseMs + ((eta - cfg.leadS) * 1000).roundToLong())
        }
        return withAlarm(st, max(at, floor) to reason)
    }

    /** Early-biased seconds left to the current stop, from the last fix. */
    private fun remainingS(s: EngineState, nowMs: Long): Double? {
        val eta = s.lastEtaS ?: return null
        val base = s.lastEtaBaseMs ?: return null
        return eta - (nowMs - base) / 1000.0
    }

    /**
     * When the current leg's bus reaches its stop, by the early-biased ETA (for resuming after a kill).
     * Null when there is no estimate.
     */
    fun remainingArrivalMs(s: EngineState, nowMs: Long): Long? {
        val r = remainingS(s, nowMs) ?: return null
        return if (r > 0) nowMs + (r * 1000).roundToLong() else null
    }

    // ---- fixes ----

    private fun accepts(s: EngineState, input: Input.FixIn): Boolean {
        val f = input.fix
        if (f.accuracyM.isNaN() || f.accuracyM > cfg.maxAccuracyM) return false
        val ageMs = input.tMs - f.fixTimeMs
        if (ageMs > cfg.maxFixAgeS * 1000) return false
        if (ageMs < -1000) return false // from the future: clock confusion, ignore
        val last = s.lastFixMs
        if (last != null && f.fixTimeMs <= last) return false // duplicate or out of order
        return true
    }

    private fun onFix(s: EngineState, input: Input.FixIn, e: Edges): EngineState {
        if (s.phase !in ACTIVE) return s
        if (!accepts(s, input)) return s
        val leg = s.leg ?: return s
        val now = input.tMs
        val ft = input.fix.fixTimeMs
        val pos = input.fix.pos

        val keepMs = (max(cfg.winShortS, max(cfg.boardWindowS, cfg.peakAvgS)) * 1000 + 1_000).toLong()
        val samples = (s.samples + Sample(ft, pos)).filter { it.tMs >= ft - keepMs }
        val straight = Geo.distanceM(pos, leg.stop)
        val nearStop = min(straight, Geo.distanceM(pos, leg.dest))
        val minD = min(s.minD, straight)

        val short = avgSpeed(samples, ft, cfg.winShortS, cfg.minSpanS)
        val peakSample = avgWithSpan(samples, ft, cfg.peakAvgS)
        val shortAvgs = (if (cfg.winPeakS > 0 && peakSample != null && peakSample.second >= cfg.peakAvgS * cfg.peakMinSpanFrac)
            s.shortAvgs + ShortAvg(ft, peakSample.first) else s.shortAvgs)
            .filter { it.tMs >= ft - (cfg.winPeakS * 1000).toLong() }
        val peak = shortAvgs.maxOfOrNull { it.mps }
        val slow10 = displacementSpeed(samples, ft, 10.0)
        val board = displacementSpeed(samples, ft, cfg.boardWindowS, minSpanS = cfg.boardWindowS * 0.6)

        val match = Learning.match(leg.learned, pos, cfg.matchRadiusM)
        val dEff = match?.let { max(straight, it.toEndM * cfg.routeFactor) } ?: straight
        // How fast a bus has really covered the rest from here (learned), else the short recent peak.
        val learnedV = match?.fastestToEndS?.takeIf { it >= 1.0 }?.let { dEff / it }
        // On a learned route, past rides are a better guide than the fixed floor (city streets under 25 km/h).
        val floor = if (learnedV != null) cfg.vFloor * cfg.learnedFloorFrac else cfg.vFloor
        val vEst = maxOf(floor, short ?: 0.0, learnedV ?: peak ?: 0.0)
        val etaS = dEff / vEst
        val displayEta = match?.typicalToEndS ?: short?.takeIf { it > 2.0 }?.let { dEff / it }

        var boarded = s.boarded
        var phase = s.phase
        if (!boarded && board != null && board >= cfg.boardKmh / 3.6) {
            boarded = true
            e.boarded = true
            if (phase == Phase.WAITING) phase = Phase.RIDING
        }
        val trail = if (!boarded) s.trail
        else if (s.trail.isEmpty() || Geo.distanceM(s.trail.last().pos, pos) >= cfg.trailStepM) s.trail + Sample(ft, pos)
        else s.trail

        // Continuous slow period near the stop: that's you getting off (or waiting there for the next bus).
        val slowNear = nearStop <= cfg.alightRadiusM && slow10 != null && slow10 < cfg.alightSlowKmh / 3.6
        val dwellSince = if (slowNear) s.dwellSinceMs ?: ft else null
        val dwellPos = if (slowNear) s.dwellStartPos ?: pos else null

        var st = s.copy(
            phase = phase, samples = samples, shortAvgs = shortAvgs, trail = trail, boarded = boarded,
            minD = minD, lastD = dEff, startD = s.startD ?: if (boarded) dEff else null,
            lastVest = vEst, lastSpeed = short, lastFixMs = ft, lastPos = pos, onLearned = match != null,
            displayEtaS = displayEta, dwellSinceMs = dwellSince, dwellStartPos = dwellPos,
        )
        // The alarm's ETA only means something for the leg you might sleep on.
        if (leg.alarmEnabled) st = st.copy(lastEtaS = etaS, lastEtaBaseMs = ft)

        val passed = minD < cfg.overshootNearM && straight > minD + cfg.overshootGrowM && (short ?: 0.0) >= cfg.boardKmh / 3.6

        if (phase == Phase.RINGING) {
            if (passed && !s.overshootSent) {
                e.overshoot = true
                return st.copy(overshootSent = true)
            }
            return st
        }

        val watchingForGetOff = phase == Phase.AWAKE || !leg.alarmEnabled
        if (watchingForGetOff) {
            if (phase == Phase.AWAKE && passed && !s.overshootSent) {
                return st.copy(overshootSent = true, alarmAtMs = now, alarmReason = AlarmReason.OVERSHOOT)
            }
            if (!s.arrivedSent && boarded &&
                (straight <= cfg.nudgeM || (straight <= cfg.nudgeStoppedM && slow10 != null && slow10 < 1.0))
            ) {
                e.arrived = true
                st = st.copy(arrivedSent = true)
            }
            if (dwellSince != null && ft - dwellSince >= cfg.alightDwellS * 1000) {
                val stop = dwellPos ?: pos
                // The track ends at your pin (what the alarm aims for); where you got off is only a suggestion.
                if (boarded) e.learned = LearnedUpdate(leg.dest, stop, Learning.buildTrack(trail, leg.dest))
                return advance(st, now)
            }
            return scheduleNextLeg(st, now, ft, pos)
        }

        // WAITING or RIDING on an alarm-on leg.
        val wakeAt = ft + ((etaS - cfg.leadS) * 1000).roundToLong()
        val failsafe = failsafeAt(ft, straight)
        var alarm = min(wakeAt, failsafe)
        var reason = if (wakeAt <= failsafe) AlarmReason.WAKE else AlarmReason.FAILSAFE
        // No speed information yet (start of a leg, or back from a dropout): a fix may pull the alarm earlier, never later.
        val current = s.alarmAtMs
        if (short == null && current != null && current < alarm) { alarm = current; reason = s.alarmReason ?: reason }
        // Rode past the stop without ringing (e.g. a mis-pasted stop): ring now.
        if (passed) { alarm = now; reason = AlarmReason.OVERSHOOT }
        alarm = max(alarm, now)
        return st.copy(alarmAtMs = alarm, alarmReason = reason)
    }

    /** While awake for this stop (or this leg's alarm is off): only the next alarm-on leg's failsafe. */
    private fun scheduleNextLeg(st: EngineState, now: Long, ft: Long, pos: LatLng): EngineState {
        val target = alarmTarget(st) ?: return withAlarm(st, null)
        val at = max(now, failsafeAt(ft, Geo.distanceM(pos, st.legs[target].stop)))
        return withAlarm(st, at to AlarmReason.NEXT_LEG)
    }

    /** Path length ÷ time over the samples in the trailing window, or null if they span less than [minSpanS]. */
    private fun avgSpeed(samples: List<Sample>, nowMs: Long, winS: Double, minSpanS: Double): Double? {
        val r = avgWithSpan(samples, nowMs, winS) ?: return null
        return if (r.second >= minSpanS) r.first else null
    }

    /** Start-to-end displacement ÷ time: robust to GPS jitter when standing still. */
    private fun displacementSpeed(samples: List<Sample>, nowMs: Long, winS: Double, minSpanS: Double = cfg.minSpanS): Double? {
        val from = nowMs - (winS * 1000).toLong()
        val first = samples.firstOrNull { it.tMs >= from } ?: return null
        val last = samples.last()
        val spanS = (last.tMs - first.tMs) / 1000.0
        if (spanS < minSpanS) return null
        return Geo.distanceM(first.pos, last.pos) / spanS
    }

    private fun avgWithSpan(samples: List<Sample>, nowMs: Long, winS: Double): Pair<Double, Double>? {
        val from = nowMs - (winS * 1000).toLong()
        var first: Sample? = null
        var prev: Sample? = null
        var path = 0.0
        for (smp in samples) {
            if (smp.tMs < from) continue
            if (first == null) first = smp
            prev?.let { path += Geo.distanceM(it.pos, smp.pos) }
            prev = smp
        }
        if (first == null || prev == null || prev === first) return null
        val spanS = (prev.tMs - first.tMs) / 1000.0
        if (spanS <= 0) return null
        return path / spanS to spanS
    }

    // ---- status ----

    fun status(s: EngineState, nowMs: Long): Status {
        val age = s.lastFixMs?.let { (nowMs - it) / 1000.0 }
        val eta = s.displayEtaS?.let { e -> s.lastFixMs?.let { max(0.0, e - (nowMs - it) / 1000.0) } }
        return Status(
            legIndex = s.legIndex,
            legCount = s.legs.size,
            legName = s.leg?.name,
            phase = s.phase,
            distanceM = s.lastD,
            etaS = eta,
            vEstKmh = s.lastVest?.let { it * 3.6 },
            speedKmh = s.lastSpeed?.let { it * 3.6 },
            lastFixAgeS = age,
            gpsOk = age != null && age <= cfg.maxFixAgeS,
            alarmAtMs = s.alarmAtMs,
            alarmReason = s.alarmReason,
            ringReason = s.ringReason,
            onLearnedRoute = s.onLearned,
            learnedRides = s.leg?.learned?.rides ?: 0,
            startDistanceM = s.startD,
        )
    }

    companion object {
        private val ACTIVE = setOf(Phase.WAITING, Phase.RIDING, Phase.RINGING, Phase.AWAKE)
        internal fun round1(x: Double): Double = (x * 10).roundToInt() / 10.0
    }
}

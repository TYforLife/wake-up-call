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
    val samples: List<Sample> = emptyList(),
    val shortAvgs: List<ShortAvg> = emptyList(),
    val minD: Double = Double.MAX_VALUE,
    val lastD: Double? = null,
    val lastEtaS: Double? = null,
    val lastEtaBaseMs: Long? = null,
    val lastVest: Double? = null,
    val lastFixMs: Long? = null,
    val headsUpSent: Boolean = false,
    val overshootSent: Boolean = false,
    val slowSinceMs: Long? = null,
    /** After an Ack you are still on the previous bus until about here; this leg ignores fixes until then. */
    val holdUntilMs: Long? = null,
) {
    val leg: Leg? get() = legs.getOrNull(legIndex)
}

/**
 * The deterministic engine: step(state, input) -> (state, output). No clocks, no randomness, no I/O.
 *
 * Every accepted fix schedules min(wakeAt, failsafeAt), clamped to >= now:
 *   v_est      = max(V_FLOOR, avg90, avg15, peak30 over WIN_PEAK)
 *   wakeAt     = fixTime + d / v_est - LEAD
 *   failsafeAt = fixTime + max(DEADMAN_MIN, d / V_MAX - LEAD)
 * Both are measured from the fix time (never later than now), which only makes them earlier.
 */
class Engine(val cfg: Config = Config()) {

    fun step(s: EngineState, input: Input): Pair<EngineState, Output> {
        var headsUp = false
        var overshoot = false
        val next: EngineState = when (input) {
            is Input.Start ->
                if (input.legs.isEmpty()) s.copy(phase = Phase.DONE, alarmAtMs = null)
                else enterLeg(EngineState(legs = input.legs), 0, input.tMs, input.holdUntilMs)

            is Input.FixIn -> {
                val r = onFix(s, input)
                headsUp = r.headsUp
                overshoot = r.overshoot
                r.state
            }

            is Input.Tick -> s
            is Input.Rang -> if (s.phase == Phase.RIDING) s.copy(phase = Phase.RINGING, alarmAtMs = null) else s
            is Input.Ack -> if (s.phase == Phase.RINGING) advance(s, input.tMs, holdUntilMs = remainingArrivalMs(s, input.tMs)) else s
            is Input.SkipLeg -> if (s.phase == Phase.RIDING || s.phase == Phase.RINGING) advance(s, input.tMs) else s
            is Input.Stop -> s.copy(phase = Phase.DONE, alarmAtMs = null)
        }
        val status = status(next, input.tMs)
        val out = Output(
            alarmAtMs = next.alarmAtMs,
            headsUp = headsUp,
            overshoot = overshoot,
            status = status,
            events = listOf(Trace.inputEvent(input), Trace.outEvent(input.tMs, next.alarmAtMs, headsUp, overshoot, status)),
        )
        return next to out
    }

    // ---- legs ----

    private fun advance(s: EngineState, nowMs: Long, holdUntilMs: Long? = null): EngineState =
        if (s.legIndex + 1 < s.legs.size) enterLeg(s, s.legIndex + 1, nowMs, holdUntilMs)
        else s.copy(phase = Phase.DONE, alarmAtMs = null)

    /**
     * Fresh speed windows per leg: a previous bus's speed says nothing about the next one.
     * With [holdUntilMs] (after an Ack) the leg keeps its backup alarm and ignores fixes until you are
     * off the previous bus, so a short next leg cannot ring (and be acked away) while you are still on it.
     */
    private fun enterLeg(s: EngineState, index: Int, nowMs: Long, holdUntilMs: Long? = null): EngineState =
        EngineState(
            phase = Phase.RIDING,
            legs = s.legs,
            legIndex = index,
            alarmAtMs = backupAlarm(s.legs, index, nowMs),
            holdUntilMs = holdUntilMs?.takeIf { it > nowMs },
        )

    /**
     * When the current leg's bus reaches its stop, by the engine's early-biased ETA: the soonest you can be
     * on the next bus. Null when there is no estimate (e.g. a backup ring with no fixes).
     */
    fun remainingArrivalMs(s: EngineState, nowMs: Long): Long? {
        val eta = s.lastEtaS ?: return null
        val base = s.lastEtaBaseMs ?: return null
        val at = base + (eta * 1000).roundToLong()
        return if (at > nowMs) min(at, nowMs + MAX_HOLD_MS) else null
    }

    /**
     * The no-fix backup when entering leg [index] at [nowMs]: its wait plus ride, minus LEAD.
     * For an alarm-off leg, the backup of the next alarm-on leg, so a kill on this leg still protects that one.
     */
    fun backupAlarm(legs: List<Leg>, index: Int, nowMs: Long): Long? {
        var minutes = 0
        for (i in index until legs.size) {
            minutes += legs[i].expectedWaitMin + legs[i].expectedRideMin
            if (legs[i].alarmEnabled) {
                return max(nowMs, nowMs + minutes * 60_000L - (cfg.leadS * 1000).roundToLong())
            }
        }
        return null
    }

    // ---- fixes ----

    private class FixResult(val state: EngineState, val headsUp: Boolean = false, val overshoot: Boolean = false)

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

    private fun onFix(s: EngineState, input: Input.FixIn): FixResult {
        if (s.phase != Phase.RIDING && s.phase != Phase.RINGING) return FixResult(s)
        if (!accepts(s, input)) return FixResult(s)
        val leg = s.leg ?: return FixResult(s)
        val now = input.tMs
        val fix = input.fix
        val ft = fix.fixTimeMs
        val hold = s.holdUntilMs
        if (hold != null) {
            // Still on the previous bus: keep the backup, collect nothing (its speed is not this leg's).
            if (ft < hold) return FixResult(s.copy(lastFixMs = ft, lastD = Geo.distanceM(fix.pos, leg.dest)))
        }

        val keepMs = (max(cfg.winLongS, cfg.peakAvgS) * 1000).toLong()
        val samples = ((if (hold != null) emptyList() else s.samples) + Sample(ft, fix.pos)).filter { it.tMs >= ft - keepMs }
        val d = Geo.distanceM(fix.pos, leg.dest)
        val minD = min(s.minD, d)
        val passed = minD < cfg.overshootNearM && d > minD + cfg.overshootGrowM

        val long = avgSpeed(samples, ft, cfg.winLongS, cfg.minSpanS)
        val short = avgSpeed(samples, ft, cfg.winShortS, cfg.minSpanS)
        val peakSample = avgWithSpan(samples, ft, cfg.peakAvgS)
        val shortAvgs = (if (cfg.winPeakS > 0 && peakSample != null && peakSample.second >= cfg.peakAvgS * cfg.peakMinSpanFrac)
            s.shortAvgs + ShortAvg(ft, peakSample.first) else s.shortAvgs)
            .filter { it.tMs >= ft - (cfg.winPeakS * 1000).toLong() }
        val peak = shortAvgs.maxOfOrNull { it.mps }
        val vEst = maxOf(cfg.vFloor, long ?: 0.0, short ?: 0.0, peak ?: 0.0)
        val etaS = d / vEst

        var st = s.copy(
            holdUntilMs = null,
            samples = samples, shortAvgs = shortAvgs, minD = minD, lastD = d, lastEtaS = etaS,
            lastEtaBaseMs = ft, lastVest = vEst, lastFixMs = ft,
        )

        if (s.phase == Phase.RINGING) {
            if (passed && !s.overshootSent) return FixResult(st.copy(overshootSent = true), overshoot = true)
            return FixResult(st)
        }

        if (!leg.alarmEnabled) return FixResult(trackAlarmOffLeg(st, samples, ft, d, now))

        val leadMs = cfg.leadS * 1000
        val wakeAt = ft + (etaS * 1000 - leadMs)
        val failsafeAt = ft + max(cfg.deadmanMinS * 1000, d / cfg.vMax * 1000 - leadMs)
        var alarm = min(wakeAt, failsafeAt).roundToLong()
        // No speed information yet (start of leg, or back from a dropout): a fix may pull the alarm earlier, never later.
        val current = s.alarmAtMs
        if (long == null && short == null && current != null) alarm = min(alarm, current)
        // Passed the stop without ringing (e.g. a mis-pasted destination): ring now.
        if (passed) alarm = now
        alarm = max(alarm, now)
        st = st.copy(alarmAtMs = alarm)

        if (!s.headsUpSent && etaS <= cfg.headsUpS) {
            st = st.copy(headsUpSent = true)
            return FixResult(st, headsUp = true)
        }
        return FixResult(st)
    }

    /** Alarm-off legs: tracked, never their own alarm; advance after dwelling slowly near the stop. */
    private fun trackAlarmOffLeg(st: EngineState, samples: List<Sample>, ft: Long, d: Double, now: Long): EngineState {
        val slow = (displacementSpeed(samples, ft, 10.0) ?: Double.MAX_VALUE) < cfg.arriveSlowKmh / 3.6
        if (d < cfg.arriveRadiusM && slow) {
            val since = st.slowSinceMs ?: ft
            if (ft - since >= cfg.arriveDwellS * 1000) return advance(st, now)
            return st.copy(slowSinceMs = since)
        }
        return st.copy(slowSinceMs = null)
    }

    /** Path length ÷ time over the samples in the trailing window, or null if they span less than [minSpanS]. */
    private fun avgSpeed(samples: List<Sample>, nowMs: Long, winS: Double, minSpanS: Double): Double? {
        val r = avgWithSpan(samples, nowMs, winS) ?: return null
        return if (r.second >= minSpanS) r.first else null
    }

    /** Start-to-end displacement ÷ time: robust to GPS jitter when standing still. */
    private fun displacementSpeed(samples: List<Sample>, nowMs: Long, winS: Double): Double? {
        val w = samples.filter { it.tMs >= nowMs - (winS * 1000).toLong() }
        if (w.size < 2) return null
        val spanS = (w.last().tMs - w.first().tMs) / 1000.0
        if (spanS < cfg.minSpanS) return null
        return Geo.distanceM(w.first().pos, w.last().pos) / spanS
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
        val eta = if (s.lastEtaS != null && s.lastEtaBaseMs != null)
            max(0.0, s.lastEtaS - (nowMs - s.lastEtaBaseMs) / 1000.0) else null
        return Status(
            legIndex = s.legIndex,
            legCount = s.legs.size,
            legName = s.leg?.name,
            phase = s.phase,
            distanceM = s.lastD,
            etaS = eta,
            vEstKmh = s.lastVest?.let { it * 3.6 },
            lastFixAgeS = age,
            gpsOk = age != null && age <= cfg.maxFixAgeS,
            alarmAtMs = s.alarmAtMs,
            holdUntilMs = s.holdUntilMs,
        )
    }

    companion object {
        /** Longest the next leg waits for you to get off the previous bus. */
        const val MAX_HOLD_MS = 20 * 60_000L

        internal fun round1(x: Double): Double = (x * 10).roundToInt() / 10.0
    }
}

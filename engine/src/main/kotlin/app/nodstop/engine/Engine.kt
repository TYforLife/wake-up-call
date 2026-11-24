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
    /** (fix time, typical past time left from that point) while on a learned route: today's pace. */
    val paceSamples: List<Pair<Long, Double>> = emptyList(),
    /** Today's pace vs past rides (1.2 = 20% slower), or null. */
    val pace: Double? = null,
    val lastVest: Double? = null,
    /** Whole-ride speed since boarding, exponentially weighted (recent minutes count most), and its time. */
    val tripV: Double? = null,
    val tripVMs: Long? = null,
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
    /** Waiting for the first fix to pick which leg you're on (Start with autoLeg). */
    val pickLeg: Boolean = false,
    /** Stops the pick skipped (leg index, distance at the pick), watched in case one was still ahead. */
    val skipWatch: List<Pair<Int, Double>> = emptyList(),
    /** How many stops the pick at Start skipped to reach this leg (shown, so a skip is never silent). */
    val pickedPast: Int = 0,
    /** Since when you've been in the stop's circle at walking pace, after "I'm awake" (quick get-off). */
    val quickSinceMs: Long? = null,
    /** The stop you just left by a quick get-off, watched in case you were still on the bus. */
    val watchStop: LatLng? = null,
    val watchUntilMs: Long? = null,
    val watchMinD: Double = Double.MAX_VALUE,
    /** The OVERSHOOT ring now ringing is for [watchStop] (the previous leg), not this one. */
    val prevStopRing: Boolean = false,
    /** Urgent rings so far this trip (at most MAX_URGENT_RINGS). */
    val urgentRings: Int = 0,
    /** While an URGENT alarm is pending or ringing: the alarm it would otherwise have, restored after the ack. */
    val beforeUrgentAtMs: Long? = null,
    val beforeUrgentReason: AlarmReason? = null,
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
    private val reringMs = (cfg.reringS * 1000).roundToLong()

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
                    .let { st -> if (input.autoLeg && input.legs.size > 1 && !input.awakeOnFirst) st.copy(pickLeg = true) else st }

            is Input.FixIn -> keepUrgent(s, onFix(s, input, e))
            is Input.Tick -> s
            is Input.Urgent -> onUrgent(s, input.tMs)
            is Input.UpdateLegs -> updateLegs(s, input)
            // While ringing, the alarm re-arms itself RERING ahead (DESIGN §2): if anything kills the ring before
            // "I'm awake", the system rings again. Each re-ring pushes it on; the ack replaces it.
            is Input.Rang -> when (s.phase) {
                Phase.WAITING, Phase.RIDING, Phase.AWAKE ->
                    s.copy(phase = Phase.RINGING, resumePhase = s.phase, ringReason = s.alarmReason,
                        alarmAtMs = input.tMs + reringMs, alarmReason = s.alarmReason)
                Phase.RINGING -> s.copy(alarmAtMs = input.tMs + reringMs, alarmReason = s.ringReason)
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

    /**
     * What this ride teaches its stop: the way there, from after the previous stop of the trip (DESIGN §2.3). A ride
     * that started before it (the previous leg skipped) must not teach this stop the way past it.
     */
    private fun learn(st: EngineState, trail: List<Sample>): LearnedUpdate? {
        val leg = st.leg ?: return null
        val prev = st.legs.getOrNull(st.legIndex - 1)?.stop
        return LearnedUpdate(leg.dest, Learning.buildTrack(Learning.after(trail, prev, cfg.pickAtStopM), leg.dest))
    }

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
            urgentRings = s.urgentRings,
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

    private fun failsafeAt(ftMs: Long, straightM: Double, leadS: Double = cfg.leadS): Long =
        ftMs + (max(cfg.deadmanMinS, straightM / cfg.vMax - leadS) * 1000).roundToLong()

    /** This stop's lead: tuned by your too-early / too-late ratings, within 30-90 s; else LEAD. */
    fun leadFor(leg: Leg): Double = leg.learned?.leadS?.coerceIn(cfg.minLeadS, cfg.maxLeadS) ?: cfg.leadS

    /**
     * The lead the stop's own timing rings with (DESIGN §2.6, owner 2026-11-24: "the alarm woke me outside the ring").
     * With your own circle the circle is your margin: it rings when the bus enters it, and the stop's timing only
     * steps in at the shortest lead the invariant holds for ([Config.minLeadS], 30 s), for a circle so small the bus
     * would be closer than that at its edge. Without one: [leadFor].
     */
    fun wakeLead(leg: Leg): Double = if (leg.wakeRadiusM != null) cfg.minLeadS else leadFor(leg)

    // ---- ack ----

    private fun onAck(s: EngineState, nowMs: Long): EngineState {
        val back = s.resumePhase ?: Phase.RIDING
        return when (s.ringReason) {
            // Rode past the stop you'd just left (quick get-off was wrong): you're on this leg still, get off now.
            AlarmReason.OVERSHOOT -> if (s.prevStopRing) rearm(s.copy(phase = back, ackMs = nowMs, prevStopRing = false), nowMs)
            // You know you passed it: this leg is over.
            else advance(s, nowMs)
            // A safety ring for something further on: carry on as you were.
            AlarmReason.NO_GPS, AlarmReason.NEXT_LEG -> rearm(s.copy(phase = back, ackMs = nowMs), nowMs)
            // An urgent message: never "awake for this stop" (you may doze off again). Carry on exactly as before.
            AlarmReason.URGENT -> afterUrgent(s.copy(phase = back), nowMs)
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

    // ---- route edits during a trip (PLAN U3) ----

    private fun updateLegs(s: EngineState, input: Input.UpdateLegs): EngineState {
        if (s.phase !in ACTIVE) return s
        val merged = s.legs.mapIndexed { i, old ->
            input.legs.getOrNull(i)?.takeIf { it.dest == old.dest }?.let { old.copy(wakeRadiusM = it.wakeRadiusM) } ?: old
        }
        val st = s.copy(legs = merged)
        val leg = st.leg ?: return st
        val pos = st.lastPos
        val zone = leg.wakeRadiusM
        val inside = zone != null && pos != null && Geo.distanceM(pos, leg.stop) <= zone
        val due = st.alarmAtMs?.let { it <= input.tMs } == true
        val onBus = st.boarded || (st.lastSpeed ?: 0.0) >= cfg.boardKmh / 3.6
        return if (st.phase in setOf(Phase.WAITING, Phase.RIDING) && leg.alarmEnabled && onBus && inside && !due) {
            st.copy(alarmAtMs = input.tMs, alarmReason = AlarmReason.WAKE)
        } else st
    }

    /** Ends this leg after a quick get-off and starts watching the stop just left. */
    private fun quickAdvance(st: EngineState, nowMs: Long, ft: Long, stop: LatLng): EngineState =
        advance(st, nowMs).let { n ->
            if (n.phase == Phase.DONE) n
            else n.copy(watchStop = stop, watchUntilMs = ft + (cfg.prevStopWatchS * 1000).roundToLong(), watchMinD = Double.MAX_VALUE)
        }

    // ---- urgent (While You Slept, DESIGN §10) ----

    /** Ring now for an urgent message, while there is a trip to protect and urgent rings are left. */
    private fun onUrgent(s: EngineState, nowMs: Long): EngineState {
        if (s.phase !in URGENT_PHASES || s.urgentRings >= cfg.maxUrgentRings) return s
        val current = s.alarmAtMs
        // Already due anyway: that ring comes first and says why.
        if (current != null && current <= nowMs) return s
        return s.copy(
            alarmAtMs = nowMs, alarmReason = AlarmReason.URGENT, urgentRings = s.urgentRings + 1,
            beforeUrgentAtMs = current, beforeUrgentReason = s.alarmReason,
        )
    }

    /**
     * A fix (or a leg change) must not push a pending urgent ring later. The schedule the fix computed is
     * kept aside, so the ack can restore it.
     */
    private fun keepUrgent(prev: EngineState, next: EngineState): EngineState {
        val urgentAt = prev.alarmAtMs.takeIf { prev.alarmReason == AlarmReason.URGENT } ?: return next
        if (next.phase !in URGENT_PHASES) return next
        val computed = next.alarmAtMs
        if (computed != null && computed <= urgentAt) return next.copy(beforeUrgentAtMs = null, beforeUrgentReason = null)
        return next.copy(
            alarmAtMs = urgentAt, alarmReason = AlarmReason.URGENT,
            beforeUrgentAtMs = computed, beforeUrgentReason = next.alarmReason,
        )
    }

    /**
     * After "I'm awake" on an urgent ring: the schedule as if it never happened, from the newest fix, with
     * no REARM delay; never later than the alarm it replaced (W1).
     */
    private fun afterUrgent(st0: EngineState, nowMs: Long): EngineState {
        val st = st0.copy(beforeUrgentAtMs = null, beforeUrgentReason = null)
        var at = st0.beforeUrgentAtMs
        var reason = st0.beforeUrgentReason
        val target = alarmTarget(st)
        val pos = st.lastPos
        val ft = st.lastFixMs
        if (target != null && pos != null && ft != null) {
            val leg = st.legs[target]
            val ownLeg = target == st.legIndex && st.phase != Phase.AWAKE
            val lead = wakeLead(leg)
            var fresh = failsafeAt(ft, Geo.distanceM(pos, leg.stop), if (ownLeg) maxOf(cfg.leadS, lead) else cfg.leadS)
            var freshReason = if (ownLeg) AlarmReason.FAILSAFE else AlarmReason.NEXT_LEG
            val eta = st.lastEtaS
            val base = st.lastEtaBaseMs
            if (ownLeg && eta != null && base != null) {
                val wake = base + ((eta - lead) * 1000).roundToLong()
                if (wake <= fresh) { fresh = wake; freshReason = AlarmReason.WAKE }
            }
            if (at == null || fresh < at) { at = fresh; reason = freshReason }
        }
        if (at == null) return withAlarm(st, null)
        return withAlarm(st, max(at, nowMs) to (reason ?: AlarmReason.FAILSAFE))
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

    private fun onFix(s0: EngineState, input: Input.FixIn, e: Edges): EngineState {
        if (s0.phase !in ACTIVE) return s0
        if (!accepts(s0, input)) return s0
        // Started mid-trip (e.g. already on the second bus): jump to the leg you're actually on.
        val p = input.fix.pos
        val s = when {
            s0.pickLeg -> {
                val k = LegPicker.pick(s0.legs, p, cfg)
                if (k == s0.legIndex) s0.copy(pickLeg = false)
                else {
                    // Skipped stops far from here are watched: a bus coming up to one means it was ahead.
                    val watch = (0 until k).map { it to Geo.distanceM(p, s0.legs[it].stop) }.filter { it.second > cfg.pickAtStopM }
                    enterLeg(s0.copy(lastPos = p), k, input.tMs)
                        .copy(pickLeg = false, lastFixMs = s0.lastFixMs, skipWatch = watch, pickedPast = k - s0.legIndex)
                }
            }
            s0.skipWatch.isNotEmpty() -> {
                // The earliest skipped stop you're now coming up to: that leg after all, ringing now (it's close, and
                // this leg's speed is unknown so far: rule 4). With no speed yet, the next fixes can't push it later.
                // Further out on a fast bus: at least the lead (plus a margin) away at its current speed.
                val reach = max(cfg.skipWatchM, (s0.lastSpeed ?: 0.0) * (cfg.leadS + 15.0))
                val back = s0.skipWatch.firstOrNull { (j, d0) ->
                    val d = Geo.distanceM(p, s0.legs[j].stop)
                    d <= reach && d <= d0 - cfg.skipApproachM
                }
                if (back == null || !s0.legs[back.first].alarmEnabled) s0
                else enterLeg(s0.copy(lastPos = p), back.first, input.tMs)
                    .copy(lastFixMs = s0.lastFixMs, alarmAtMs = input.tMs, alarmReason = AlarmReason.WAKE)
            }
            else -> s0
        }
        val leg = s.leg ?: return s
        val now = input.tMs
        val ft = input.fix.fixTimeMs
        val pos = input.fix.pos

        val keepMs = (maxOf(cfg.winShortS, cfg.boardWindowS, cfg.peakAvgS, cfg.etaSpeedWinS) * 1000 + 1_000).toLong()
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
        // Short minimum span so starting the trip while already on a moving bus counts within ~8 s.
        val board = displacementSpeed(samples, ft, cfg.boardWindowS, minSpanS = cfg.boardMinSpanS)

        val match = Learning.match(leg.learned, pos, cfg.matchRadiusM)
        val dEff = match?.let { max(straight, it.toEndM * cfg.routeFactor) } ?: straight
        // Today's pace on a learned route: how long the last few minutes took vs past rides over the same stretch.
        val paceSamples = match?.typicalToEndS?.let { typ ->
            (s.paceSamples + (ft to typ)).filter { it.first >= ft - (cfg.paceWinS * 1000).toLong() }
        } ?: emptyList()
        val pace = Eta.pace(paceSamples, cfg) ?: s.pace.takeIf { match != null }
        // Learned time left from here (DESIGN §2.5): the fastest past ride, or past rides' typical time at
        // today's pace (minus a safety margin) if that's sooner. Either way, only ever earlier than before.
        val learnedTime = Eta.learnedTimeLeft(match?.fastestToEndS, match?.typicalToEndS, pace, cfg)
        val learnedV = learnedTime?.takeIf { it >= 1.0 }?.let { dEff / it }
        // On a learned route, past rides are a better guide than the fixed floor (city streets under 25 km/h).
        val floor = if (learnedV != null) cfg.vFloor * cfg.learnedFloorFrac else cfg.vFloor
        // Whole-ride speed (PLAN U4): a bus that slows for a few lights near the end still counts as the
        // speed it's been doing. Only after boarding (walking to the stop says nothing about the bus).
        val tripVUpdated = cfg.tripTauS > 0 && s.boarded && short != null
        val tripV = when {
            cfg.tripTauS <= 0 -> null
            tripVUpdated && short != null -> Eta.ewma(s.tripV, s.tripVMs, short, ft, cfg.tripTauS)
            else -> s.tripV
        }
        val tripVMs = if (tripVUpdated) ft else s.tripVMs
        // Off a learned route it joins the max (can only ring earlier); on one, past rides know the slow end better.
        // Never faster than a bus can go (owner, 2026-11-24: woken ~4 km out). A past ride with bad timing made the
        // speed impossible and the time left nothing. Capping at V_MAX keeps §2's invariant: its precondition is
        // that the bus never exceeds V_MAX, which the failsafe already assumes.
        val vEst = minOf(cfg.vMax, maxOf(floor, short ?: 0.0, learnedV ?: maxOf(peak ?: 0.0, tripV ?: 0.0)))
        val etaS = dEff / vEst
        val recentV = avgSpeed(samples, ft, cfg.etaSpeedWinS, cfg.minSpanS)
        val displayEta = Eta.display(match?.typicalToEndS, pace, dEff, tripV ?: recentV, s.phase == Phase.WAITING && !s.boarded)

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
            lastVest = vEst, lastSpeed = short, tripV = tripV, tripVMs = tripVMs, lastFixMs = ft, lastPos = pos, onLearned = match != null,
            displayEtaS = displayEta, dwellSinceMs = dwellSince, dwellStartPos = dwellPos,
            paceSamples = paceSamples, pace = pace,
        )
        // The alarm's ETA only means something for the leg you might sleep on.
        if (leg.alarmEnabled) st = st.copy(lastEtaS = etaS, lastEtaBaseMs = ft)

        // Watching the stop you just left by a quick get-off: carried on past it at bus speed? Ring now.
        val ws = s.watchStop
        val wu = s.watchUntilMs
        if (ws != null && wu != null) {
            if (ft > wu) {
                st = st.copy(watchStop = null, watchUntilMs = null, watchMinD = Double.MAX_VALUE)
            } else {
                val dw = Geo.distanceM(pos, ws)
                val wMin = min(s.watchMinD, dw)
                val rodePast = wMin < cfg.overshootNearM && dw > wMin + cfg.overshootGrowM && (short ?: 0.0) >= cfg.boardKmh / 3.6
                if (rodePast && phase in setOf(Phase.WAITING, Phase.RIDING, Phase.AWAKE)) {
                    return st.copy(
                        watchStop = null, watchUntilMs = null, watchMinD = Double.MAX_VALUE,
                        alarmAtMs = now, alarmReason = AlarmReason.OVERSHOOT, prevStopRing = true,
                    )
                }
                st = st.copy(watchMinD = wMin)
            }
        }

        val passed = minD < cfg.overshootNearM && straight > minD + cfg.overshootGrowM && (short ?: 0.0) >= cfg.boardKmh / 3.6

        if (phase == Phase.RINGING) {
            if (passed && !s.overshootSent) {
                e.overshoot = true
                return st.copy(overshootSent = true)
            }
            return st
        }

        val watchingForGetOff = phase == Phase.AWAKE || !leg.alarmEnabled
        // Alarm-off leg and the bus carried on past its stop with you on it: you're on the next leg now.
        if (!leg.alarmEnabled && passed && boarded) {
            val nextLeg = advance(st, now)
            return if (nextLeg.phase == Phase.WAITING) nextLeg.copy(phase = Phase.RIDING, boarded = true) else nextLeg
        }
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
                // Learned only if this ride really reached your pin (PLAN U4): not where you got off or stopped.
                if (boarded && minD <= cfg.learnPassM) e.learned = learn(st, trail)
                return advance(st, now)
            }
            // Quick get-off (PLAN U3): awake, in the stop's circle, at walking pace for a few seconds.
            if (phase == Phase.AWAKE) {
                val radius = min(leg.wakeRadiusM ?: cfg.quickAlightM, cfg.quickAlightMaxM)
                val walking = nearStop <= radius && slow10 != null && slow10 < cfg.alightSlowKmh / 3.6
                val since = if (walking) s.quickSinceMs ?: ft else null
                if (since != null && ft - since >= cfg.quickAlightS * 1000) {
                    if (boarded && minD <= cfg.learnPassM) e.learned = learn(st, trail)
                    return quickAdvance(st.copy(quickSinceMs = null), now, ft, leg.stop)
                }
                st = st.copy(quickSinceMs = since)
            }
            return scheduleNextLeg(st, now, ft, pos)
        }

        // WAITING or RIDING on an alarm-on leg.
        // Per-stop lead, tuned by your ratings (30-90 s); the failsafe never uses less than the default.
        val lead = wakeLead(leg)
        val wakeAt = ft + ((etaS - lead) * 1000).roundToLong()
        val failsafe = failsafeAt(ft, straight, maxOf(cfg.leadS, lead))
        var alarm = min(wakeAt, failsafe)
        var reason = if (wakeAt <= failsafe) AlarmReason.WAKE else AlarmReason.FAILSAFE
        // No speed information yet (start of a leg, or back from a dropout): a fix may pull the alarm earlier, never later.
        val current = s.alarmAtMs
        if (short == null && current != null && current < alarm) { alarm = current; reason = s.alarmReason ?: reason }
        // Your own wake zone (DESIGN §2.6): it rings when a fix shows the bus in it (below), never from an estimate of
        // when it will be (owner, 2026-11-24: "don't wake me up early"; an estimate rang ~4 km out). Only the dead-man's
        // switch aims at its edge, at V_MAX: while fixes come it's pushed on; if they stop, it rings before the edge.
        val zone = leg.wakeRadiusM
        // From outside it only: waiting at a stop that's already inside your circle must not ring before the bus.
        if (zone != null && straight > zone) {
            val edgeFailsafe = failsafeAt(ft, max(0.0, straight - zone), 0.0)
            if (edgeFailsafe < alarm) { alarm = edgeFailsafe; reason = AlarmReason.FAILSAFE }
        }
        // Inside it on the bus, ring now. "On the bus" = boarding detected, or bus speed right now (a trip started
        // on a crawling bus may never have detected boarding).
        if (zone != null && straight <= zone && (boarded || (short ?: 0.0) >= cfg.boardKmh / 3.6)) { alarm = now; reason = AlarmReason.WAKE }
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
            position = s.lastPos,
            pace = s.pace,
            leadS = s.leg?.let { leadFor(it) },
            pickedPast = s.pickedPast,
        )
    }

    companion object {
        private val ACTIVE = setOf(Phase.WAITING, Phase.RIDING, Phase.RINGING, Phase.AWAKE)
        private val URGENT_PHASES = setOf(Phase.WAITING, Phase.RIDING, Phase.AWAKE)
        internal fun round1(x: Double): Double = (x * 10).roundToInt() / 10.0
    }
}

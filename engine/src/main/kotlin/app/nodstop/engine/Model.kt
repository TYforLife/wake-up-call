package app.nodstop.engine

import kotlinx.serialization.Serializable

@Serializable
data class LatLng(val lat: Double, val lng: Double)

@Serializable
data class Leg(
    val name: String,
    val dest: LatLng,
    /** Use the fastest usual ride: the no-fix backup alarm is based on it. */
    val expectedRideMin: Int,
    /** Wait BEFORE this leg (e.g. 8 for the transfer). Counted in this leg's backup alarm. */
    val expectedWaitMin: Int = 0,
    val alarmEnabled: Boolean = true,
)

/** A location fix. [fixTimeMs] is when the position was measured, on the same clock as [tMs]. */
data class Fix(val tMs: Long, val fixTimeMs: Long, val pos: LatLng, val accuracyM: Float)

sealed interface Input {
    val tMs: Long

    /**
     * Start (or resume) a trip. [holdUntilMs]: until then the first leg keeps its backup alarm and ignores fixes,
     * because you are still on the previous bus (used when resuming after a cold-start ring).
     */
    data class Start(override val tMs: Long, val legs: List<Leg>, val holdUntilMs: Long? = null) : Input
    data class FixIn(override val tMs: Long, val fix: Fix) : Input
    data class Tick(override val tMs: Long) : Input
    data class Rang(override val tMs: Long) : Input
    data class Ack(override val tMs: Long) : Input
    data class SkipLeg(override val tMs: Long) : Input
    data class Stop(override val tMs: Long) : Input
}

enum class Phase { IDLE, RIDING, RINGING, DONE }

data class Status(
    val legIndex: Int,
    val legCount: Int,
    val legName: String?,
    val phase: Phase,
    val distanceM: Double?,
    val etaS: Double?,
    val vEstKmh: Double?,
    val lastFixAgeS: Double?,
    val gpsOk: Boolean,
    val alarmAtMs: Long?,
    /** Still on the previous bus: this leg starts tracking at this time. */
    val holdUntilMs: Long?,
)

data class Output(
    /** The ONE system alarm; null = cancel. The shell reschedules only if it changed by more than 1 s. */
    val alarmAtMs: Long?,
    /** Edge-triggered, once per leg. */
    val headsUp: Boolean,
    /** Edge-triggered while RINGING: maximum escalation and "You passed your stop". */
    val overshoot: Boolean,
    val status: Status,
    /** The input and the output summary, ready for the trace file. */
    val events: List<TraceEvent>,
)

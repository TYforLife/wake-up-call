package app.nodstop.engine

import kotlinx.serialization.Serializable

@Serializable
data class LatLng(val lat: Double, val lng: Double)

/** A point on a learned ride, with what was left from there to the stop on that ride. */
@Serializable
data class TrackPoint(val lat: Double, val lng: Double, val toEndM: Double, val toEndS: Double) {
    val pos: LatLng get() = LatLng(lat, lng)
}

/** One past ride to a stop, from boarding to where you got off. Points are about 25 m apart. */
@Serializable
data class Track(val points: List<TrackPoint>)

/** What the app has learned about a stop from past rides: the way there, and your ratings. */
@Serializable
data class Learned(
    /**
     * Your pin. (Before PLAN U4 this was a "suggested stop" averaged from where you got off; where a trip
     * ends says nothing reliable about the stop, so it's no longer derived or shown.) Never used for the alarm.
     */
    val stop: LatLng,
    /** Most recent rides first. */
    val tracks: List<Track> = emptyList(),
    val rides: Int = 0,
    /** This stop's lead in seconds, tuned by your ratings (too early: later; too late: earlier). Null = default. */
    val leadS: Double? = null,
)

/** A leg is just "where you get off". No times: everything else comes from GPS. */
@Serializable
data class Leg(
    val name: String,
    val dest: LatLng,
    val alarmEnabled: Boolean = true,
    val learned: Learned? = null,
    /** Your own wake zone: once on the bus and within this many metres of the stop, ring (if not already). */
    val wakeRadiusM: Double? = null,
    /** What you ride to this stop (BACKLOG F29): its top speed bounds the failsafe (DESIGN §2). */
    val mode: Mode = Mode.BUS,
) {
    /** The stop the engine aims for: always your pin. */
    val stop: LatLng get() = dest
}

/** What a leg is ridden on (BACKLOG F29). A train is faster than a bus, so its failsafe must assume more. */
@Serializable
enum class Mode { BUS, TRAIN, FERRY }

/** A location fix. [fixTimeMs] is when the position was measured, on the same clock as [tMs]. */
data class Fix(val tMs: Long, val fixTimeMs: Long, val pos: LatLng, val accuracyM: Float)

sealed interface Input {
    val tMs: Long

    /**
     * Start (or resume) a trip. [awakeOnFirst]: resume after "I'm awake" on a ring the trip service
     * didn't see (cold start): the first leg starts already acked. [lastKnown]: the phone's last known
     * position (any age up to NO_FIX): arms the failsafe before the first fresh fix.
     */
    data class Start(
        override val tMs: Long,
        val legs: List<Leg>,
        val awakeOnFirst: Boolean = false,
        val lastKnown: Fix? = null,
        /** Pick the leg you're actually on from the first fix (you may have started mid-trip, on the bus). */
        val autoLeg: Boolean = false,
    ) : Input
    data class FixIn(override val tMs: Long, val fix: Fix) : Input
    data class Tick(override val tMs: Long) : Input
    data class Rang(override val tMs: Long) : Input
    data class Ack(override val tMs: Long) : Input
    data class SkipLeg(override val tMs: Long) : Input
    data class Stop(override val tMs: Long) : Input
    /**
     * While You Slept (DESIGN §10): a message from someone you marked important looks urgent.
     * Rings now. It can only add a ring, never delay or remove one (invariant W1).
     */
    data class Urgent(override val tMs: Long) : Input
    /**
     * The route was edited during the trip (PLAN U3). Only wake circles are taken, and only for legs whose
     * stop is unchanged; a circle you're already inside rings now. It can only make an alarm earlier.
     */
    data class UpdateLegs(override val tMs: Long, val legs: List<Leg>) : Input
}

/**
 * WAITING: at the stop, not on a bus yet. RIDING: on the bus. RINGING: the alarm is ringing.
 * AWAKE: you held "I'm awake"; watching for you to get off (or ride past the stop).
 */
enum class Phase { IDLE, WAITING, RIDING, RINGING, AWAKE, DONE }

/** Why the currently scheduled alarm would ring. */
enum class AlarmReason {
    /** Your stop is about 45 s away. */
    WAKE,
    /** GPS updates stopped: ringing as late as is still safe. */
    FAILSAFE,
    /** No GPS fix at all since the leg started. */
    NO_GPS,
    /** You rode past your stop. */
    OVERSHOOT,
    /** Safety alarm for the next leg while you're still on this bus. */
    NEXT_LEG,
    /** An urgent message from someone you marked important (While You Slept, DESIGN §10). */
    URGENT,
}

data class Status(
    val legIndex: Int,
    val legCount: Int,
    val legName: String?,
    val phase: Phase,
    /** Distance to the stop: along the learned route when matched, else straight line. */
    val distanceM: Double?,
    /** A realistic ETA for display: past rides' time from here × today's pace, else recent speed. */
    val etaS: Double?,
    val vEstKmh: Double?,
    val speedKmh: Double?,
    val lastFixAgeS: Double?,
    val gpsOk: Boolean,
    val alarmAtMs: Long?,
    val alarmReason: AlarmReason?,
    /** The reason of the alarm that last rang (shown on the alarm screen). */
    val ringReason: AlarmReason?,
    /** Following a learned route from past rides. */
    val onLearnedRoute: Boolean,
    val learnedRides: Int,
    /** Distance when you boarded, for a progress bar. */
    val startDistanceM: Double?,
    /** Your last accepted position (display only, e.g. the map). */
    val position: LatLng? = null,
    /** Today's pace vs past rides on this route (1.2 = 20% slower), when known. */
    val pace: Double? = null,
    /** How many seconds before this stop it aims to ring (per stop, tuned by your ratings). */
    val leadS: Double? = null,
    /** Stops skipped by the pick at Start to reach this leg; 0 once you're past it or on a later leg. */
    val pickedPast: Int = 0,
)

data class Output(
    /** The ONE system alarm; null = cancel. The shell sets it only through [SystemAlarm.update]. */
    val alarmAtMs: Long?,
    /** Edge-triggered while RINGING: maximum escalation and "You passed your stop". */
    val overshoot: Boolean,
    /** Edge-triggered once per leg: you're at the stop now (a light nudge). */
    val arrived: Boolean,
    /** Edge-triggered: boarding detected. */
    val boarded: Boolean,
    /** On a completed leg: what was learned, to merge into the app's store for this leg's stop. */
    val learned: LearnedUpdate?,
    val status: Status,
    /** The input and the output summary, ready for the trace file. */
    val events: List<TraceEvent>,
)

/** A new ride to merge for the leg whose pasted stop is [dest]. */
data class LearnedUpdate(val dest: LatLng, val track: Track?)

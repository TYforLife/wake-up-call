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

/** What the app has learned about a stop from past rides: the way there, and a suggested stop position. */
@Serializable
data class Learned(
    /**
     * A SUGGESTION only: roughly where you got off on past rides (from GPS, so it varies with where you
     * stood or walked). Never used for the alarm: your pin is the stop. Shown faintly, with "Use suggestion".
     */
    val stop: LatLng,
    /** Most recent rides first. */
    val tracks: List<Track> = emptyList(),
    val rides: Int = 0,
)

/** A leg is just "where you get off". No times: everything else comes from GPS. */
@Serializable
data class Leg(
    val name: String,
    val dest: LatLng,
    val alarmEnabled: Boolean = true,
    val learned: Learned? = null,
) {
    /** The stop the engine aims for: always your pin. */
    val stop: LatLng get() = dest
}

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
    ) : Input
    data class FixIn(override val tMs: Long, val fix: Fix) : Input
    data class Tick(override val tMs: Long) : Input
    data class Rang(override val tMs: Long) : Input
    data class Ack(override val tMs: Long) : Input
    data class SkipLeg(override val tMs: Long) : Input
    data class Stop(override val tMs: Long) : Input
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
}

data class Status(
    val legIndex: Int,
    val legCount: Int,
    val legName: String?,
    val phase: Phase,
    /** Distance to the stop: along the learned route when matched, else straight line. */
    val distanceM: Double?,
    /** A realistic ETA for display (current speed, or past rides). Not what the alarm uses. */
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
)

data class Output(
    /** The ONE system alarm; null = cancel. The shell reschedules only if it changed by more than 1 s. */
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
data class LearnedUpdate(val dest: LatLng, val stop: LatLng, val track: Track?)

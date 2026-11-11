package app.nodstop.engine

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One JSON Lines record, shared by the app's trip logger and the replay test.
 * Kinds: start, fix, tick, rang, ack, skip, stop (inputs); out (engine output summary);
 * rate and note (app annotations, ignored by replay).
 */
@Serializable
data class TraceEvent(
    val t: Long,
    val k: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val acc: Float? = null,
    val ft: Long? = null,
    val legs: List<Leg>? = null,
    val hold: Long? = null,
    val alarmAt: Long? = null,
    val headsUp: Boolean? = null,
    val overshoot: Boolean? = null,
    val d: Int? = null,
    val eta: Int? = null,
    val v: Double? = null,
    val leg: Int? = null,
    val ph: String? = null,
    /** For "rate": too_early | good | too_late. For "note": free text. */
    val msg: String? = null,
)

object Trace {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(e: TraceEvent): String = json.encodeToString(TraceEvent.serializer(), e)
    fun decode(line: String): TraceEvent = json.decodeFromString(TraceEvent.serializer(), line)

    fun inputEvent(input: Input): TraceEvent = when (input) {
        is Input.Start -> TraceEvent(input.tMs, "start", legs = input.legs, hold = input.holdUntilMs)
        is Input.FixIn -> TraceEvent(
            input.tMs, "fix", lat = input.fix.pos.lat, lng = input.fix.pos.lng,
            acc = input.fix.accuracyM, ft = input.fix.fixTimeMs,
        )
        is Input.Tick -> TraceEvent(input.tMs, "tick")
        is Input.Rang -> TraceEvent(input.tMs, "rang")
        is Input.Ack -> TraceEvent(input.tMs, "ack")
        is Input.SkipLeg -> TraceEvent(input.tMs, "skip")
        is Input.Stop -> TraceEvent(input.tMs, "stop")
    }

    fun toInput(e: TraceEvent): Input? = when (e.k) {
        "start" -> Input.Start(e.t, e.legs ?: emptyList(), e.hold)
        "fix" -> Input.FixIn(e.t, Fix(e.t, e.ft ?: e.t, LatLng(e.lat ?: 0.0, e.lng ?: 0.0), e.acc ?: Float.NaN))
        "tick" -> Input.Tick(e.t)
        "rang" -> Input.Rang(e.t)
        "ack" -> Input.Ack(e.t)
        "skip" -> Input.SkipLeg(e.t)
        "stop" -> Input.Stop(e.t)
        else -> null
    }

    fun outEvent(tMs: Long, alarmAt: Long?, headsUp: Boolean, overshoot: Boolean, s: Status): TraceEvent =
        TraceEvent(
            t = tMs,
            k = "out",
            alarmAt = alarmAt,
            headsUp = headsUp,
            overshoot = overshoot,
            d = s.distanceM?.let { Math.round(it).toInt() },
            eta = s.etaS?.let { Math.round(it).toInt() },
            v = s.vEstKmh?.let { Engine.round1(it) },
            leg = s.legIndex,
            ph = s.phase.name,
        )
}

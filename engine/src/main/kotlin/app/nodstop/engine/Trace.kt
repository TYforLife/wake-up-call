package app.nodstop.engine

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One JSON Lines record, shared by the app's trip logger and the replay test.
 * Kinds: start (legs, including what was learned about each stop), fix, tick, rang, ack, skip, stop (inputs);
 * out (engine output summary);
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
    val awake: Boolean? = null,
    val alarmAt: Long? = null,
    /** Why that alarm would ring (AlarmReason). */
    val ar: String? = null,
    val overshoot: Boolean? = null,
    val arrived: Boolean? = null,
    val boarded: Boolean? = null,
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
        is Input.Start -> TraceEvent(
            input.tMs, "start", legs = input.legs, awake = input.awakeOnFirst.takeIf { it },
            lat = input.lastKnown?.pos?.lat, lng = input.lastKnown?.pos?.lng, acc = input.lastKnown?.accuracyM, ft = input.lastKnown?.fixTimeMs,
        )
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
        "start" -> Input.Start(
            e.t, e.legs ?: emptyList(), e.awake == true,
            if (e.lat != null && e.lng != null && e.ft != null) Fix(e.t, e.ft, LatLng(e.lat, e.lng), e.acc ?: 0f) else null,
        )
        "fix" -> Input.FixIn(e.t, Fix(e.t, e.ft ?: e.t, LatLng(e.lat ?: 0.0, e.lng ?: 0.0), e.acc ?: Float.NaN))
        "tick" -> Input.Tick(e.t)
        "rang" -> Input.Rang(e.t)
        "ack" -> Input.Ack(e.t)
        "skip" -> Input.SkipLeg(e.t)
        "stop" -> Input.Stop(e.t)
        else -> null
    }

    fun outEvent(tMs: Long, overshoot: Boolean, arrived: Boolean, boarded: Boolean, s: Status): TraceEvent =
        TraceEvent(
            t = tMs,
            k = "out",
            alarmAt = s.alarmAtMs,
            ar = s.alarmReason?.name,
            overshoot = overshoot.takeIf { it },
            arrived = arrived.takeIf { it },
            boarded = boarded.takeIf { it },
            d = s.distanceM?.let { Math.round(it).toInt() },
            eta = s.etaS?.let { Math.round(it).toInt() },
            v = s.vEstKmh?.let { Engine.round1(it) },
            leg = s.legIndex,
            ph = s.phase.name,
        )

    /**
     * A ride log safe to commit to a public repo: every position (fixes, stops, learned tracks, last known)
     * moves by the same offset, so your real streets aren't in it, and the `out` lines are recomputed so it
     * still replays exactly. `rate` lines are kept; `note` lines are dropped.
     */
    fun anonymize(lines: List<String>, dLat: Double, dLng: Double, cfg: Config = Config()): List<String> {
        fun mv(p: LatLng) = LatLng(p.lat + dLat, p.lng + dLng)
        fun mvLeg(l: Leg) = l.copy(
            dest = mv(l.dest),
            learned = l.learned?.let { le ->
                le.copy(
                    stop = mv(le.stop),
                    tracks = le.tracks.map { t -> Track(t.points.map { it.copy(lat = it.lat + dLat, lng = it.lng + dLng) }) },
                )
            },
        )
        val engine = Engine(cfg)
        var state = EngineState()
        val out = ArrayList<String>()
        for (line in lines) {
            if (line.isBlank()) continue
            val e = decode(line)
            when (e.k) {
                "out", "note" -> continue
                "rate" -> { out += encode(e); continue }
            }
            val moved = e.copy(
                lat = e.lat?.plus(dLat),
                lng = e.lng?.plus(dLng),
                legs = e.legs?.map(::mvLeg),
            )
            val input = toInput(moved) ?: continue
            val (next, o) = engine.step(state, input)
            state = next
            out += o.events.map(::encode)
        }
        return out
    }
}

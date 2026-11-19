package app.nodstop.data

import android.content.Context
import app.nodstop.engine.AlarmReason
import app.nodstop.notify.DndRestore
import app.nodstop.engine.Eta
import app.nodstop.engine.LatLng
import app.nodstop.engine.Learned
import app.nodstop.engine.LearnedUpdate
import app.nodstop.engine.Learning
import app.nodstop.engine.Leg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

internal val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Writes atomically (temp file + rename), so a kill mid-write never leaves a corrupt file. */
internal fun File.writeAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) { writeText(text); tmp.delete() }
}

class RouteStore(context: Context) {
    private val file = File(context.filesDir, "routes.json")
    private val _routes = MutableStateFlow(load())
    val routes: StateFlow<List<Route>> = _routes

    private val serializer = ListSerializer(Route.serializer())

    /** Never treats an unreadable file as "no routes" (that used to overwrite them on the next save). */
    private fun load(): List<Route> = when (val l = SafeStore.load(file, serializer, System.currentTimeMillis()) { Route.seeds() }) {
        is SafeStore.Loaded.Missing -> l.value.also { save(it) }
        else -> l.value
    }

    private fun save(list: List<Route>) = SafeStore.save(file, serializer, list)

    /** Replaces every route (Debug → Import). */
    fun replaceAll(list: List<Route>) { save(list); _routes.value = list }

    fun get(id: String): Route? = _routes.value.firstOrNull { it.id == id }

    fun upsert(route: Route) {
        val list = _routes.value.toMutableList()
        val i = list.indexOfFirst { it.id == route.id }
        if (i >= 0) list[i] = route else list += route
        save(list); _routes.value = list
    }

    fun delete(id: String) {
        val list = _routes.value.filterNot { it.id == id }
        save(list); _routes.value = list
    }
}

/**
 * The trip as it must survive a process kill: enough to resume after a cold-start ring.
 * [legOffset] maps the engine's leg index back to [legs] (a resumed trip starts the engine at a later leg).
 */
@Serializable
data class ActiveTrip(
    val routeId: String?,
    val routeName: String,
    val legs: List<Leg>,
    val legOffset: Int,
    val engineLegIndex: Int,
    val startedMs: Long,
    val traceFile: String,
    /** Why the scheduled alarm would ring, and the early-biased seconds left to the stop, at the last save. */
    val alarmReason: AlarmReason? = null,
    val remainingS: Double? = null,
    val savedMs: Long = 0,
    val simulated: Boolean = false,
    /** Simulated trips: start this far before stop 1, at this speed (so a resumed sim continues in place). */
    val simKm: Double? = null,
    val simKmh: Double? = null,
) {
    val legIndex: Int get() = legOffset + engineLegIndex
    val currentLeg: Leg? get() = legs.getOrNull(legIndex)
}

/** What to do when "I'm awake" is held and no trip service is alive (the ring came from a cold start). */
data class ResumePlan(val legs: List<Leg>, val legOffset: Int, val awake: Boolean)

object Resume {
    /** Trips older than this are stale: never resume them. */
    const val MAX_AGE_MS = 4 * 3_600_000L
    /** Within this of the stop, a ring means "your stop": resume already awake for it. */
    const val NEAR_S = 180.0

    /**
     * Continue at the SAME leg. If the ring was for this stop (or for the next leg while you were already
     * awake), you're awake for it; if it was an early safety ring far out (GPS lost, no GPS), keep tracking
     * it so it rings again near the stop.
     */
    fun plan(trip: ActiveTrip?, nowMs: Long): ResumePlan? {
        if (trip == null || nowMs - trip.startedMs > MAX_AGE_MS) return null
        if (trip.legIndex >= trip.legs.size) return null
        val remaining = trip.remainingS?.let { it - (nowMs - trip.savedMs) / 1000.0 }
        val awake = when (trip.alarmReason) {
            AlarmReason.WAKE, AlarmReason.OVERSHOOT, AlarmReason.NEXT_LEG -> true
            AlarmReason.FAILSAFE -> remaining != null && remaining <= NEAR_S
            // An urgent message is never "awake for this stop" (DESIGN §10): keep tracking it.
            AlarmReason.NO_GPS, AlarmReason.URGENT, null -> false
        }
        return ResumePlan(trip.legs.drop(trip.legIndex), trip.legIndex, awake)
    }
}

/** What has been learned about each stop from past rides (stop key → learned), in learned.json. */
class LearnedStore(context: Context) {
    private val file = File(context.filesDir, "learned.json")
    private val serializer = MapSerializer(String.serializer(), Learned.serializer())
    private var cache: Map<String, Learned> = load()
    private val _all = MutableStateFlow(cache)
    val all: StateFlow<Map<String, Learned>> = _all

    private fun load(): Map<String, Learned> = SafeStore.load(file, serializer, System.currentTimeMillis()) { emptyMap() }.value

    /** Replaces everything learned (Debug → Import). */
    fun replaceAll(m: Map<String, Learned>) = set(m)

    fun get(dest: LatLng): Learned? = cache[Route.stopKey(dest)]

    fun merge(update: LearnedUpdate) {
        val key = Route.stopKey(update.dest)
        val merged = Learning.merge(cache[key], update)
        set(cache + (key to merged))
    }

    fun forget(dest: LatLng) = set(cache - Route.stopKey(dest))

    /** A ring-timing rating for this stop: too early → ring later, too late → ring earlier (30–90 s). */
    fun rate(dest: LatLng, rating: String) {
        val key = Route.stopKey(dest)
        val existing = cache[key] ?: Learned(dest, emptyList(), 0)
        set(cache + (key to existing.copy(leadS = Eta.adjustLead(existing.leadS, rating))))
    }

    fun forgetAll() = set(emptyMap())

    private fun set(m: Map<String, Learned>) {
        cache = m
        SafeStore.save(file, serializer, m)
        _all.value = m
    }
}

class TripStore(context: Context) {
    private val file = File(context.filesDir, "active_trip.json")

    fun load(): ActiveTrip? = if (!file.exists()) null else
        SafeStore.load(file, ActiveTrip.serializer().nullable, System.currentTimeMillis()) { null }.value

    fun save(trip: ActiveTrip) = file.writeAtomically(storeJson.encodeToString(ActiveTrip.serializer(), trip))

    fun clear() { file.delete() }
}

@Serializable
data class PendingRating(
    val traceFile: String,
    val legs: List<Pair<Int, String>>,
    val endedMs: Long,
    /** Each rated leg's stop (same order as [legs]), so a rating can tune that stop's lead. */
    val dests: List<LatLng> = emptyList(),
) {
    /** Without [leg] (answered elsewhere); null when nothing is left to ask. */
    fun without(leg: Int): PendingRating? {
        val keep = legs.indices.filter { legs[it].first != leg }
        if (keep.isEmpty()) return null
        return copy(legs = keep.map { legs[it] }, dests = keep.mapNotNull { dests.getOrNull(it) }.takeIf { it.size == keep.size } ?: emptyList())
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _speakerLastResort = MutableStateFlow(prefs.getBoolean(KEY_SPEAKER, false))
    /** Phone speaker after 60 s of ringing. Off by default (DESIGN §5). */
    val speakerLastResort: StateFlow<Boolean> = _speakerLastResort

    fun setSpeakerLastResort(on: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAKER, on).apply(); _speakerLastResort.value = on
    }

    private val _pendingRating = MutableStateFlow(readRating())
    val pendingRating: StateFlow<PendingRating?> = _pendingRating

    private fun readRating(): PendingRating? = prefs.getString(KEY_RATING, null)?.let {
        runCatching { storeJson.decodeFromString(PendingRating.serializer(), it) }.getOrNull()
    }

    fun setPendingRating(r: PendingRating?) {
        prefs.edit().apply {
            if (r == null) remove(KEY_RATING) else putString(KEY_RATING, storeJson.encodeToString(PendingRating.serializer(), r))
        }.apply()
        _pendingRating.value = r
    }

    private val _dndDuringTrips = MutableStateFlow(prefs.getBoolean(KEY_DND, false))
    /** While You Slept: Do Not Disturb (alarms only) during trips. Off by default, experimental (DESIGN §10). */
    val dndDuringTrips: StateFlow<Boolean> = _dndDuringTrips

    fun setDndDuringTrips(on: Boolean) {
        prefs.edit().putBoolean(KEY_DND, on).apply(); _dndDuringTrips.value = on
    }

    /** What Nodstop changed in Do Not Disturb, so it can be put back (also after a crash). */
    fun dndRecord(): DndRestore.Record? =
        if (!prefs.getBoolean(KEY_DND_SET, false)) null
        else DndRestore.Record(true, prefs.getInt(KEY_DND_PREV, DndRestore.FILTER_ALL))

    fun setDndRecord(r: DndRestore.Record?) {
        // commit(), not apply(): this must be on disk before the filter changes.
        prefs.edit().apply {
            if (r == null || !r.setByUs) { remove(KEY_DND_SET); remove(KEY_DND_PREV) }
            else { putBoolean(KEY_DND_SET, true); putInt(KEY_DND_PREV, r.previousFilter) }
        }.commit()
    }

    private val _vipNames = MutableStateFlow(readVips())
    /** While You Slept: important contacts, typed in (no contacts permission). Only their messages can ring. */
    val vipNames: StateFlow<List<String>> = _vipNames

    private fun readVips(): List<String> = prefs.getString(KEY_VIPS, "").orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun setVipNames(names: List<String>) {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs.edit().putString(KEY_VIPS, clean.joinToString("\n")).apply(); _vipNames.value = clean
    }

    private val _teaTone = MutableStateFlow(prefs.getBoolean(KEY_TEA, false))
    /** While You Slept: rewrite briefing lines in group-chat "tea" voice (facts re-checked). */
    val teaTone: StateFlow<Boolean> = _teaTone

    fun setTeaTone(on: Boolean) { prefs.edit().putBoolean(KEY_TEA, on).apply(); _teaTone.value = on }

    private val _useGpu = MutableStateFlow(prefs.getBoolean(KEY_GPU, false))
    /** While You Slept: run the model on the GPU (experimental, falls back to the CPU). */
    val useGpu: StateFlow<Boolean> = _useGpu

    fun setUseGpu(on: Boolean) { prefs.edit().putBoolean(KEY_GPU, on).apply(); _useGpu.value = on }

    /**
     * The Briefing lab loaded the model on the GPU on this phone without crashing. Trips only try the GPU
     * after that: a GPU driver can crash natively in ways no `try` can catch, so the first try happens in
     * the lab, never mid-trip.
     */
    val gpuVerified: Boolean get() = prefs.getBoolean(KEY_GPU_OK, false)

    fun setGpuVerified(ok: Boolean) { prefs.edit().putBoolean(KEY_GPU_OK, ok).apply() }

    private val _askTiming = MutableStateFlow(prefs.getBoolean(KEY_ASK_TIMING, true))
    /** After each leg's ring, ask (one silent notification) whether it was too early, good or too late. */
    val askTiming: StateFlow<Boolean> = _askTiming

    fun setAskTiming(on: Boolean) { prefs.edit().putBoolean(KEY_ASK_TIMING, on).apply(); _askTiming.value = on }

    private val _lastRing = MutableStateFlow(prefs.getString(KEY_LAST_RING, null))
    /** How the last ring reached you, in words (PLAN U0), for Readiness. */
    val lastRing: StateFlow<String?> = _lastRing

    fun setLastRing(summary: String) { prefs.edit().putString(KEY_LAST_RING, summary).apply(); _lastRing.value = summary }

    private companion object {
        const val KEY_LAST_RING = "last_ring"
        const val KEY_ASK_TIMING = "ask_ring_timing"
        const val KEY_VIPS = "wys_vip_names"
        const val KEY_TEA = "wys_tea_tone"
        const val KEY_GPU = "wys_use_gpu"
        const val KEY_GPU_OK = "wys_gpu_verified"
        const val KEY_DND = "dnd_during_trips"
        const val KEY_DND_SET = "dnd_set_by_us"
        const val KEY_DND_PREV = "dnd_previous_filter"
        const val KEY_SPEAKER = "speaker_last_resort"
        const val KEY_RATING = "pending_rating"
    }
}

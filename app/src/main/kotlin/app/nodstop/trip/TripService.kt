package app.nodstop.trip

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import app.nodstop.App
import app.nodstop.Notifications
import app.nodstop.alarm.RingService
import app.nodstop.data.ActiveTrip
import app.nodstop.data.PendingRating
import app.nodstop.data.Resume
import app.nodstop.engine.AlarmReason
import app.nodstop.engine.Engine
import app.nodstop.engine.EngineState
import app.nodstop.engine.Fix
import app.nodstop.engine.Input
import app.nodstop.engine.Leg
import app.nodstop.engine.Output
import app.nodstop.engine.Phase
import app.nodstop.engine.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Foreground service (type location) that runs a trip: feeds GPS fixes and 5-s ticks to the engine,
 * applies its one alarm, nudges you at the stop, stores what each ride taught it, writes the trace,
 * and persists enough to resume after a kill. Started only from the UI (foreground).
 */
class TripService : Service() {

    data class TripUi(
        val routeName: String,
        val legNames: List<String>,
        val legIndex: Int,
        val status: Status,
        val simulated: Boolean,
        val startedMs: Long,
    ) {
        val legName: String? get() = legNames.getOrNull(legIndex)
    }

    private val app get() = App.instance
    private val engine = Engine()
    private var state = EngineState()
    private var trip: ActiveTrip? = null
    private var source: LocationSource? = null
    private var lastScheduled: Long? = null
    private var lastPersistMs = 0L
    private var lastNotifyMs = 0L
    private var lastReason: AlarmReason? = null
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            input(Input.Tick(System.currentTimeMillis()))
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        lastScheduled = app.scheduler.scheduledAtMs
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(Notifications.ID_TRIP, Notifications.tripStarting(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Log.e("Nodstop", "trip service could not start in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        val now = System.currentTimeMillis()
        when (intent?.action) {
            ACTION_START_ROUTE -> {
                val route = intent.getStringExtra(EXTRA_ROUTE_ID)?.let { app.routes.get(it) }
                val legs = route?.toLegs { app.learned.get(it) }
                if (route == null || legs == null) { if (trip == null) shutdown(); return START_NOT_STICKY }
                withLastKnown { known -> begin(route.id, route.name, legs, 0, false, null, false, System.currentTimeMillis(), known) }
            }
            ACTION_START_SIM -> {
                val km = intent.getDoubleExtra(EXTRA_SIM_KM, 6.0)
                val kmh = intent.getDoubleExtra(EXTRA_SIM_KMH, 50.0)
                val legs = listOf(
                    Leg("Sim stop 1 (${fmt(km)} km at ${kmh.roundToInt()} km/h)", SimulatedLocationSource.SIM_DEST),
                    Leg(
                        "Sim stop 2 (${fmt(SimulatedLocationSource.LEG2_KM)} km further)",
                        SimulatedLocationSource.pointNorth(SimulatedLocationSource.SIM_DEST, SimulatedLocationSource.LEG2_KM * 1000),
                    ),
                )
                val sim = SimulatedLocationSource(SimulatedLocationSource.SIM_DEST, km, kmh, now)
                begin(null, "Simulated trip", legs, 0, false, null, true, now, null, sim, simKm = km, simKmh = kmh)
            }
            ACTION_RESUME -> {
                val old = app.trips.load()
                val plan = Resume.plan(old, now)
                if (old == null || plan == null) {
                    app.trips.clear()
                    if (trip == null) shutdown()
                    return START_NOT_STICKY
                }
                val sim = if (old.simulated && old.simKm != null && old.simKmh != null)
                    SimulatedLocationSource(SimulatedLocationSource.SIM_DEST, old.simKm, old.simKmh, old.startedMs) else null
                val note = "resumed after a cold-start ring at leg ${plan.legOffset + 1} (${if (plan.awake) "awake for it" else "still tracking it"})"
                val go = { known: Fix? ->
                    begin(old.routeId, old.routeName, old.legs, plan.legOffset, plan.awake, old.traceFile, old.simulated,
                        System.currentTimeMillis(), known, sim, note, old.simKm, old.simKmh, old.startedMs)
                }
                if (sim != null) go(null) else withLastKnown(go)
            }
            ACTION_STOP -> stopTrip()
            else -> if (trip == null) shutdown()
        }
        return START_NOT_STICKY
    }

    /** The phone's last known position (up to 3 min old) arms the failsafe before the first fresh fix. */
    private fun withLastKnown(then: (Fix?) -> Unit) {
        FusedLocationSource(this).lastKnown(maxAgeMs = 180_000L, onResult = then)
    }

    private fun begin(
        routeId: String?,
        routeName: String,
        allLegs: List<Leg>,
        legOffset: Int,
        awakeOnFirst: Boolean,
        traceFile: String?,
        simulated: Boolean,
        now: Long,
        lastKnown: Fix?,
        sim: LocationSource? = null,
        note: String? = null,
        simKm: Double? = null,
        simKmh: Double? = null,
        startedMs: Long = now,
    ) {
        source?.stop()
        handler.removeCallbacks(tick)
        val file = if (traceFile != null) app.traces.continueTrip(traceFile) else app.traces.newTrip(now)
        note?.let { app.traces.note(now, it) }
        trip = ActiveTrip(routeId, routeName, allLegs, legOffset, 0, startedMs, file.name,
            simulated = simulated, simKm = simKm, simKmh = simKmh)
        state = EngineState()
        input(Input.Start(now, allLegs.drop(legOffset), awakeOnFirst, lastKnown))
        val src = sim ?: FusedLocationSource(this)
        source = src
        src.start { fix -> input(Input.FixIn(fix.tMs, fix)) }
        handler.postDelayed(tick, TICK_MS)
    }

    private fun input(i: Input) {
        val t = trip ?: return
        val prevLeg = state.legIndex
        val prevPhase = state.phase
        val (next, out) = engine.step(state, i)
        state = next
        apply(t, out, changed = next.legIndex != prevLeg || next.phase != prevPhase || i is Input.Start)
    }

    private fun apply(t: ActiveTrip, out: Output, changed: Boolean) {
        app.traces.write(out.events)
        val now = out.events.first().t

        // The ONE alarm: reschedule only when it changed by more than 1 s.
        val at = out.alarmAtMs
        val last = lastScheduled
        if (at == null) {
            if (last != null) { app.scheduler.cancel(); lastScheduled = null }
        } else if (last == null || abs(at - last) > 1_000) {
            app.scheduler.set(at); lastScheduled = at
        }

        val legName = t.legs.getOrNull(t.legOffset + out.status.legIndex)?.name
        if (out.arrived) nudge(legName)
        if (out.overshoot) RingService.overshoot()
        out.learned?.let { u -> if (!t.simulated) app.learned.merge(u) }

        val reasonChanged = out.status.alarmReason != lastReason
        lastReason = out.status.alarmReason
        val updated = t.copy(
            engineLegIndex = out.status.legIndex,
            alarmReason = out.status.alarmReason,
            remainingS = engine.remainingArrivalMs(state, now)?.let { (it - now) / 1000.0 },
            savedMs = now,
        )
        trip = updated
        if (changed || reasonChanged || now - lastPersistMs >= PERSIST_MS) {
            app.trips.save(updated); lastPersistMs = now
        }

        val ui = TripUi(updated.routeName, updated.legs.map { it.name }, updated.legIndex, out.status, updated.simulated, updated.startedMs)
        _status.value = ui
        if (changed || now - lastNotifyMs >= 2_000) {
            lastNotifyMs = now
            getSystemService(NotificationManager::class.java).notify(Notifications.ID_TRIP, Notifications.trip(this, ui))
        }

        if (out.status.phase == Phase.DONE) finishTrip(updated)
    }

    /** One light buzz and a notification: the bus is at your stop. Not the alarm: no sound. */
    private fun nudge(legName: String?) {
        Notifications.atStop(this, legName)
        runCatching {
            val v = getSystemService(VibratorManager::class.java).defaultVibrator
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1)
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            else @Suppress("DEPRECATION") v.vibrate(effect)
        }
    }

    private fun finishTrip(t: ActiveTrip) {
        offerRating(t)
        app.trips.clear()
        app.traces.endTrip()
        trip = null
        shutdown()
    }

    private fun offerRating(t: ActiveTrip) {
        if (t.simulated) return
        val legs = t.legs.mapIndexedNotNull { i, l -> if (l.alarmEnabled) i to l.name else null }
        if (legs.isNotEmpty()) app.settings.setPendingRating(PendingRating(t.traceFile, legs, System.currentTimeMillis()))
    }

    private fun shutdown() {
        source?.stop()
        source = null
        handler.removeCallbacks(tick)
        _status.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- called in-process ----

    fun onRang() = input(Input.Rang(System.currentTimeMillis()))
    fun ack() = input(Input.Ack(System.currentTimeMillis()))
    fun skipLeg() = input(Input.SkipLeg(System.currentTimeMillis()))

    fun stopTrip() {
        if (trip != null) input(Input.Stop(System.currentTimeMillis())) else shutdown()
    }

    override fun onDestroy() {
        // Do NOT cancel the alarm here: if the system destroys us, the scheduled alarm is the dead-man's switch.
        source?.stop()
        handler.removeCallbacks(tick)
        instance = null
        _status.value = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START_ROUTE = "start_route"
        private const val ACTION_START_SIM = "start_sim"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_ROUTE_ID = "route_id"
        private const val EXTRA_SIM_KM = "sim_km"
        private const val EXTRA_SIM_KMH = "sim_kmh"
        private const val TICK_MS = 5_000L
        private const val PERSIST_MS = 15_000L

        @Volatile
        var instance: TripService? = null
            private set

        private val _status = MutableStateFlow<TripUi?>(null)
        /** Non-null while a trip runs in this process. */
        val status: StateFlow<TripUi?> = _status

        fun startRoute(ctx: Context, routeId: String) = send(ctx, Intent(ctx, TripService::class.java)
            .setAction(ACTION_START_ROUTE).putExtra(EXTRA_ROUTE_ID, routeId))

        fun startSim(ctx: Context, km: Double, kmh: Double) = send(ctx, Intent(ctx, TripService::class.java)
            .setAction(ACTION_START_SIM).putExtra(EXTRA_SIM_KM, km).putExtra(EXTRA_SIM_KMH, kmh))

        /** After "I'm awake" on a ring that came from a cold start: continue the same leg. */
        fun resumeAfterColdRing(ctx: Context) = send(ctx, Intent(ctx, TripService::class.java).setAction(ACTION_RESUME))

        /** Stop the trip, including one whose process was killed (its alarm is still scheduled). */
        fun stop(ctx: Context) {
            val svc = instance
            if (svc != null) svc.stopTrip()
            else {
                val app = App.instance
                app.scheduler.cancel()
                app.trips.load()?.let {
                    app.traces.continueTrip(it.traceFile)
                    app.traces.note(System.currentTimeMillis(), "stopped from the app after the trip service died")
                    app.traces.endTrip()
                }
                app.trips.clear()
            }
        }

        private fun send(ctx: Context, intent: Intent) {
            try {
                ctx.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e("Nodstop", "could not start the trip service", e)
            }
        }

        fun phaseLabel(ui: TripUi): String = when (ui.status.phase) {
            Phase.WAITING -> "Waiting for the bus"
            Phase.RIDING -> "On the bus"
            Phase.RINGING -> "Wake up"
            Phase.AWAKE -> "Get off at ${ui.legName ?: "your stop"}"
            Phase.DONE -> "Trip done"
            Phase.IDLE -> "Starting"
        }

        fun summary(ui: TripUi): String {
            val s = ui.status
            val age = s.lastFixAgeS
            val gps = if (s.gpsOk) null else if (age == null) "waiting for GPS" else "GPS lost ${age.roundToInt()} s"
            val dist = s.distanceM?.let { distance(it) }
            val eta = s.etaS?.let { "~${minutes(it)}" }
            val learned = if (s.onLearnedRoute) "learned route" else null
            return listOfNotNull(dist, eta, gps, learned).joinToString(" · ").ifEmpty { "Getting GPS…" }
        }

        fun distance(m: Double): String = if (m < 1_000) "${(m / 10).roundToInt() * 10} m" else "${fmt(m / 1000)} km"
        fun minutes(s: Double): String = if (s < 90) "${s.roundToInt()} s" else "${(s / 60).roundToInt()} min"
        fun clock(ms: Long): String = SimpleDateFormat("H:mm:ss", Locale.getDefault()).format(Date(ms))
        private fun fmt(x: Double) = String.format(Locale.US, "%.1f", x)
    }
}

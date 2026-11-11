package app.nodstop.trip

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.nodstop.App
import app.nodstop.Notifications
import app.nodstop.alarm.RingService
import app.nodstop.data.ActiveTrip
import app.nodstop.data.PendingRating
import app.nodstop.data.Resume
import app.nodstop.engine.Engine
import app.nodstop.engine.EngineState
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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Foreground service (type location) that runs a trip: feeds GPS fixes and 5-s ticks to the engine,
 * applies its one alarm, posts the heads-up, writes the trace, and persists enough to resume after a kill.
 * Started only from the UI (foreground), so no background-location permission is needed.
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
            startForeground(Notifications.ID_TRIP, Notifications.trip(this, "Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Log.e("Nodstop", "trip service could not start in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        val now = System.currentTimeMillis()
        when (intent?.action) {
            ACTION_START_ROUTE -> {
                val route = intent.getStringExtra(EXTRA_ROUTE_ID)?.let { app.routes.get(it) }
                val legs = route?.toLegs()
                if (route == null || legs == null) { if (trip == null) shutdown(); return START_NOT_STICKY }
                begin(route.id, route.name, legs, legOffset = 0, holdUntilMs = null, traceFile = null, simulated = false, now = now)
            }
            ACTION_START_SIM -> {
                val km = intent.getDoubleExtra(EXTRA_SIM_KM, 3.0)
                val kmh = intent.getDoubleExtra(EXTRA_SIM_KMH, 50.0)
                val legs = listOf(
                    Leg("Sim stop 1 (${fmt(km)} km at ${kmh.roundToInt()} km/h)", SimulatedLocationSource.SIM_DEST,
                        expectedRideMin = max(1, (km / kmh * 60).toInt())),
                    Leg("Sim stop 2 (${fmt(SimulatedLocationSource.LEG2_KM)} km further)",
                        SimulatedLocationSource.pointNorth(SimulatedLocationSource.SIM_DEST, SimulatedLocationSource.LEG2_KM * 1000),
                        expectedRideMin = max(1, (SimulatedLocationSource.LEG2_KM / kmh * 60).toInt())),
                )
                begin(null, "Simulated trip", legs, 0, null, null, true, now,
                    sim = SimulatedLocationSource(SimulatedLocationSource.SIM_DEST, km, kmh, now), simKm = km, simKmh = kmh)
            }
            ACTION_RESUME -> {
                val old = app.trips.load()
                val plan = Resume.plan(old, now)
                if (old == null || plan == null) {
                    // The ring was for the last leg (or the trip is stale): the trip is over.
                    if (old != null) {
                        app.traces.continueTrip(old.traceFile)
                        app.traces.note(now, "cold ring acked on the last leg: trip done")
                        offerRating(old)
                    }
                    app.trips.clear()
                    app.traces.endTrip()
                    if (trip == null) shutdown()
                    return START_NOT_STICKY
                }
                val sim = if (old.simulated && old.simKm != null && old.simKmh != null)
                    SimulatedLocationSource(SimulatedLocationSource.SIM_DEST, old.simKm, old.simKmh, old.startedMs) else null
                begin(old.routeId, old.routeName, old.legs, plan.legOffset, plan.holdUntilMs, old.traceFile, old.simulated, now,
                    sim = sim, note = "resumed after a cold-start ring at leg ${plan.legOffset + 1}",
                    simKm = old.simKm, simKmh = old.simKmh, startedMs = old.startedMs)
            }
            ACTION_STOP -> stopTrip()
            else -> if (trip == null) shutdown()
        }
        return START_NOT_STICKY
    }

    private fun begin(
        routeId: String?,
        routeName: String,
        allLegs: List<Leg>,
        legOffset: Int,
        holdUntilMs: Long?,
        traceFile: String?,
        simulated: Boolean,
        now: Long,
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
        input(Input.Start(now, allLegs.drop(legOffset), holdUntilMs))
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
        apply(t, out, legChanged = next.legIndex != prevLeg || next.phase != prevPhase || i is Input.Start)
    }

    private fun apply(t: ActiveTrip, out: Output, legChanged: Boolean) {
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

        if (out.headsUp) Notifications.headsUp(this, t.legs.getOrNull(t.legOffset + out.status.legIndex)?.name)
        if (out.overshoot) RingService.overshoot()

        val updated = t.copy(engineLegIndex = out.status.legIndex, expectedArrivalMs = engine.remainingArrivalMs(state, now))
        trip = updated
        if (legChanged || now - lastPersistMs >= PERSIST_MS) {
            app.trips.save(updated); lastPersistMs = now
        }

        val ui = TripUi(updated.routeName, updated.legs.map { it.name }, updated.legIndex, out.status, updated.simulated, updated.startedMs)
        _status.value = ui
        if (legChanged || now - lastNotifyMs >= 2_000) {
            lastNotifyMs = now
            getSystemService(NotificationManager::class.java).notify(Notifications.ID_TRIP, Notifications.trip(this, summary(ui)))
        }

        if (out.status.phase == Phase.DONE) finishTrip(updated)
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

        /** After "I'm awake" on a ring that came from a cold start: continue at the next leg. */
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

        fun summary(ui: TripUi): String {
            val s = ui.status
            val leg = "Leg ${ui.legIndex + 1}/${ui.legNames.size}"
            val hold = s.holdUntilMs
            if (hold != null) return "$leg · transfer · tracking next bus from ${clock(hold)}"
            val age = s.lastFixAgeS
            val gps = if (s.gpsOk) "GPS ok" else if (age == null) "waiting for GPS" else "GPS lost ${age.roundToInt()} s"
            val dist = s.distanceM?.let { "${fmt(it / 1000)} km" }
            val eta = s.etaS?.let { "~${(it / 60).roundToInt()} min" }
            return listOfNotNull(leg, dist, eta, gps).joinToString(" · ")
        }

        fun clock(ms: Long): String = SimpleDateFormat("H:mm:ss", Locale.getDefault()).format(Date(ms))
        private fun fmt(x: Double) = String.format(Locale.US, "%.1f", x)
    }
}

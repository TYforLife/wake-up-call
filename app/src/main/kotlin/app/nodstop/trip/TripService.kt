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
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import app.nodstop.data.Route
import app.nodstop.engine.LatLng
import app.nodstop.engine.CoordParser
import app.nodstop.App
import app.nodstop.Notifications
import app.nodstop.alarm.RingService
import app.nodstop.alarm.vibrateAsAlarm
import app.nodstop.isolated
import app.nodstop.brief.BriefingCoordinator
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import app.nodstop.brief.Digests
import app.nodstop.brief.PanelLines
import app.nodstop.brief.RingStats
import app.nodstop.notify.DndController
import app.nodstop.notify.MessageStore
import app.nodstop.data.ActiveTrip
import app.nodstop.data.Departures
import app.nodstop.data.Timetable
import app.nodstop.data.TransitData
import app.nodstop.data.TransitIndex
import app.nodstop.data.PendingRating
import app.nodstop.data.RouteSuggest
import app.nodstop.data.Resume
import app.nodstop.engine.AlarmReason
import app.nodstop.engine.Engine
import app.nodstop.engine.EngineState
import app.nodstop.engine.Fix
import app.nodstop.engine.Input
import app.nodstop.engine.Leg
import app.nodstop.engine.Output
import app.nodstop.engine.Phase
import app.nodstop.engine.Geo
import app.nodstop.engine.TraceEvent
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
        val legStops: List<LatLng>,
        val legIndex: Int,
        val status: Status,
        val simulated: Boolean,
        val startedMs: Long,
        /** Each stop's own circle, or null for automatic (so a simulated trip's circles show and change, PLAN B4). */
        val legRadii: List<Double?> = emptyList(),
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
    private var briefing: BriefingCoordinator? = null
    /** GPS gaps and circle entries for the trip log (PLAN R0). */
    private var notes = RideNotes()
    private val routesScope = MainScope()
    private var index: TransitIndex? = null
    private var timetable: Timetable? = null
    private var routesJob: Job? = null

    /** Quiet trip follows the screen (PLAN Run 10, A4): quiet while it's off, your notifications while it's on. */
    private val screen = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (trip == null) return
            DndController.update(this@TripService, tripRunning = true, screenOn = intent.action == Intent.ACTION_SCREEN_ON)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            input(Input.Tick(now))
            // Also every tick: the setting or its access may change mid-trip, and a screen broadcast can be missed.
            if (trip != null) DndController.update(this@TripService, tripRunning = true)
            briefing?.onTick(now, lastScheduled)
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        lastScheduled = app.scheduler.scheduledAtMs
        // A wake circle changed on the map applies to this trip at once (PLAN U3).
        routesJob = routesScope.launch { app.routes.routes.collect { onRoutesChanged(it) } }
        // The timetable, for the bus's ETA on screen (`busEta`); a build without it keeps the engine's.
        routesScope.launch { index = TransitData.get(this@TripService); timetable = TransitData.timetable(this@TripService) }
        val f = android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, RECEIVER_NOT_EXPORTED) else registerReceiver(screen, f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A notification button on a running trip: already in the foreground; don't flash "starting" over it.
        val button = intent?.action == ACTION_OFF || intent?.action == ACTION_STOP
        // Android refuses a location service without location access (SecurityException) or from the background.
        if (!(button && trip != null)) {
            val ok = isolated("trip in the foreground", false) {
                startForeground(Notifications.ID_TRIP, Notifications.tripStarting(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                true
            }
            if (!ok) { stopSelf(); return START_NOT_STICKY }
        }
        val now = System.currentTimeMillis()
        when (intent?.action) {
            ACTION_START_ROUTE -> {
                val route = intent.getStringExtra(EXTRA_ROUTE_ID)?.let { app.routes.get(it) }
                val legs = route?.toLegs { app.learned.get(it) }
                if (route == null || legs == null) { if (trip == null) shutdown(); return START_NOT_STICKY }
                // "Start from leg…" picks the leg; plain Start lets the engine pick it from where you are.
                val from = intent.getIntExtra(EXTRA_FROM_LEG, -1).takeIf { it in legs.indices }
                withLastKnown { known ->
                    begin(route.id, route.name, legs, from ?: 0, false, null, false, System.currentTimeMillis(), known, autoLeg = from == null)
                }
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
                val afterRing = intent.getBooleanExtra(EXTRA_AFTER_RING, true)
                val plan = Resume.plan(old, now, afterRing)
                if (old == null || plan == null) {
                    // Nothing left to protect: drop the cold ring's re-ring safety net too.
                    if (trip == null) app.scheduler.cancel()
                    app.trips.clear()
                    if (trip == null) shutdown()
                    return START_NOT_STICKY
                }
                val sim = if (old.simulated && old.simKm != null && old.simKmh != null)
                    SimulatedLocationSource(SimulatedLocationSource.SIM_DEST, old.simKm, old.simKmh, old.startedMs) else null
                val note = "resumed ${if (afterRing) "after a cold-start ring" else "after a restart"} at leg ${plan.legOffset + 1} " +
                    "(${if (plan.awake) "awake for it" else "still tracking it"})"
                val go = { known: Fix? ->
                    begin(old.routeId, old.routeName, old.legs, plan.legOffset, plan.awake, old.traceFile, old.simulated,
                        System.currentTimeMillis(), known, sim, note, old.simKm, old.simKmh, old.startedMs)
                }
                if (sim != null) go(null) else withLastKnown(go)
            }
            ACTION_STOP -> stopTrip()
            // The notification's "I'm off this bus" (PLAN Run 10, A3): as the button in the app.
            ACTION_OFF -> if (trip != null) skipLeg() else shutdown()
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
        autoLeg: Boolean = false,
    ) {
        source?.stop()
        handler.removeCallbacks(tick)
        Notifications.cancelTripPaused(this)
        // While You Slept (DESIGN §10): catch chat notifications while this trip runs, in memory only.
        MessageStore.instance.start()
        briefing?.stop()
        // An urgent VIP message (DESIGN §10): ring now, through the engine and the single ring path (W1).
        briefing = BriefingCoordinator(this, now) { handler.post { input(Input.Urgent(System.currentTimeMillis())) } }
        val file = if (traceFile != null) app.traces.continueTrip(traceFile) else app.traces.newTrip(now)
        note?.let { app.traces.note(now, it) }
        trip = ActiveTrip(routeId, routeName, allLegs, legOffset, 0, startedMs, file.name,
            simulated = simulated, simKm = simKm, simKmh = simKmh)
        state = EngineState()
        input(Input.Start(now, allLegs.drop(legOffset), awakeOnFirst, lastKnown, autoLeg))
        val src = sim ?: FusedLocationSource(this)
        source = src
        notes = RideNotes()
        src.start { fix -> notes.onFix(fix.tMs); input(Input.FixIn(fix.tMs, fix)) }
        handler.postDelayed(tick, TICK_MS)
        // Quiet trip: on now only if the screen is already off (a trip started from the tile, PLAN A4).
        DndController.update(this, tripRunning = true)
    }

    private fun input(i: Input) {
        val t = trip ?: return
        val prevLeg = state.legIndex
        val prevPhase = state.phase
        val (next, out) = engine.step(state, i)
        state = next
        apply(t, out, changed = next.legIndex != prevLeg || next.phase != prevPhase || i is Input.Start)
    }

    /** "Quiet trip: 6 held" while our Do Not Disturb is on (PLAN R2); the count needs notification access. */
    private fun quietLine(): String? {
        val on = app.settings.dndRecord()?.setByUs == true
        val snap = MessageStore.instance.snapshot()
        return Notifications.quietLine(on, if (snap.active) snap.added + snap.otherNotifications else null)
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
        // Entering your circle: was boarding detected, and when is the alarm? (Explains a circle that didn't ring.)
        val s = out.status
        if (s.phase == Phase.WAITING || s.phase == Phase.RIDING) {
            val leg = t.legs.getOrNull(t.legOffset + s.legIndex)
            val straight = s.position?.let { p -> leg?.let { Geo.distanceM(p, it.stop) } }
            notes.onPosition(
                t.legOffset + s.legIndex, leg?.wakeRadiusM, straight, s.startDistanceM != null || s.phase == Phase.RIDING,
                s.alarmAtMs?.let { (it - now) / 1000 },
            )?.let { app.traces.write(listOf(TraceEvent(now, "zone", msg = it))) }
        }
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

        // On a bus Plan a trip knows, the time left shown is the timetable's from where you are (owner, 2026-11-24).
        val shown = busEta(updated, out.status)?.let { out.status.copy(etaS = it) } ?: out.status
        val ui = TripUi(
            updated.routeName, updated.legs.map { it.name }, updated.legs.map { it.stop }, updated.legIndex, shown, updated.simulated,
            updated.startedMs, updated.legs.map { it.wakeRadiusM },
        )
        _status.value = ui
        if (changed || now - lastNotifyMs >= 2_000) {
            lastNotifyMs = now
            getSystemService(NotificationManager::class.java).notify(Notifications.ID_TRIP, Notifications.trip(this, ui, quietLine(), updated.currentLeg?.wakeRadiusM))
        }

        if (out.status.phase == Phase.DONE) finishTrip(updated)
    }

    /**
     * The timetable's time left on this bus (`Departures.remainingS`): only on the bus, only when Plan a trip knows
     * which bus it is and where you got on, and only off a learned route (past rides come first). For what's shown;
     * the alarm keeps the engine's earlier estimate.
     */
    private fun busEta(t: ActiveTrip, s: Status): Double? {
        if (s.phase != Phase.RIDING) return null
        // Past rides on this route know your bus best (owner: "use the past rides as well"): then the engine's ETA.
        if (s.onLearnedRoute && s.etaS != null) return null
        val here = s.position ?: return null
        val ix = index ?: return null
        val tt = timetable ?: return null
        val draft = t.routeId?.let { app.routes.get(it) }?.legs?.getOrNull(t.legOffset + s.legIndex) ?: return null
        val bus = draft.bus ?: return null
        val board = draft.boardCoords?.let { CoordParser.parse(it) } ?: return null
        val alight = CoordParser.parse(draft.coords) ?: return null
        return Departures.remainingS(ix, tt, bus, board, alight, here, java.time.LocalDate.now(), java.time.LocalTime.now().toSecondOfDay())
    }

    /** One light buzz and a notification: the bus is at your stop. Not the alarm: no sound. */
    private fun nudge(legName: String?) {
        Notifications.atStop(this, legName)
        isolated("at-stop buzz") {
            getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrateAsAlarm(VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1))
        }
    }

    private fun finishTrip(t: ActiveTrip) {
        app.traces.write(listOf(TraceEvent(System.currentTimeMillis(), "gps", msg = notes.gpsLine())))
        offerRating(t)
        app.trips.clear()
        app.traces.endTrip()
        trip = null
        shutdown()
    }

    private fun offerRating(t: ActiveTrip) {
        if (t.simulated || !app.settings.askTiming.value) return
        val rated = t.legs.withIndex().filter { it.value.alarmEnabled && it.index !in askedLegs }
        if (rated.isNotEmpty()) app.settings.setPendingRating(
            PendingRating(t.traceFile, rated.map { it.index to it.value.name }, System.currentTimeMillis(), rated.map { it.value.dest }),
        )
    }

    private fun shutdown() {
        briefing?.stop(); briefing = null
        MessageStore.instance.stop()
        Notifications.cancelMissed(this) // the messages it pointed to are gone
        DndController.onTripEnd(this)
        source?.stop()
        source = null
        handler.removeCallbacks(tick)
        _status.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- called in-process ----

    fun onRang() {
        val now = System.currentTimeMillis()
        input(Input.Rang(now))
        app.traces.write(listOf(TraceEvent(now, "gps", msg = notes.gpsLine())))
        logRingStats(now)
        briefing?.onRang()
    }

    /** Gate C: what the alarm screen had ready at this ring, as counts in the trip log (never text). */
    private fun logRingStats(now: Long) {
        val snap = MessageStore.instance.snapshot()
        if (!snap.active) return
        val digest = Digests.build(snap.messages, snap.otherNotifications, app.settings.vipNames.value)
        val lines = PanelLines.combine(digest, snap.briefed, snap.urgent)
        app.traces.wys(now, RingStats.of(lines, digest.otherNotifications, app.models.current() != null).encode())
    }
    fun ack() {
        val t = trip
        val ringing = state.phase == Phase.RINGING
        val reason = state.ringReason
        val leg = t?.let { it.legOffset + state.legIndex }
        input(Input.Ack(System.currentTimeMillis()))
        // A stop's own ring (not a safety or message ring): ask, optionally, how its timing was.
        if (t != null && leg != null && ringing && !t.simulated && app.settings.askTiming.value &&
            (reason == AlarmReason.WAKE || reason == AlarmReason.FAILSAFE)
        ) {
            val l = t.legs.getOrNull(leg)
            if (l != null && l.alarmEnabled) {
                askedLegs += leg
                Notifications.rateLeg(this, t.traceFile, leg, l.name, l.dest)
            }
        }
    }

    /**
     * "Start at <stop>" (BACKLOG 5): the trip started past a stop you're actually still going to. Same trip and log,
     * from that leg, with no pick this time.
     */
    fun restartAt(leg: Int) {
        val t = trip ?: return
        if (t.simulated || leg !in t.legs.indices) return
        app.traces.note(System.currentTimeMillis(), "started again at leg ${leg + 1} (you tapped Start at ${t.legs[leg].name})")
        withLastKnown { known ->
            begin(t.routeId, t.routeName, t.legs, leg, false, t.traceFile, false, System.currentTimeMillis(), known, startedMs = t.startedMs)
        }
    }

    /** Legs already asked about by notification this trip: the end-of-trip card skips them. */
    private val askedLegs = HashSet<Int>()
    fun skipLeg() = input(Input.SkipLeg(System.currentTimeMillis()))

    fun stopTrip() {
        if (trip != null) input(Input.Stop(System.currentTimeMillis())) else shutdown()
    }

    /** Any saved leg with this trip's stop: take its wake circle (works for a simulated trip's stops too). */
    private fun onRoutesChanged(routes: List<Route>) {
        val t = trip ?: return
        val own = routes.firstOrNull { it.id == t.routeId }
        val byStop = HashMap<LatLng, Double?>()
        for (r in routes.filter { it !== own } + listOfNotNull(own)) {
            r.legs.forEach { d -> CoordParser.parse(d.coords)?.let { p -> byStop[p] = d.wakeRadiusM } }
        }
        val merged = t.legs.map { l -> if (byStop.containsKey(l.dest)) l.copy(wakeRadiusM = byStop[l.dest]) else l }
        applyLegs(merged)
    }

    /** A simulated trip's stop isn't in any saved route: the map sets its circle here (not saved). */
    fun setWakeRadius(stop: LatLng, radiusM: Double?) {
        val t = trip ?: return
        applyLegs(t.legs.map { if (it.dest == stop) it.copy(wakeRadiusM = radiusM) else it })
    }

    private fun applyLegs(merged: List<Leg>) {
        val t = trip ?: return
        if (merged == t.legs) return
        val updated = t.copy(legs = merged)
        trip = updated
        app.trips.save(updated)
        input(Input.UpdateLegs(System.currentTimeMillis(), merged.drop(t.legOffset)))
    }

    override fun onDestroy() {
        routesJob?.cancel()
        unregisterReceiver(screen)
        // Do NOT cancel the alarm here: if the system destroys us, the scheduled alarm is the dead-man's switch.
        briefing?.stop(); briefing = null
        MessageStore.instance.stop()
        DndController.onTripEnd(this)
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
        private const val ACTION_OFF = "off"
        private const val EXTRA_ROUTE_ID = "route_id"
        private const val EXTRA_AFTER_RING = "after_ring"
        private const val EXTRA_FROM_LEG = "from_leg"
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

        /** Start a route; [fromLeg] = a leg you chose, or -1 to pick it from where you are. */
        fun startRoute(ctx: Context, routeId: String, fromLeg: Int = -1) {
            App.instance.settings.setLastRouteId(routeId)
            val cal = java.util.Calendar.getInstance()
            val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
            App.instance.settings.addStart(
                RouteSuggest.Start(
                    routeId, cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE),
                    dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY,
                ),
            )
            send(ctx, Intent(ctx, TripService::class.java)
                .setAction(ACTION_START_ROUTE).putExtra(EXTRA_ROUTE_ID, routeId).putExtra(EXTRA_FROM_LEG, fromLeg))
        }

        fun startSim(ctx: Context, km: Double, kmh: Double) = send(ctx, Intent(ctx, TripService::class.java)
            .setAction(ACTION_START_SIM).putExtra(EXTRA_SIM_KM, km).putExtra(EXTRA_SIM_KMH, kmh))

        /** After "I'm awake" on a ring that came from a cold start: continue the same leg. */
        fun resumeAfterColdRing(ctx: Context) = send(ctx, Intent(ctx, TripService::class.java).setAction(ACTION_RESUME))

        /** After an app update or a phone restart (PLAN A1): continue tracking the same stop; nothing rang. */
        fun resumeIntent(ctx: Context): Intent =
            Intent(ctx, TripService::class.java).setAction(ACTION_RESUME).putExtra(EXTRA_AFTER_RING, false)

        fun resumeAfterRestart(ctx: Context) = send(ctx, resumeIntent(ctx))

        /** Stop the trip, including one whose process was killed (its alarm is still scheduled). */
        /** Stops the trip (the notification's Stop trip action). */
        fun stopIntent(ctx: Context): Intent = Intent(ctx, TripService::class.java).setAction(ACTION_STOP)

        /** "I'm off this bus" from the notification (PLAN Run 10, A3). */
        fun offIntent(ctx: Context): Intent = Intent(ctx, TripService::class.java).setAction(ACTION_OFF)

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
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: Android won't start it from the background right now.
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

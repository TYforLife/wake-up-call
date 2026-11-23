package app.nodstop.learn

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.nodstop.Notifications
import app.nodstop.R
import app.nodstop.engine.LearnSpike
import app.nodstop.engine.SpikeEvent
import app.nodstop.isolated
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * L0 Learn spike (docs/LEARN.md): records one ride's raw signals until the rider stops it. GPS at the same rate a
 * trip uses (so the battery number is a trip's), activity transitions and the phone's activity guesses, the battery
 * every 5 minutes, and the rider's own "got on" / "got off" marks as ground truth.
 *
 * It never schedules, changes or cancels an alarm (CLAUDE.md rule 1): a normal trip can run alongside it.
 */
class LearnSpikeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var location: LocationCallback? = null

    private val battery = object : Runnable {
        override fun run() {
            SpikeLog.append(this@LearnSpikeService, batteryEvent(this@LearnSpikeService, "batt"))
            handler.postDelayed(this, BATTERY_EVERY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopRecording(); return START_NOT_STICKY }
        val ok = isolated("learn spike in the foreground", false) {
            startForeground(Notifications.ID_LEARN_SPIKE, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            true
        }
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        if (_running.value) return START_NOT_STICKY
        _running.value = true
        val now = System.currentTimeMillis()
        SpikeLog.begin(this, now)
        val allowed = hasActivityPermission(this)
        SpikeLog.append(this, batteryEvent(this, "begin").copy(ok = allowed, msg = "${Build.MODEL} Android ${Build.VERSION.RELEASE}"))
        startLocation()
        if (allowed) startActivity()
        handler.postDelayed(battery, BATTERY_EVERY_MS)
        return START_NOT_STICKY
    }

    private fun startLocation() {
        val client = LocationServices.getFusedLocationProviderClient(this)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000).setMinUpdateIntervalMillis(1_000).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    val now = System.currentTimeMillis()
                    SpikeLog.append(
                        this@LearnSpikeService,
                        SpikeEvent(
                            now, "fix", et = wallOf(loc.elapsedRealtimeNanos / 1_000_000, now),
                            lat = loc.latitude, lng = loc.longitude, acc = if (loc.hasAccuracy()) loc.accuracy else null,
                            spd = if (loc.hasSpeed()) loc.speed else null,
                            sacc = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else null,
                        ),
                    )
                }
            }
        }
        try {
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
            location = cb
        } catch (e: SecurityException) {
            Log.w("Nodstop", "learn spike: no location access", e)
            SpikeLog.append(this, SpikeEvent(System.currentTimeMillis(), "note", msg = "no location access"))
        }
    }

    private fun startActivity() {
        val client = ActivityRecognition.getClient(this)
        val types = listOf(DetectedActivity.IN_VEHICLE, DetectedActivity.ON_FOOT, DetectedActivity.WALKING, DetectedActivity.STILL)
        val transitions = types.flatMap { t ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { dir ->
                ActivityTransition.Builder().setActivityType(t).setActivityTransition(dir).build()
            }
        }
        try {
            client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), activityIntent(this, REQ_TRANSITIONS))
            client.requestActivityUpdates(ACTIVITY_EVERY_MS, activityIntent(this, REQ_UPDATES))
        } catch (e: SecurityException) {
            Log.w("Nodstop", "learn spike: no activity recognition", e)
        }
    }

    private fun stopRecording() {
        handler.removeCallbacks(battery)
        location?.let { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) }
        location = null
        try {
            val client = ActivityRecognition.getClient(this)
            client.removeActivityTransitionUpdates(activityIntent(this, REQ_TRANSITIONS))
            client.removeActivityUpdates(activityIntent(this, REQ_UPDATES))
        } catch (e: SecurityException) {
            Log.w("Nodstop", "learn spike: activity recognition was taken back", e)
        }
        if (SpikeLog.current(this) != null) SpikeLog.append(this, batteryEvent(this, "end"))
        SpikeLog.finish(this)
        _running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Killed by the system: close the file the same way, so the report still has an end.
        if (_running.value) stopRecording()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "learn_spike_stop"
        private const val REQ_TRANSITIONS = 70
        private const val REQ_UPDATES = 71
        private const val BATTERY_EVERY_MS = 5 * 60_000L
        private const val ACTIVITY_EVERY_MS = 10_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, LearnSpikeService::class.java))
            } catch (e: IllegalStateException) {
                Log.e("Nodstop", "could not start the learn spike", e)
            }
        }

        fun stop(ctx: Context) {
            if (_running.value) ctx.startService(Intent(ctx, LearnSpikeService::class.java).setAction(ACTION_STOP))
        }

        fun hasActivityPermission(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        /** Records the rider's mark (ground truth for the report). */
        fun mark(ctx: Context, what: String) {
            if (_running.value || SpikeLog.current(ctx) != null) SpikeLog.append(ctx, SpikeEvent(System.currentTimeMillis(), "mark", act = what))
        }

        /** A time on the monotonic clock ([elapsedMs]) as wall time, measured now: a wrong phone clock can't skew delays. */
        fun wallOf(elapsedMs: Long, nowMs: Long): Long = nowMs - (SystemClock.elapsedRealtime() - elapsedMs)

        fun batteryEvent(ctx: Context, kind: String): SpikeEvent {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            return SpikeEvent(System.currentTimeMillis(), kind, pct = pct, chg = bm?.isCharging)
        }

        // Activity recognition fills the intent's extras, so the PendingIntent must be mutable (Android 12+).
        private fun activityIntent(ctx: Context, req: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, req, Intent(ctx, LearnSpikeReceiver::class.java).setAction(LearnSpikeReceiver.ACTION_ACTIVITY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        private fun markIntent(ctx: Context, what: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, req, Intent(ctx, LearnSpikeReceiver::class.java).setAction(LearnSpikeReceiver.ACTION_MARK).putExtra(LearnSpikeReceiver.EXTRA_MARK, what),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun notification(ctx: Context): Notification {
            val stop = PendingIntent.getService(
                ctx, 72, Intent(ctx, LearnSpikeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(ctx, Notifications.CH_TRIP)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Learn spike · recording")
                .setContentText("No alarm here. Tap when you get on and off.")
                .setOngoing(true)
                .setContentIntent(Notifications.openApp(ctx))
                .addAction(Notification.Action.Builder(null, "Got on", markIntent(ctx, LearnSpike.BUS_ON, 73)).build())
                .addAction(Notification.Action.Builder(null, "Got off", markIntent(ctx, LearnSpike.BUS_OFF, 74)).build())
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build()
        }
    }
}

/** Activity results and the notification's "Got on" / "Got off" buttons, into the spike's log. */
class LearnSpikeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_MARK -> intent.getStringExtra(EXTRA_MARK)?.let { LearnSpikeService.mark(context, it) }
            ACTION_ACTIVITY -> if (SpikeLog.current(context) != null) record(context, intent)
        }
    }

    private fun record(ctx: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        if (ActivityTransitionResult.hasResult(intent)) {
            ActivityTransitionResult.extractResult(intent)?.transitionEvents?.forEach { e ->
                SpikeLog.append(
                    ctx,
                    SpikeEvent(
                        now, "tr", et = LearnSpikeService.wallOf(e.elapsedRealTimeNanos / 1_000_000, now),
                        act = name(e.activityType), enter = e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                    ),
                )
            }
        }
        if (ActivityRecognitionResult.hasResult(intent)) {
            ActivityRecognitionResult.extractResult(intent)?.let { r ->
                SpikeLog.append(
                    ctx,
                    SpikeEvent(
                        now, "act", et = LearnSpikeService.wallOf(r.elapsedRealtimeMillis, now),
                        acts = r.probableActivities.associate { name(it.type) to it.confidence },
                    ),
                )
            }
        }
    }

    companion object {
        const val ACTION_ACTIVITY = "learn_spike_activity"
        const val ACTION_MARK = "learn_spike_mark"
        const val EXTRA_MARK = "mark"

        fun name(type: Int): String = when (type) {
            DetectedActivity.IN_VEHICLE -> LearnSpike.IN_VEHICLE
            DetectedActivity.ON_FOOT -> LearnSpike.ON_FOOT
            DetectedActivity.WALKING -> LearnSpike.WALKING
            DetectedActivity.STILL -> LearnSpike.STILL
            DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
            DetectedActivity.RUNNING -> "RUNNING"
            DetectedActivity.TILTING -> "TILTING"
            else -> "UNKNOWN"
        }
    }
}

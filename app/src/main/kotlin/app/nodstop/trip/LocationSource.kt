package app.nodstop.trip

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.nodstop.engine.Fix
import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.cos
import kotlin.math.max
import kotlin.random.Random

/** Where fixes come from. Wall-clock times; fix age is measured on the monotonic clock. */
interface LocationSource {
    fun start(onFix: (Fix) -> Unit)
    fun stop()
}

class FusedLocationSource(context: Context) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission") // checked by the UI before a trip can start
    override fun start(onFix: (Fix) -> Unit) {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000)
            .setMinUpdateIntervalMillis(1_000)
            .setWaitForAccurateLocation(false)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) onFix(toFix(loc))
            }
        }
        callback = cb
        client.requestLocationUpdates(req, cb, Looper.getMainLooper())
    }

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    companion object {
        /**
         * The fix's age comes from elapsedRealtime (monotonic), not from comparing wall clocks,
         * so a phone clock a few seconds off can't make every fix look stale (or fresh).
         */
        fun toFix(loc: Location): Fix {
            val now = System.currentTimeMillis()
            val ageMs = max(0L, (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000)
            val acc = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE
            return Fix(now, now - ageMs, LatLng(loc.latitude, loc.longitude), acc)
        }
    }
}

/**
 * Couch-test source: a straight north-bound drive in real time. At wall time [startedMs] the bus is
 * [startKm] south of [origin], moving at [speedKmh]; it keeps going past every stop (so overshoot and
 * a second leg further north can be tested). A fix every 2 s with a little noise. Because position
 * depends only on the wall clock, a source recreated after a kill continues from the same place.
 */
class SimulatedLocationSource(
    private val origin: LatLng,
    private val startKm: Double,
    private val speedKmh: Double,
    private val startedMs: Long,
) : LocationSource {
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random(System.nanoTime())
    private var onFix: ((Fix) -> Unit)? = null

    private val emit = object : Runnable {
        override fun run() {
            val cb = onFix ?: return
            val now = System.currentTimeMillis()
            val northM = -startKm * 1000 + speedKmh / 3.6 * (now - startedMs) / 1000.0
            cb(Fix(now, now - 300, pointNorth(origin, northM + rnd.nextDouble(-3.0, 3.0), rnd.nextDouble(-3.0, 3.0)), 5f))
            handler.postDelayed(this, 2_000)
        }
    }

    override fun start(onFix: (Fix) -> Unit) {
        this.onFix = onFix
        handler.post(emit)
    }

    override fun stop() {
        onFix = null
        handler.removeCallbacks(emit)
    }

    companion object {
        /** Where simulated trips head (leg 1); leg 2 is [LEG2_KM] further north. */
        val SIM_DEST = LatLng(-36.8485, 174.7633)
        const val LEG2_KM = 3.0

        fun pointNorth(p: LatLng, northM: Double, eastM: Double = 0.0): LatLng {
            val mPerDegLat = Math.PI * Geo.EARTH_RADIUS_M / 180
            val mPerDegLng = mPerDegLat * cos(Math.toRadians(p.lat))
            return LatLng(p.lat + northM / mPerDegLat, p.lng + eastM / mPerDegLng)
        }
    }
}

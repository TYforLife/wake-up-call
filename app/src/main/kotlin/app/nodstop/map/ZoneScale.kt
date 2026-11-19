package app.nodstop.map

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The wake-zone slider (PLAN U3): 50 m–2 km on a square-root scale, so most of the track is the low range
 * people actually use (half the slider is under ~540 m). Plain Kotlin, tested on the JVM.
 */
object ZoneScale {
    const val MIN_M = 50.0
    const val MAX_M = 2_000.0
    val PRESETS = listOf(150.0, 250.0, 400.0, 700.0)

    /** Slider position 0..1 to metres, rounded to 10 m below 500 m and 50 m above. */
    fun toMeters(pos: Float): Double {
        val p = pos.coerceIn(0f, 1f).toDouble()
        return round(MIN_M + (MAX_M - MIN_M) * p * p)
    }

    fun toPos(m: Double): Float = sqrt(((m.coerceIn(MIN_M, MAX_M) - MIN_M) / (MAX_M - MIN_M))).toFloat()

    fun round(m: Double): Double = if (m < 500) (m / 10).roundToInt() * 10.0 else (m / 50).roundToInt() * 50.0

    fun label(m: Double): String =
        if (m < 1_000) "${m.roundToInt()} m" else java.lang.String.format(java.util.Locale.US, "%.2f", m / 1000).trimEnd('0').trimEnd('.') + " km"
}

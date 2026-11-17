package app.nodstop.brief

/**
 * Whether the phone can afford the model right now (DESIGN §10). The model shares the process with the
 * trip service, so it steps aside rather than risk the trip being killed for memory or the phone
 * overheating. When it steps aside, the alarm screen shows the digest (W2); the alarm is never affected.
 */
object DeviceGate {
    const val MIN_BATTERY_PCT = 20

    /** Android's PowerManager thermal status: 0 none … 3 severe … 6 shutdown. Severe and above: no AI. */
    const val MAX_THERMAL_STATUS = 2

    /** Room to leave for the rest of the phone when loading. */
    const val HEADROOM_BYTES = 512L * 1024 * 1024

    /** Loading never needs more than this on top of the headroom (the runtime maps the file, it doesn't copy it). */
    const val MAX_LOAD_BYTES = 2L * 1024 * 1024 * 1024

    enum class Verdict(val reason: String?) {
        OK(null),
        LOW_BATTERY("battery under 20%"),
        HOT("phone too hot"),
        LOW_MEMORY("not enough free memory"),
    }

    fun check(
        batteryPct: Int?,
        thermalStatus: Int?,
        availMemBytes: Long?,
        systemLowMemory: Boolean,
        modelBytes: Long,
        modelLoaded: Boolean,
    ): Verdict = when {
        batteryPct != null && batteryPct < MIN_BATTERY_PCT -> Verdict.LOW_BATTERY
        thermalStatus != null && thermalStatus > MAX_THERMAL_STATUS -> Verdict.HOT
        systemLowMemory -> Verdict.LOW_MEMORY
        !modelLoaded && availMemBytes != null && availMemBytes < minOf(modelBytes, MAX_LOAD_BYTES) + HEADROOM_BYTES -> Verdict.LOW_MEMORY
        else -> Verdict.OK
    }
}

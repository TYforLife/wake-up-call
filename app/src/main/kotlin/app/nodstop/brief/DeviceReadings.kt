package app.nodstop.brief

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/** What [DeviceGate] needs from the phone right now. The one place both a trip and the Briefing lab read it. */
data class DeviceReadings(
    val batteryPct: Int?,
    val thermalStatus: Int,
    val availMem: Long,
    val totalMem: Long,
    val lowMemory: Boolean,
) {
    fun verdict(modelBytes: Long, modelLoaded: Boolean): DeviceGate.Verdict =
        DeviceGate.check(batteryPct, thermalStatus, availMem, lowMemory, modelBytes, modelLoaded)

    companion object {
        fun read(ctx: Context): DeviceReadings {
            val mem = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            // Int.MIN_VALUE when the phone doesn't report it.
            val battery = ctx.getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
            val thermal = ctx.getSystemService(PowerManager::class.java).currentThermalStatus
            return DeviceReadings(battery, thermal, mem.availMem, mem.totalMem, mem.lowMemory)
        }
    }
}

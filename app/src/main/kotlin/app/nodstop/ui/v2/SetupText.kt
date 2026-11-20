package app.nodstop.ui.v2

import app.nodstop.ui.Readiness

/** One row on Setup: what it is, why in five words, and whether it's allowed. */
data class SetupRowUi(val id: String, val title: String, val subtitle: String, val ok: Boolean)

/** The words on Setup (mockup `setup`) and Settings (mockup `settings`). Pure. */
object SetupText {
    /**
     * The mockup's rows, in its order, then Over other apps (Run 10), the alarm channel (only when it's off),
     * battery (always on Samsung) and volume (only when it's a problem).
     */
    fun rows(checks: List<Readiness.Check>, samsung: Boolean): List<SetupRowUi> {
        val by = checks.associateBy { it.id }
        val out = ArrayList<SetupRowUi>()
        fun add(id: String, title: String, subtitle: String) {
            by[id]?.let { out += SetupRowUi(id, title, subtitle, it.ok) }
        }
        add(Readiness.NOTIFICATIONS, "Notifications", "To show the alarm")
        add(Readiness.FULL_SCREEN, "Over the lock screen", "So you can stop it without unlocking")
        add(Readiness.EXACT, "Exact alarms", "So it rings on time")
        add(Readiness.BUZZ, "Buzz on Mute", "So it vibrates even when muted")
        add(Readiness.LOCATION, "Location while riding", "Only during a trip you start")
        add(Readiness.OVERLAY, "Over other apps", "So the alarm opens while you use the phone")
        if (by[Readiness.ALARM_CHANNEL]?.ok == false) add(Readiness.ALARM_CHANNEL, "Alarm notifications", "Turned off for the alarm: turn it back on")
        val battery = by[Readiness.BATTERY]
        if (battery != null && (samsung || !battery.ok)) {
            add(Readiness.BATTERY, "Keep running", if (samsung) "Battery Unrestricted, and in Never sleeping apps" else "So GPS isn't paused on the way")
        }
        if (by[Readiness.VOLUME]?.ok == false) add(Readiness.VOLUME, "Alarm volume", "For the speaker, if it rings out loud")
        return out
    }

    fun canContinue(rows: List<SetupRowUi>): Boolean = rows.all { it.ok }

    /** "Mum, Daycare"; "None yet". */
    fun contacts(names: List<String>): String = if (names.isEmpty()) "None yet" else names.joinToString(", ")

    /** The Samsung steps no API can check, shown under Setup's rows on a Samsung phone. */
    val SAMSUNG_STEPS = listOf(
        "Settings → Apps → Nodstop → Battery → Unrestricted",
        "Settings → Battery → Background usage limits → Never sleeping apps → add Nodstop",
        "Never Force stop Nodstop: Android cancels its alarms when you do",
    )
}

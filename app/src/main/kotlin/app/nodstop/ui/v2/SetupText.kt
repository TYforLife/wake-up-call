package app.nodstop.ui.v2

import app.nodstop.ui.Readiness

/** One row on Setup: what it is, why in five words, and whether it's allowed. */
data class SetupRowUi(val id: String, val title: String, val subtitle: String, val ok: Boolean)

/** The words on Setup (mockup `setup`) and Settings (mockup `settings`). Pure. */
/** Where a first run goes (BACKLOG F34): home and work by address first, then the permissions, in one go. */
object FirstRun {
    enum class Step { HOME_WORK, SETUP, NONE }

    /** At launch: no route can start yet → home and work; else permissions still off and never shown → Setup. */
    fun atLaunch(anyRouteReady: Boolean, setupSeen: Boolean, needsAttention: Boolean): Step = when {
        !anyRouteReady -> Step.HOME_WORK
        !setupSeen && needsAttention -> Step.SETUP
        else -> Step.NONE
    }

    /** After home and work: the permissions the alarm needs, if any are off and Setup was never shown. */
    fun afterHomeWork(setupSeen: Boolean, needsAttention: Boolean): Step =
        if (!setupSeen && needsAttention) Step.SETUP else Step.NONE
}

object SetupText {
    /** Alarm sound → Buzz my watch (BACKLOG F3): what the watch's own app needs, in one line. */
    const val WATCH_STEPS = "Keeps buzzing until you're awake. Turn on Sleepers in the COROS app: Profile → Settings → Notifications."
    /** Settings' "Alarm sound" line: the sound's name, or your file's. */
    fun sound(id: String?, customName: String?): String {
        val s = app.nodstop.alarm.Sounds.Id.of(id)
        return if (s == app.nodstop.alarm.Sounds.Id.CUSTOM) "Your own: ${customName ?: "a file"}" else s.label
    }

    /** Settings' "Live buses" line (BACKLOG F13). */
    fun liveBuses(hasKey: Boolean): String = if (hasKey) "On, with your AT key" else "Off: add your AT key"

    /** What a key check found, in words. */
    fun liveCheck(c: app.nodstop.data.AtLive.Check): String = when (c) {
        is app.nodstop.data.AtLive.Check.Ok -> "AT took your key: ${"%,d".format(java.util.Locale.US, c.vehicles)} buses and trains are live right now."
        app.nodstop.data.AtLive.Check.Rejected ->
            "AT didn't accept that key. Check it's the Primary key of a Realtime subscription, then remove it and paste it again."
        is app.nodstop.data.AtLive.Check.Down ->
            "AT's server isn't answering${c.code?.let { " ($it)" }.orEmpty()}. Your key is saved; check again later. The alarm works without it."
    }

    /** "6,888 stops". */
    fun stops(n: Int): String = "%,d stops".format(java.util.Locale.US, n)

    /** "12 s", "20 s". */
    fun seconds(s: Double): String = "${Math.round(s)} s"

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
        if (by[Readiness.ALARM_CHANNEL]?.ok == false) add(Readiness.ALARM_CHANNEL, "Alarm notifications", "Turned off for the alarm: turn it back on")
        val battery = by[Readiness.BATTERY]
        if (battery != null && (samsung || !battery.ok)) {
            add(Readiness.BATTERY, "Keep running", if (samsung) "Battery Unrestricted, and in Never sleeping apps" else "Battery → Unrestricted, so GPS isn't paused")
        }
        if (by[Readiness.VOLUME]?.ok == false) add(Readiness.VOLUME, "Alarm volume", "For the speaker, if it rings out loud")
        return out
    }

    fun canContinue(rows: List<SetupRowUi>): Boolean = rows.all { it.ok }

    /** "Mum, Daycare"; "None yet". */
    fun contacts(names: List<String>): String = if (names.isEmpty()) "None yet" else names.joinToString(", ")

    /** The Samsung steps no API can check, shown under Setup's rows on a Samsung phone. */
    val SAMSUNG_STEPS = listOf(
        "Settings → Apps → Sleepers → Battery → Unrestricted",
        "Settings → Battery → Background usage limits → Never sleeping apps → add Sleepers",
        "Never Force stop Sleepers: Android cancels its alarms when you do",
    )
}

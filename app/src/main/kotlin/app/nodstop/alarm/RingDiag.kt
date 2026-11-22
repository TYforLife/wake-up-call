package app.nodstop.alarm

import android.os.SystemClock
import app.nodstop.isolated
import app.nodstop.App
import app.nodstop.engine.TraceEvent

/** Records each ring as a [RingReport]: in the trip log ("ringd") and as the "Last ring" summary that Settings → Report a missed alarm shares. */
object RingDiag {
    @Volatile private var current: RingReport? = null
    @Volatile private var startedElapsed = 0L

    fun start(
        screenWasOn: Boolean, fullScreenAllowed: Boolean, earbuds: Boolean, screenWoken: Boolean, test: Boolean,
        ringer: String? = null, dnd: String? = null, buzz: RingMode.Buzz? = null, speakerAfterS: Int? = null,
        channel: String? = null, overlay: Boolean? = null, quiet: Boolean? = null, cold: Boolean? = null,
    ) {
        startedElapsed = SystemClock.elapsedRealtime()
        current = RingReport(
            System.currentTimeMillis(), screenWasOn, fullScreenAllowed, earbuds, screenWoken, test,
            ringer = ringer, dnd = dnd, buzz = buzz, speakerAfterS = speakerAfterS,
            channel = channel, overlay = overlay, quiet = quiet, cold = cold,
        )
        save()
    }

    /** The alarm screen is on screen. */
    fun shown() {
        val r = current ?: return
        if (r.shownAfterMs != null || r.ackSource != null) return
        current = r.copy(shownAfterMs = SystemClock.elapsedRealtime() - startedElapsed)
        save()
    }

    /** "I'm awake": hold, volume key or notification button. */
    fun acked(source: String) {
        val r = current ?: return
        if (r.ackSource != null) return
        current = r.copy(ackSource = source, ackAfterMs = SystemClock.elapsedRealtime() - startedElapsed)
        save()
    }

    private fun save() {
        val r = current ?: return
        // The report must never stand in the ring's way: a log that can't be written is skipped.
        isolated("ring report") {
            val app = App.instance
            app.settings.setLastRing(r.summary())
            // A ring that cold-started the app: write into the saved trip's log, so the verdict sees it (PLAN A0).
            if (!app.traces.hasCurrent) app.trips.load()?.let { app.traces.continueTrip(it.traceFile) }
            App.instance.traces.write(listOf(TraceEvent(System.currentTimeMillis(), "ringd", msg = r.encode())))
        }
    }
}

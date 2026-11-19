package app.nodstop.alarm

import android.os.SystemClock
import app.nodstop.App
import app.nodstop.engine.TraceEvent

/** Records each ring as a [RingReport]: in the trip log ("ringd") and as Readiness's "Last ring" line. */
object RingDiag {
    @Volatile private var current: RingReport? = null
    @Volatile private var startedElapsed = 0L

    fun start(
        screenWasOn: Boolean, fullScreenAllowed: Boolean, earbuds: Boolean, screenWoken: Boolean, test: Boolean,
        ringer: String? = null, dnd: String? = null, buzz: RingMode.Buzz? = null, speakerAfterS: Int? = null,
    ) {
        startedElapsed = SystemClock.elapsedRealtime()
        current = RingReport(
            System.currentTimeMillis(), screenWasOn, fullScreenAllowed, earbuds, screenWoken, test,
            ringer = ringer, dnd = dnd, buzz = buzz, speakerAfterS = speakerAfterS,
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
        runCatching {
            App.instance.settings.setLastRing(r.summary())
            App.instance.traces.write(listOf(TraceEvent(System.currentTimeMillis(), "ringd", msg = r.encode())))
        }
    }
}

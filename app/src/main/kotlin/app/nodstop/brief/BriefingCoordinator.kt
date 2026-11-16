package app.nodstop.brief

import android.content.Context
import android.os.BatteryManager
import app.nodstop.App
import app.nodstop.notify.MessageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the briefing during a trip (DESIGN §10): asked every tick, [BatchPlan] decides; results go into
 * [MessageStore] for the alarm screen. Never touches the alarm (W1, W2): a slow, failing or killed model
 * only means the digest is shown.
 */
class BriefingCoordinator(private val ctx: Context, tripStartMs: Long) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var plan = BatchPlan.start(tripStartMs)
    private var job: Job? = null
    private var seenGeneration = -1
    @Volatile private var llm: LiteRtLlm? = null

    /** Called on the trip's tick (main thread) with the currently scheduled alarm. */
    fun onTick(nowMs: Long, alarmAtMs: Long?) {
        val store = MessageStore.instance
        val snap = store.snapshot()
        if (!snap.active) return
        if (snap.generation != seenGeneration) {
            if (seenGeneration >= 0) plan = BatchPlan.cleared(plan, nowMs)
            seenGeneration = snap.generation
        }
        val model = App.instance.models.current()
        val d = BatchPlan.decide(plan, nowMs, alarmAtMs, snap.added, battery(), model != null, job?.isActive == true)
        if (!d.run || model == null) return
        plan = BatchPlan.ran(plan, nowMs, snap.added, d.final)
        val messages = snap.messages
        val generation = snap.generation
        job = scope.launch {
            val l = llm ?: LiteRtLlm(model.path, ctx.cacheDir.absolutePath).also { llm = it }
            try {
                // Loading can take a while on first use; only the model call itself is limited.
                l.load()
                val lines = withTimeoutOrNull(BatchPlan.LLM_TIMEOUT_MS) { Briefer(l).brief(messages) }
                if (lines != null) store.mergeBriefed(generation, lines)
            } catch (_: Throwable) {
                // Any failure: the digest stays (W2).
            } finally {
                if (d.unloadAfter) unload()
            }
        }
    }

    fun stop() {
        job?.cancel()
        scope.cancel()
        unload()
    }

    private fun unload() {
        llm?.close()
        llm = null
    }

    private fun battery(): Int? = runCatching {
        ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }.getOrNull()
}

package app.nodstop.brief

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
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
 * Runs While You Slept during a trip (DESIGN §10), asked on every trip tick:
 * - urgent check first: new messages from VIPs go to the model one by one; an urgent one calls [onUrgent]
 *   (the trip sends `Input.Urgent`: the single ring path, earlier only, W1);
 * - otherwise [BatchPlan] decides when to brief; results go into [MessageStore] for the alarm screen.
 * One model job at a time. A slow, failing or killed model only means the digest is shown (W2).
 */
class BriefingCoordinator(
    private val ctx: Context,
    tripStartMs: Long,
    private val onUrgent: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var plan = BatchPlan.start(tripStartMs)
    private var job: Job? = null
    private var seenGeneration = -1
    private val checked = HashSet<String>()
    @Volatile private var llm: LiteRtLlm? = null

    /** Called on the trip's tick (main thread) with the currently scheduled alarm. */
    fun onTick(nowMs: Long, alarmAtMs: Long?) {
        val store = MessageStore.instance
        val snap = store.snapshot()
        if (!snap.active) return
        if (snap.generation != seenGeneration) {
            if (seenGeneration >= 0) plan = BatchPlan.cleared(plan, nowMs)
            seenGeneration = snap.generation
            synchronized(checked) { checked.clear() }
        }
        if (job?.isActive == true) return
        val model = App.instance.models.current() ?: return
        val battery = battery()
        // Memory, heat and battery first: the trip shares this process and must never be put at risk.
        val gate = deviceVerdict(battery, model.sizeBytes)
        if (gate != DeviceGate.Verdict.OK) {
            unload() // free the memory now; the digest covers everything
            return
        }
        val generation = snap.generation
        val settings = App.instance.settings

        // 1. Urgent check: only VIP messages, never seen before. Each gets ONE attempt, marked before the
        // call, so a timeout or a model error can't send the same message to the model every tick.
        val candidates = synchronized(checked) {
            Urgency.take(snap.messages, settings.vipNames.value, checked)
        }
        if (candidates.isNotEmpty()) {
            job = scope.launch {
                val l = llmFor(model)
                try {
                    l.load()
                    val urgent = withTimeoutOrNull(BatchPlan.LLM_TIMEOUT_MS) {
                        UrgencyChecker(l).firstUrgent(candidates)
                    }
                    if (urgent != null && store.markUrgent(generation, urgent.convKey)) onUrgent()
                } catch (_: Throwable) {
                    // Not urgent as far as we know: the stop alarm is unaffected either way.
                }
            }
            return
        }

        // 2. Briefing passes.
        val d = BatchPlan.decide(plan, nowMs, alarmAtMs, snap.added, battery, modelAvailable = true, running = false)
        if (!d.run) return
        plan = BatchPlan.ran(plan, nowMs, snap.added, d.final)
        val messages = snap.messages
        val previous = snap.briefed
        val tone = if (settings.teaTone.value) Tone.TEA else Tone.PLAIN
        job = scope.launch {
            val l = llmFor(model)
            try {
                // Loading can take a while on first use; only the model calls are limited.
                l.load()
                val lines = withTimeoutOrNull(BatchPlan.LLM_TIMEOUT_MS) { Briefer(l, tone).brief(messages, previous) }
                if (lines != null) store.mergeBriefed(generation, lines)
            } catch (_: Throwable) {
                // Any failure: the digest stays (W2).
            }
        }
    }

    /** The alarm rang: free the model's memory until the next pass needs it. */
    fun onRang() {
        if (job?.isActive != true) unload()
    }

    fun stop() {
        job?.cancel()
        scope.cancel()
        unload()
    }

    private fun llmFor(model: app.nodstop.model.ModelManager.Model): LiteRtLlm =
        llm ?: LiteRtLlm(model.path, ctx.cacheDir.absolutePath).also { llm = it }

    private fun unload() {
        llm?.close()
        llm = null
    }

    private fun deviceVerdict(battery: Int?, modelBytes: Long): DeviceGate.Verdict {
        val mem = runCatching { ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) } }.getOrNull()
        val thermal = runCatching { ctx.getSystemService(PowerManager::class.java).currentThermalStatus }.getOrNull()
        return DeviceGate.check(battery, thermal, mem?.availMem, mem?.lowMemory == true, modelBytes, modelLoaded = llm != null)
    }

    private fun battery(): Int? = runCatching {
        ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }.getOrNull()
}

package app.nodstop.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.nodstop.App
import app.nodstop.brief.BatchPlan
import app.nodstop.brief.BriefParser
import app.nodstop.brief.DeviceGate
import app.nodstop.brief.EvalRunner
import app.nodstop.brief.Llm
import app.nodstop.brief.Guardrails
import app.nodstop.brief.LiteRtLlm
import app.nodstop.brief.PromptBuilder
import app.nodstop.brief.SampleChats
import app.nodstop.model.LabExit
import app.nodstop.model.ModelManager
import app.nodstop.trip.TripService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One run of the lab: timings and what the model wrote for the made-up chats. */
private data class LabResult(
    val model: String,
    val backend: String? = null,
    val loadMs: Long?,
    val firstTokenMs: Long?,
    val totalMs: Long?,
    val raw: String?,
    /** (conversation title, line, passed the guardrails) */
    val lines: List<Triple<String, String, Boolean>>,
    val error: String?,
)

/**
 * Debug → Briefing lab (DESIGN §10, Run 2): import a Gemma `.litertlm` file and time it on made-up chats
 * (`SampleChats`, never yours). Decision C uses these numbers: the real model call is limited to 60 s.
 */
@Composable
fun BriefingLabScreen(go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val mm = App.instance.models
    val models by mm.models.collectAsState()
    val selected by mm.selected.collectAsState()
    val imp by mm.importState.collectAsState()
    val trip by TripService.status.collectAsState()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LabResult?>(null) }
    var eval by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { mm.import(uri) }
    }
    val current = mm.current()
    // A run that closed the app last time: say why, once (it's read when the lab opens in a fresh start).
    val lastExit = remember { lastRunExit(ctx) }
    val verified = remember(result, running) { App.instance.settings.modelVerified }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopBar("Briefing lab", onBack = { go(Screen.Debug) })
        Section("Model") {
            Text(
                "Download a Gemma .litertlm file on this phone (Gemma 4 E2B; Gemma 3 1B if that's too slow), then import " +
                    "it here. Nodstop keeps a copy in its own storage and never downloads anything itself.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (models.isEmpty()) Text("No model yet: the alarm screen shows the plain digest.", style = MaterialTheme.typography.bodySmall)
            for (m in models) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = m.name == (selected ?: current?.name), onClick = { mm.select(m.name) })
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall)
                        Text("${ModelManager.mb(m.sizeBytes)} MB", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(enabled = !running, onClick = { mm.delete(m.name) }) { Text("Delete") }
                }
            }
            Button(enabled = !imp.running && !running, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import a model file") }
            if (imp.running) {
                val total = imp.totalBytes
                Text(
                    "Copying… ${ModelManager.mb(imp.copiedBytes)}" + (total?.let { " of ${ModelManager.mb(it)}" } ?: "") + " MB",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            imp.error?.let { Text(it, color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodySmall) }
        }
        Section("Phone now") {
            val status = remember(models, running, result, eval) { deviceStatus(ctx, current?.sizeBytes) }
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        lastExit?.let { x ->
            Section("Last run closed the app") {
                Text(x.title, color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodyMedium)
                Text(x.advice, style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("Run on sample chats") {
            val fits = remember(models, running) { current?.let { memoryFits(ctx, it.sizeBytes) } ?: true }
            if (current != null && !fits) Text(
                "This phone may not have enough free memory for this model: it can close the app. Close other apps first, " +
                    "or import a smaller model (Gemma 3 1B).",
                color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodySmall,
            )
            if (current != null) Text(
                if (current.name == verified) "This model has run on this phone: trips use it." else "Trips show the plain digest until this model finishes a run here.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Made-up chats, never yours. The first load can take a minute or more. " +
                    "During a trip the briefing waits at most ${BatchPlan.LLM_TIMEOUT_MS / 1000} s for an answer.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(enabled = current != null && !running && trip == null && !imp.running, onClick = {
                val m = current ?: return@Button
                running = true
                result = null
                scope.launch {
                    result = withContext(Dispatchers.Default) { runLab(m, ctx.cacheDir.absolutePath) }
                    running = false
                }
            }) { Text(if (running) "Running…" else if (current != null && !memoryFits(ctx, current.sizeBytes)) "Run anyway" else "Run") }
            if (trip != null) Text("Not during a trip.", style = MaterialTheme.typography.bodySmall)
            result?.let { r -> LabResultView(r) }
        }
        Section("Quality eval") {
            Text(
                "20 made-up conversations, each with facts a good line must keep. Scores facts kept and lines that " +
                    "invented something (those would be dropped on the alarm screen). Takes a few minutes.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(enabled = current != null && !running && trip == null && !imp.running, onClick = {
                val m = current ?: return@Button
                running = true
                eval = null
                scope.launch {
                    eval = withContext(Dispatchers.Default) { runEval(m, ctx.cacheDir.absolutePath) }
                    running = false
                }
            }) { Text(if (running) "Running…" else "Run eval") }
            eval?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private suspend fun runLab(m: ModelManager.Model, cacheDir: String): LabResult {
    val settings = App.instance.settings
    val llm = LiteRtLlm(m.path, cacheDir, settings.useGpu.value)
    // If this run takes the app down, the next start explains why (LabExit).
    settings.setLabRunning(m.name, System.currentTimeMillis())
    return try {
        val loadMs = llm.load()
        // Survived a GPU load on this phone: trips may now try the GPU too (see Settings.gpuVerified).
        if (App.instance.settings.useGpu.value) App.instance.settings.setGpuVerified(llm.backendUsed == "GPU")
        val convs = PromptBuilder.conversations(SampleChats.messages)
        val raw = withTimeoutOrNull(2 * BatchPlan.LLM_TIMEOUT_MS) { llm.generate(PromptBuilder.build(convs), PromptBuilder.maxTokens(convs)) }
        val s = llm.lastStats
        val parsed = raw?.let { BriefParser.parse(it, convs.map { c -> c.id }.toSet()) }.orEmpty()
        val lines = convs.mapNotNull { c ->
            val l = parsed[c.id] ?: return@mapNotNull null
            Triple(c.title, l.line, Guardrails.passes(l.line, SampleChats.messages.filter { it.convKey == c.convKey }, c.title))
        }
        // It loaded and answered on this phone: trips may use it now (until then they show the plain digest).
        if (raw != null) settings.setModelVerified(m.name)
        LabResult(m.name, llm.backendUsed, loadMs, s.firstTokenMs, if (raw == null) null else s.totalMs, raw, lines,
            if (raw == null) "No answer within ${2 * BatchPlan.LLM_TIMEOUT_MS / 1000} s." else null)
    } catch (t: Throwable) {
        LabResult(m.name, llm.backendUsed, null, null, null, null, emptyList(), "${t::class.simpleName}: ${t.message}")
    } finally {
        settings.setLabRunning(null)
        llm.close()
    }
}

/**
 * The lab's last run didn't finish because the app was closed (the owner's Galaxy A17): why, from Android's own
 * record of how the process ended. Null if the last run finished, or nothing was recorded.
 */
private fun lastRunExit(ctx: android.content.Context): LabExit.Verdict? {
    val settings = App.instance.settings
    val (_, startedMs) = settings.labRunning ?: return null
    settings.setLabRunning(null)
    val am = ctx.getSystemService(android.app.ActivityManager::class.java)
    val exit = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5) }.getOrNull()
        ?.firstOrNull { it.timestamp >= startedMs - 1_000 }
        ?: return LabExit.Verdict("The last run didn't finish: the app was closed while the model loaded.", "Try again with nothing else open, or try a smaller model (Gemma 3 1B).", true)
    return LabExit.of(exit.reason, exit.status, exit.description, exit.pss, settings.useGpu.value)
}

/** Enough free memory to load this model now, with room left for the rest of the phone (DeviceGate). */
private fun memoryFits(ctx: android.content.Context, modelBytes: Long): Boolean {
    val mem = android.app.ActivityManager.MemoryInfo().also { ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
    return DeviceGate.check(null, null, mem.availMem, mem.lowMemory, modelBytes, modelLoaded = false) == DeviceGate.Verdict.OK
}

/** Free memory, heat and battery, and whether a trip's briefing would run now (DeviceGate). */
private fun deviceStatus(ctx: android.content.Context, modelBytes: Long?): String {
    val mem = android.app.ActivityManager.MemoryInfo().also { ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
    val thermal = runCatching { ctx.getSystemService(android.os.PowerManager::class.java).currentThermalStatus }.getOrNull()
    val battery = runCatching {
        ctx.getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrNull()
    val verdict = modelBytes?.let { DeviceGate.check(battery, thermal, mem.availMem, mem.lowMemory, it, modelLoaded = false) }
    return "Memory ${ModelManager.mb(mem.availMem)} MB free of ${ModelManager.mb(mem.totalMem)} MB · model ${modelBytes?.let { ModelManager.mb(it) } ?: "–"} MB · thermal status ${thermal ?: "?"} (3+ stops AI) · battery ${battery ?: "?"}%\n" +
        when {
            verdict == null -> "No model imported."
            verdict == DeviceGate.Verdict.OK -> "A trip's briefing would run now."
            else -> "A trip's briefing would step aside now: ${verdict.reason}. The digest is shown instead."
        }
}

/** The eval's score as text for the lab (and to paste into the PR). */
private suspend fun runEval(m: ModelManager.Model, cacheDir: String): String {
    val llm = LiteRtLlm(m.path, cacheDir, App.instance.settings.useGpu.value)
    App.instance.settings.setLabRunning(m.name, System.currentTimeMillis())
    return try {
        llm.load()
        val t0 = android.os.SystemClock.elapsedRealtime()
        val limited = Llm { p, max -> withTimeoutOrNull(2 * BatchPlan.LLM_TIMEOUT_MS) { llm.generate(p, max) } ?: "" }
        val r = EvalRunner(limited).run()
        val secs = (android.os.SystemClock.elapsedRealtime() - t0) / 1000
        buildString {
            appendLine("${m.name} (${llm.backendUsed}): facts kept ${r.factsKept} of ${r.factsTotal} (${r.keptPercent}%), invented ${r.invented}, answered ${r.answered} of ${r.results.size}, ${secs} s")
            for (c in r.results.filter { it.invented || it.missed.isNotEmpty() }) {
                appendLine("• ${c.id}: ${if (c.invented) "invented a fact" else "missed ${c.missed.joinToString()}"}${c.line?.let { " — \"$it\"" } ?: ""}")
            }
        }.trim()
    } catch (t: Throwable) {
        "Eval failed: ${t::class.simpleName}: ${t.message}"
    } finally {
        App.instance.settings.setLabRunning(null)
        llm.close()
    }
}

@Composable
private fun LabResultView(r: LabResult) {
    fun s(ms: Long?) = ms?.let { "%.1f s".format(it / 1000.0) } ?: "–"
    Text("${r.model} on ${r.backend ?: "?"}", style = MaterialTheme.typography.titleSmall)
    Text("Load ${s(r.loadMs)} · first token ${s(r.firstTokenMs)} · answer ${s(r.totalMs)}", style = MaterialTheme.typography.bodyMedium)
    val total = r.totalMs
    if (total != null) Text(
        if (total <= BatchPlan.LLM_TIMEOUT_MS) "Within the ${BatchPlan.LLM_TIMEOUT_MS / 1000} s limit." else "Too slow for the ${BatchPlan.LLM_TIMEOUT_MS / 1000} s limit: try Gemma 3 1B.",
        color = if (total <= BatchPlan.LLM_TIMEOUT_MS) Color(0xFF2E7D32) else Color(0xFFD32F2F),
        style = MaterialTheme.typography.bodySmall,
    )
    r.error?.let { Text(it, color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodySmall) }
    for ((title, line, ok) in r.lines) {
        Text("${if (ok) "✓" else "✗ invented a fact:"} $title", style = MaterialTheme.typography.titleSmall)
        Text(line, style = MaterialTheme.typography.bodySmall)
    }
    r.raw?.let { Text("Raw answer:\n$it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
}

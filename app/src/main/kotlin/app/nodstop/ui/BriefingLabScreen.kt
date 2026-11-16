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
import app.nodstop.brief.Guardrails
import app.nodstop.brief.LiteRtLlm
import app.nodstop.brief.PromptBuilder
import app.nodstop.brief.SampleChats
import app.nodstop.model.ModelManager
import app.nodstop.trip.TripService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One run of the lab: timings and what the model wrote for the made-up chats. */
private data class LabResult(
    val model: String,
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { mm.import(uri) }
    }
    val current = mm.current()

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
        Section("Run on sample chats") {
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
            }) { Text(if (running) "Running…" else "Run") }
            if (trip != null) Text("Not during a trip.", style = MaterialTheme.typography.bodySmall)
            result?.let { r -> LabResultView(r) }
        }
    }
}

private suspend fun runLab(m: ModelManager.Model, cacheDir: String): LabResult {
    val llm = LiteRtLlm(m.path, cacheDir)
    return try {
        val loadMs = llm.load()
        val convs = PromptBuilder.conversations(SampleChats.messages)
        val raw = withTimeoutOrNull(2 * BatchPlan.LLM_TIMEOUT_MS) { llm.generate(PromptBuilder.build(convs), PromptBuilder.maxTokens(convs)) }
        val s = llm.lastStats
        val parsed = raw?.let { BriefParser.parse(it, convs.map { c -> c.id }.toSet()) }.orEmpty()
        val lines = convs.mapNotNull { c ->
            val l = parsed[c.id] ?: return@mapNotNull null
            Triple(c.title, l.line, Guardrails.passes(l.line, SampleChats.messages.filter { it.convKey == c.convKey }, c.title))
        }
        LabResult(m.name, loadMs, s.firstTokenMs, if (raw == null) null else s.totalMs, raw, lines,
            if (raw == null) "No answer within ${2 * BatchPlan.LLM_TIMEOUT_MS / 1000} s." else null)
    } catch (t: Throwable) {
        LabResult(m.name, null, null, null, null, emptyList(), "${t::class.simpleName}: ${t.message}")
    } finally {
        llm.close()
    }
}

@Composable
private fun LabResultView(r: LabResult) {
    fun s(ms: Long?) = ms?.let { "%.1f s".format(it / 1000.0) } ?: "–"
    Text(r.model, style = MaterialTheme.typography.titleSmall)
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

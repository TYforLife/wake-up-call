package app.nodstop.learn

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.nodstop.engine.LearnSpike
import app.nodstop.engine.SpikeEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * L0 Learn spike's recording (docs/LEARN.md): JSONL in files/learn, one file per ride, flushed per line so a kill
 * loses nothing. Kept apart from files/traces, so the trip log and "Report a missed alarm" never pick it up.
 * Stays on the phone; it leaves only when the rider shares it, location hidden.
 */
object SpikeLog {
    private const val PREFS = "learn_spike"
    private const val KEY_CURRENT = "current"

    private val _events = MutableStateFlow<List<SpikeEvent>>(emptyList())
    /** The ride being recorded, or the last one recorded. */
    val events: StateFlow<List<SpikeEvent>> = _events

    private fun dir(ctx: Context) = File(ctx.filesDir, "learn").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The file being recorded into, or null when no recording is running. */
    fun current(ctx: Context): File? = prefs(ctx).getString(KEY_CURRENT, null)?.let { File(dir(ctx), it) }

    fun begin(ctx: Context, nowMs: Long): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        val f = File(dir(ctx), "spike-$stamp.jsonl")
        prefs(ctx).edit().putString(KEY_CURRENT, f.name).apply()
        _events.value = emptyList()
        return f
    }

    fun finish(ctx: Context) {
        prefs(ctx).edit().remove(KEY_CURRENT).apply()
    }

    @Synchronized
    fun append(ctx: Context, e: SpikeEvent) {
        val f = current(ctx) ?: return
        try {
            f.appendText(LearnSpike.encode(e) + "\n")
        } catch (_: IOException) {
            return // a full disk: the spike loses a line, nothing else is affected
        }
        _events.value = _events.value + e
    }

    fun last(ctx: Context): File? = current(ctx) ?: dir(ctx).listFiles { f -> f.name.endsWith(".jsonl") }?.maxByOrNull { it.name }

    /** Shows the last recording when the screen opens and nothing is in memory (after a restart). */
    fun loadLast(ctx: Context) {
        if (_events.value.isNotEmpty()) return
        val f = last(ctx) ?: return
        _events.value = read(f)
    }

    private fun read(f: File): List<SpikeEvent> = try {
        LearnSpike.decodeAll(f.readLines())
    } catch (_: IOException) {
        emptyList()
    }

    /** Share the last recording, every position moved by one random far offset (safe for the public repo). */
    fun shareIntent(ctx: Context): Intent? {
        val raw = last(ctx) ?: return null
        val events = read(raw)
        val rnd = java.util.Random()
        val dLat = (5 + rnd.nextDouble() * 5) * (if (rnd.nextBoolean()) 1 else -1)
        val dLng = (10 + rnd.nextDouble() * 10) * (if (rnd.nextBoolean()) 1 else -1)
        val out = File(File(ctx.cacheDir, "share").apply { mkdirs() }, raw.name.removeSuffix(".jsonl") + "-hidden.jsonl")
        try {
            out.writeText(LearnSpike.anonymize(events, dLat, dLng).joinToString("\n", postfix = "\n") { LearnSpike.encode(it) })
        } catch (_: IOException) {
            return null
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, out.name)
            putExtra(Intent.EXTRA_TEXT, LearnSpike.reportLines(LearnSpike.report(events)).joinToString("\n"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share ${out.name}")
    }
}

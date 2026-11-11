package app.nodstop.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.nodstop.engine.Trace
import app.nodstop.engine.TraceEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** JSONL per trip in files/traces (DESIGN §4 trace format). Flushed on every line so a kill loses nothing. */
class TraceLogger(private val context: Context) {
    val dir: File = File(context.filesDir, "traces").apply { mkdirs() }
    private var current: File? = null
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    /** The last 200 lines written in this process (or read back from the last trip). */
    val recent: StateFlow<List<String>> = _recent

    fun newTrip(startMs: Long): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startMs))
        return File(dir, "trip-$stamp.jsonl").also { current = it }
    }

    fun continueTrip(name: String): File = File(dir, name).also { current = it }

    fun write(events: List<TraceEvent>) {
        val f = current ?: return
        val lines = events.map(Trace::encode)
        f.appendText(lines.joinToString("\n", postfix = "\n"))
        _recent.value = (_recent.value + lines).takeLast(200)
    }

    fun note(tMs: Long, msg: String) = write(listOf(TraceEvent(tMs, "note", msg = msg)))

    fun rate(fileName: String, tMs: Long, leg: Int, value: String) {
        File(dir, fileName).takeIf { it.exists() }?.appendText(Trace.encode(TraceEvent(tMs, "rate", leg = leg, msg = value)) + "\n")
    }

    fun endTrip() { current = null }

    fun lastTripFile(): File? = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.maxByOrNull { it.name }

    fun loadRecentFromDisk() {
        if (_recent.value.isNotEmpty()) return
        lastTripFile()?.let { f -> _recent.value = f.readLines().takeLast(200) }
    }

    fun shareIntent(): Intent? {
        val f = lastTripFile() ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share ${f.name}")
    }
}

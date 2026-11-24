package app.nodstop.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.nodstop.engine.Sleep
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

    /** A trip log is open in this process (a ring that cold-started the app has none until it opens the trip's). */
    val hasCurrent: Boolean get() = current != null

    fun write(events: List<TraceEvent>) {
        val f = current ?: return
        val lines = events.map(Trace::encode)
        f.appendText(lines.joinToString("\n", postfix = "\n"))
        _recent.value = (_recent.value + lines).takeLast(200)
    }

    fun note(tMs: Long, msg: String) = write(listOf(TraceEvent(tMs, "note", msg = msg)))

    /** While You Slept at a ring: counts only (e.g. "chats=4 model=3 ..."), never message text (W3). */
    fun wys(tMs: Long, counts: String) = write(listOf(TraceEvent(tMs, "wys", msg = counts)))

    fun rate(fileName: String, tMs: Long, leg: Int, value: String) {
        File(dir, fileName).takeIf { it.exists() }?.appendText(Trace.encode(TraceEvent(tMs, "rate", leg = leg, msg = value)) + "\n")
    }

    fun endTrip() { current = null }

    /**
     * Every trip's sleep on the bus (BACKLOG F30), counts only. A log is read once and its count kept with its
     * length, so a log that grew (a trip still running, or resumed) is read again.
     */
    fun sleepRides(): List<Sleep.Ride> {
        val cache = context.getSharedPreferences("sleep", Context.MODE_PRIVATE)
        val files = dir.listFiles { f -> f.name.startsWith("trip-") && f.name.endsWith(".jsonl") }.orEmpty()
        val edit = cache.edit()
        val rides = files.mapNotNull { f ->
            val len = f.length()
            val kept = cache.getString(f.name, null)?.split(',')?.mapNotNull { it.toLongOrNull() }
            if (kept != null && kept.size == 3 && kept[0] == len) return@mapNotNull Sleep.Ride(kept[1], kept[2])
            val ride = try { f.useLines { Sleep.ride(it) } } catch (_: java.io.IOException) { null } ?: return@mapNotNull null
            edit.putString(f.name, "$len,${ride.startMs},${ride.sleptMs}")
            ride
        }
        edit.apply()
        return rides
    }

    fun lastTripFile(): File? = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.maxByOrNull { it.name }

    fun loadRecentFromDisk() {
        if (_recent.value.isNotEmpty()) return
        lastTripFile()?.let { f -> _recent.value = f.readLines().takeLast(200) }
    }

    /**
     * Share the last trip. [hideLocation]: every position moved by a random offset (and the engine re-run),
     * safe to upload to the public repo as a regression test. Otherwise the raw log: keep it private.
     * [text] goes along as the message (the last-ring summary, for "Report a missed alarm"); with no trip yet,
     * only the text is shared.
     */
    fun shareIntent(hideLocation: Boolean = true, text: String? = null): Intent? {
        val raw = lastTripFile() ?: return text?.let {
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it), "Report a missed alarm")
        }
        val f = if (!hideLocation) raw else {
            val rnd = java.util.Random()
            val dLat = (5 + rnd.nextDouble() * 5) * (if (rnd.nextBoolean()) 1 else -1)
            val dLng = (10 + rnd.nextDouble() * 10) * (if (rnd.nextBoolean()) 1 else -1)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            File(dir, raw.name.removeSuffix(".jsonl") + "-hidden.jsonl").apply {
                writeText(Trace.anonymize(raw.readLines(), dLat, dLng).joinToString("\n", postfix = "\n"))
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share ${f.name}")
    }
}

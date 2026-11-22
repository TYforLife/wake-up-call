package app.nodstop.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.nodstop.App
import app.nodstop.engine.Learned
import kotlinx.serialization.Serializable
import java.io.File

/** Everything worth keeping, as one file (PLAN U1). Stops and learned rides are personal: it only leaves the phone if you share it. */
@Serializable
data class ExportBundle(
    val format: Int = FORMAT,
    val exportedAtMs: Long,
    val routes: List<Route>,
    val learned: Map<String, Learned>,
    val settings: SettingsSnapshot,
) {
    companion object { const val FORMAT = 1 }
}

@Serializable
data class SettingsSnapshot(
    val speakerLastResort: Boolean = false,
    val vipNames: List<String> = emptyList(),
    val teaTone: Boolean = false,
    val dndDuringTrips: Boolean = false,
    val useGpu: Boolean = false,
    val askTiming: Boolean = true,
)

object DataExport {
    fun encode(b: ExportBundle): String = storeJson.encodeToString(ExportBundle.serializer(), b)

    /** Null if it isn't a Nodstop export (or is from a newer format this version can't read). */
    fun decode(text: String): ExportBundle? = try {
        storeJson.decodeFromString(ExportBundle.serializer(), text).takeIf { it.format <= ExportBundle.FORMAT }
    } catch (_: IllegalArgumentException) {
        null // SerializationException is one
    }

    fun snapshot(nowMs: Long): ExportBundle {
        val app = App.instance
        val s = app.settings
        return ExportBundle(
            exportedAtMs = nowMs,
            routes = app.routes.routes.value,
            learned = app.learned.all.value,
            settings = SettingsSnapshot(s.speakerLastResort.value, s.vipNames.value, s.teaTone.value, s.dndDuringTrips.value, s.useGpu.value, s.askTiming.value),
        )
    }

    /** Replaces routes, learned rides and settings with the bundle's. */
    fun restore(b: ExportBundle) {
        val app = App.instance
        app.routes.replaceAll(b.routes)
        app.learned.replaceAll(b.learned)
        with(app.settings) {
            setSpeakerLastResort(b.settings.speakerLastResort)
            setVipNames(b.settings.vipNames)
            setTeaTone(b.settings.teaTone)
            setAskTiming(b.settings.askTiming)
            setDndDuringTrips(b.settings.dndDuringTrips)
            setUseGpu(b.settings.useGpu)
        }
    }

    /** Writes the export to the share cache and returns a share-sheet intent for it. */
    fun shareIntent(ctx: Context, nowMs: Long): Intent {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "nodstop-backup.json").apply { writeText(encode(snapshot(nowMs))) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Save your Nodstop backup")
    }
}

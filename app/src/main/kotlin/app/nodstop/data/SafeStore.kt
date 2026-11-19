package app.nodstop.data

import kotlinx.serialization.KSerializer
import java.io.File

/**
 * Loading and saving that never loses data (PLAN U1): every save keeps the previous file as `.bak`; a file
 * that can't be read is kept as `.bad-<time>` (never overwritten) and the last good backup is used instead.
 * Plain JVM code, tested with temporary folders.
 */
internal object SafeStore {
    sealed interface Loaded<out T> {
        val value: T
        data class Ok<T>(override val value: T) : Loaded<T>
        /** The main file was unreadable; this came from the backup (or the default). The bad file was kept. */
        data class Recovered<T>(override val value: T, val badCopy: File?, val fromBackup: Boolean) : Loaded<T>
        data class Missing<T>(override val value: T) : Loaded<T>
    }

    fun backup(file: File) = File(file.parentFile, "${file.name}.bak")

    fun <T> load(file: File, serializer: KSerializer<T>, nowMs: Long, default: () -> T): Loaded<T> {
        val bak = backup(file)
        if (!file.exists()) {
            return decode(bak, serializer)?.let { Loaded.Recovered(it, null, fromBackup = true) } ?: Loaded.Missing(default())
        }
        decode(file, serializer)?.let { return Loaded.Ok(it) }
        val bad = File(file.parentFile, "${file.name}.bad-$nowMs")
        val kept = runCatching { file.copyTo(bad, overwrite = true) }.getOrNull()
        decode(bak, serializer)?.let { return Loaded.Recovered(it, kept, fromBackup = true) }
        return Loaded.Recovered(default(), kept, fromBackup = false)
    }

    /** Keeps the current file as `.bak`, then writes atomically. */
    fun <T> save(file: File, serializer: KSerializer<T>, value: T) {
        if (file.exists()) runCatching { file.copyTo(backup(file), overwrite = true) }
        file.writeAtomically(storeJson.encodeToString(serializer, value))
    }

    private fun <T> decode(f: File, serializer: KSerializer<T>): T? =
        if (!f.exists()) null else runCatching { storeJson.decodeFromString(serializer, f.readText()) }.getOrNull()
}

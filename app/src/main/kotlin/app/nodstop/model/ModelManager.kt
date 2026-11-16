package app.nodstop.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device model files for While You Slept (DESIGN §10). The rider downloads a `.litertlm` file on the
 * phone and imports it here through the system file picker; the app copies it into its own storage and
 * never downloads anything itself.
 */
class ModelManager(private val ctx: Context) {
    data class Model(val name: String, val path: String, val sizeBytes: Long)
    data class ImportState(val running: Boolean = false, val copiedBytes: Long = 0, val totalBytes: Long? = null, val error: String? = null)

    private val dir = File(ctx.filesDir, "models").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("models", Context.MODE_PRIVATE)
    private val _models = MutableStateFlow(scan())
    val models: StateFlow<List<Model>> = _models
    private val _selected = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    val selected: StateFlow<String?> = _selected
    private val _import = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _import

    /** The model briefings use: the selected one, else the first imported. */
    fun current(): Model? = _models.value.let { list -> list.firstOrNull { it.name == _selected.value } ?: list.firstOrNull() }

    fun select(name: String) { prefs.edit().putString(KEY_SELECTED, name).apply(); _selected.value = name }

    fun delete(name: String) {
        File(dir, name).delete()
        if (_selected.value == name) { prefs.edit().remove(KEY_SELECTED).apply(); _selected.value = null }
        _models.value = scan()
    }

    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        val (name, size) = describe(uri)
        if (!name.endsWith(".litertlm")) {
            _import.value = ImportState(error = "Choose a .litertlm model file (e.g. Gemma 4 E2B).")
            return@withContext
        }
        if (size != null && dir.usableSpace < size + 200L * 1024 * 1024) {
            _import.value = ImportState(error = "Not enough free space: the model needs ${mb(size)} MB.")
            return@withContext
        }
        val tmp = File(dir, "$name.part")
        _import.value = ImportState(running = true, totalBytes = size)
        val result = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied % (32L shl 20) < n) _import.value = ImportState(running = true, copiedBytes = copied, totalBytes = size)
                    }
                }
            }
            val target = File(dir, name)
            target.delete()
            check(tmp.renameTo(target)) { "could not save the file" }
        }
        tmp.delete()
        _models.value = scan()
        result.onSuccess { select(name); _import.value = ImportState() }
            .onFailure { _import.value = ImportState(error = "Import failed: ${it.message}") }
    }

    private fun describe(uri: Uri): Pair<String, Long?> {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: "model.litertlm"
                val size = if (c.isNull(1)) null else c.getLong(1)
                return name.replace('/', '_') to size
            }
        }
        return "model.litertlm" to null
    }

    private fun scan(): List<Model> = dir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }.orEmpty()
        .sortedBy { it.name }.map { Model(it.name, it.absolutePath, it.length()) }

    companion object {
        private const val KEY_SELECTED = "selected"
        fun mb(bytes: Long) = bytes / (1024 * 1024)
    }
}

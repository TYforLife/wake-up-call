package app.nodstop.data

import app.nodstop.engine.Learned
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** PLAN U1: an update can never wipe routes or learned rides. */
class SafeStoreTest {
    @TempDir lateinit var dir: File
    private val routes = ListSerializer(Route.serializer())
    private val mine = listOf(Route(id = "r1", name = "To work", legs = listOf(LegDraft("Smales Farm", "-36.78, 174.75"))))

    @Test
    fun `a missing file gives the default, marked as missing`() {
        val l = SafeStore.load(File(dir, "routes.json"), routes, 1) { emptyList() }
        assertTrue(l is SafeStore.Loaded.Missing)
    }

    @Test
    fun `every save keeps the previous file as a backup`() {
        val f = File(dir, "routes.json")
        SafeStore.save(f, routes, mine)
        SafeStore.save(f, routes, emptyList())
        assertEquals(mine, storeJson.decodeFromString(routes, SafeStore.backup(f).readText()))
    }

    @Test
    fun `an unreadable file is kept, never overwritten, and the backup is used`() {
        val f = File(dir, "routes.json")
        SafeStore.save(f, routes, mine)
        SafeStore.save(f, routes, mine) // backup now holds mine too
        f.writeText("{ this is not json")
        val l = SafeStore.load(f, routes, 42) { emptyList() }
        assertTrue(l is SafeStore.Loaded.Recovered)
        assertEquals(mine, l.value)
        val bad = File(dir, "routes.json.bad-42")
        assertEquals("{ this is not json", bad.readText())
        SafeStore.save(f, routes, l.value)
        assertEquals("{ this is not json", bad.readText())
    }

    @Test
    fun `no backup either still keeps the bad file and gives the default`() {
        val f = File(dir, "learned.json").apply { writeText("[1,2") }
        val s = MapSerializer(String.serializer(), Learned.serializer())
        val l = SafeStore.load(f, s, 7) { emptyMap() }
        assertEquals(emptyMap<String, Learned>(), l.value)
        assertTrue(File(dir, "learned.json.bad-7").exists())
    }

    @Test
    fun `a lost main file falls back to its backup`() {
        val f = File(dir, "routes.json")
        SafeStore.save(f, routes, mine)
        SafeStore.save(f, routes, mine)
        f.delete()
        assertEquals(mine, SafeStore.load(f, routes, 1) { emptyList() }.value)
    }
}

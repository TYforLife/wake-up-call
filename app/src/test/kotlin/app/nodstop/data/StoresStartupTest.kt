package app.nodstop.data

import app.nodstop.engine.LatLng
import app.nodstop.engine.Learned
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The stores are built in App.onCreate: if constructing one throws, the app can't open at all (build 61:
 * RouteStore read its serializer before it was initialised). Build each the way the app does: on a fresh
 * install, and again over existing files (an update).
 */
class StoresStartupTest {
    @TempDir lateinit var dir: File

    @Test
    fun `fresh install - every store constructs, routes are seeded and saved`() {
        val routes = RouteStore(dir)
        assertEquals(Route.seeds().map { it.name }, routes.routes.value.map { it.name })
        assert(File(dir, "routes.json").exists())
        assertEquals(emptyMap<String, Learned>(), LearnedStore(dir).all.value)
        assertNull(TripStore(dir).load())
    }

    @Test
    fun `update - existing files load in a new process`() {
        RouteStore(dir).upsert(Route(name = "Evening", legs = listOf(LegDraft("Stop", "-36.8, 174.7"))))
        LearnedStore(dir).rate(LatLng(-36.8, 174.7), "too_late")
        val routes = RouteStore(dir).routes.value
        assert(routes.any { it.name == "Evening" })
        assertEquals(1, LearnedStore(dir).all.value.size)
    }
}

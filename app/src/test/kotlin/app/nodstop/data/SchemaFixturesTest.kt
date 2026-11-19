package app.nodstop.data

import app.nodstop.engine.Learned
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * PLAN U1: files written by every shipped version must still load. Add a fixture here whenever a stored
 * class changes. (Made-up coordinates; never real stops.)
 */
class SchemaFixturesTest {
    private val routes = ListSerializer(Route.serializer())
    private val learned = MapSerializer(String.serializer(), Learned.serializer())

    /** v0.2 (#1): legs had name, coords, alarmEnabled. */
    private val routesV1 = """[{"id":"a","name":"To work","legs":[{"name":"Stop D","coords":"-36.70000, 174.70000","alarmEnabled":true}]}]"""

    /** #8: legs gained wakeRadiusM. */
    private val routesV2 = """[{"id":"a","name":"To work","legs":[{"name":"Stop D","coords":"-36.70000, 174.70000","alarmEnabled":true,"wakeRadiusM":300.0}]}]"""

    /** #1: stop, tracks, rides. */
    private val learnedV1 = """{"-36.70000,174.70000":{"stop":{"lat":-36.7,"lng":174.7},"tracks":[{"points":[{"lat":-36.71,"lng":174.7,"toEndM":1100.0,"toEndS":120.0}]}],"rides":2}}"""

    /** #7: gained leadS. */
    private val learnedV2 = """{"-36.70000,174.70000":{"stop":{"lat":-36.7,"lng":174.7},"tracks":[],"rides":3,"leadS":55.0}}"""

    @Test
    fun `routes from every version load`() {
        val v1 = storeJson.decodeFromString(routes, routesV1).single()
        assertEquals("Stop D", v1.legs.single().name)
        assertNull(v1.legs.single().wakeRadiusM)
        assertEquals(300.0, storeJson.decodeFromString(routes, routesV2).single().legs.single().wakeRadiusM)
    }

    @Test
    fun `learned rides from every version load`() {
        val v1 = storeJson.decodeFromString(learned, learnedV1).values.single()
        assertEquals(2, v1.rides)
        assertNull(v1.leadS)
        assertEquals(55.0, storeJson.decodeFromString(learned, learnedV2).values.single().leadS)
    }

    @Test
    fun `fields from a newer version are ignored, not fatal`() {
        val newer = routesV2.replace("\"wakeRadiusM\":300.0", "\"wakeRadiusM\":300.0,\"futureField\":true")
        assertEquals(1, storeJson.decodeFromString(routes, newer).size)
    }
}

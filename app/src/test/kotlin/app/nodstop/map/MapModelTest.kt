package app.nodstop.map

import app.nodstop.data.LegDraft
import app.nodstop.data.Route
import app.nodstop.engine.LatLng
import app.nodstop.engine.Learned
import app.nodstop.engine.Phase
import app.nodstop.engine.Track
import app.nodstop.engine.TrackPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapModelTest {
    @Test fun `you are a person while waiting or off the bus, a bus once on it`() {
        assertEquals(MapModel.Me.PERSON, MapModel.meKind(null, null))
        assertEquals(MapModel.Me.PERSON, MapModel.meKind(Phase.WAITING, 3.0))
        assertEquals(MapModel.Me.BUS, MapModel.meKind(Phase.RIDING, 40.0))
        assertEquals(MapModel.Me.BUS, MapModel.meKind(Phase.AWAKE, 30.0), "still on the bus after I'm awake")
        assertEquals(MapModel.Me.PERSON, MapModel.meKind(Phase.AWAKE, 4.0), "walking off")
    }

    private val smales = LatLng(-36.7843, 174.7510)
    private val bond = LatLng(-36.7719, 174.7501)
    private val routes = listOf(
        Route(name = "To work", legs = listOf(LegDraft("Smales Farm", "-36.7843, 174.7510"), LegDraft("City", ""))),
        Route(name = "From work", legs = listOf(LegDraft("Smales Farm", "-36.7843, 174.7510"), LegDraft("Bond Cres", "-36.7719, 174.7501"))),
    )

    @Test fun `pins for every set stop, the current one highlighted, suggestions only when they differ`() {
        val near = Learned(LatLng(-36.78431, 174.75101), emptyList(), 2) // ~1 m: no suggestion
        val far = Learned(LatLng(-36.7728, 174.7501), listOf(Track(listOf(TrackPoint(-36.78, 174.75, 900.0, 90.0), TrackPoint(-36.775, 174.75, 300.0, 30.0)))), 3)
        val l = MapModel.layers(routes, { if (it == smales) near else if (it == bond) far else null }, "From work", 1)
        assertEquals(3, l.pins.size, "blank stops are skipped")
        assertEquals(listOf(false, false, true), l.pins.map { it.current })
        assertEquals(1, l.suggestions.size)
        assertTrue(l.suggestions[0].meters in 90..110, "${l.suggestions[0].meters} m")
        assertEquals(1, l.tracks.size)
        assertEquals(listOf(LatLng(-36.7719, 174.7501), smales.let { LatLng(-36.7719, 174.7501) }).first(), MapModel.frame(null, l).last())
    }

    @Test fun `GeoJSON is lng-lat and carries the icon kind`() {
        val j = Json.parseToJsonElement(MapModel.meJson(smales, MapModel.Me.BUS)).jsonObject
        val f = j["features"]!!.jsonArray[0].jsonObject
        val c = f["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(174.7510, c[0].jsonPrimitive.content.toDouble())
        assertEquals(-36.7843, c[1].jsonPrimitive.content.toDouble())
        assertEquals("bus", f["properties"]!!.jsonObject["icon"]!!.jsonPrimitive.content)
        assertEquals(0, Json.parseToJsonElement(MapModel.meJson(null, MapModel.Me.PERSON)).jsonObject["features"]!!.jsonArray.size)
    }
}

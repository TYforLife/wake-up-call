package app.nodstop.map

import app.nodstop.data.Planner
import app.nodstop.engine.LatLng
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A planned ride on the map (owner's feedback on Run 13), as plain data: each bus as a line through every stop it
 * calls at, each walk as a dotted line along the footpaths it follows (BACKLOG 27; straight, and marked estimated,
 * only when they weren't known), where you get on and off, where you start and where you're going. Pure: tested on
 * the JVM.
 */
object JourneyShape {
    data class Line(val points: List<LatLng>, val walk: Boolean, val route: String = "", val estimated: Boolean = false)
    data class Mark(val pos: LatLng, val kind: Kind, val label: String = "") {
        enum class Kind { START, STOP, END }
    }
    data class Shape(val lines: List<Line>, val marks: List<Mark>) {
        val all: List<LatLng> get() = lines.flatMap { it.points } + marks.map { it.pos }
    }

    fun of(j: Planner.Journey, from: LatLng, to: LatLng, stop: (Int) -> LatLng, name: (Int) -> String): Shape {
        fun at(s: Int?, end: LatLng) = s?.let(stop) ?: end
        val lines = j.legs.map { leg ->
            when (leg) {
                is Planner.Leg.Walk -> Line(leg.points.ifEmpty { listOf(at(leg.from, from), at(leg.to, to)) }, walk = true, estimated = leg.estimated)
                is Planner.Leg.Bus -> Line(leg.path.ifEmpty { listOf(leg.board, leg.alight) }.map(stop), walk = false, route = leg.route)
            }
        }
        val marks = listOf(Mark(from, Mark.Kind.START)) +
            j.buses.flatMap { listOf(Mark(stop(it.board), Mark.Kind.STOP, name(it.board)), Mark(stop(it.alight), Mark.Kind.STOP, name(it.alight))) }
                .distinctBy { it.pos } +
            Mark(to, Mark.Kind.END)
        return Shape(lines, marks)
    }

    private fun coords(p: LatLng) = buildJsonArray { add(JsonPrimitive(p.lng)); add(JsonPrimitive(p.lat)) }
    private fun feature(geometry: JsonObject, props: Map<String, JsonPrimitive>) = buildJsonObject {
        put("type", "Feature"); put("geometry", geometry); put("properties", JsonObject(props))
    }
    private fun collection(fs: List<JsonObject>) = buildJsonObject { put("type", "FeatureCollection"); put("features", JsonArray(fs)) }.toString()

    fun linesJson(s: Shape): String = collection(s.lines.map { l ->
        feature(
            buildJsonObject { put("type", "LineString"); put("coordinates", JsonArray(l.points.map(::coords))) },
            mapOf("walk" to JsonPrimitive(l.walk), "route" to JsonPrimitive(l.route), "estimated" to JsonPrimitive(l.estimated)),
        )
    })

    fun marksJson(s: Shape): String = collection(s.marks.map { m ->
        feature(
            buildJsonObject { put("type", "Point"); put("coordinates", coords(m.pos)) },
            mapOf("kind" to JsonPrimitive(m.kind.name.lowercase()), "label" to JsonPrimitive(m.label)),
        )
    })
}

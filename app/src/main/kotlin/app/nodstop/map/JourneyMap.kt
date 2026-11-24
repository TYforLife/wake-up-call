package app.nodstop.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import app.nodstop.ui.v2.NColors
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.geometry.LatLng as MLatLng

/**
 * A planned ride on the map (owner's feedback on Run 13): the buses in route blue through every stop, walks dotted,
 * where you get on and off as white discs, where you're going as the red pin. Framed to the whole ride.
 */
@Composable
fun JourneyMap(shape: JourneyShape.Shape, colors: NColors, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val mapView = rememberMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    LaunchedEffect(colors.dark) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                val pal = MapPalette.colors(colors.dark)
                MapStyle.applyGoogleLook(s, pal)
                MapStyle.addBusStops(s, pal)
                MapStyle.addImages(s, pal, density)
                addLayers(s, pal)
                style = s
            }
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
    LaunchedEffect(style, shape) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("plan-lines")?.setGeoJson(JourneyShape.linesJson(shape))
        s.getSourceAs<GeoJsonSource>("plan-marks")?.setGeoJson(JourneyShape.marksJson(shape))
        val pts = shape.all
        val m = map ?: return@LaunchedEffect
        if (pts.size >= 2) {
            val px = (48 * density).toInt()
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(pts.map { MLatLng(it.lat, it.lng) }).build(), px))
        }
    }
}

private fun addLayers(s: Style, c: MapPalette.Colors) {
    val empty = """{"type":"FeatureCollection","features":[]}"""
    s.addSource(GeoJsonSource("plan-lines", empty))
    s.addSource(GeoJsonSource("plan-marks", empty))
    val bus = Expression.eq(Expression.get("walk"), false)
    for ((id, col, w) in listOf(Triple("nod-plan-casing", c.routeCasing, 8f), Triple("nod-plan-bus", c.route, 5.5f))) {
        s.addLayer(
            LineLayer(id, "plan-lines").withProperties(
                PropertyFactory.lineColor(col.toInt()), PropertyFactory.lineWidth(w),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(bus),
        )
    }
    // Walks: Google's dotted grey, along the footpaths; lighter when only estimated (a straight line, BACKLOG 27).
    s.addLayer(
        LineLayer("nod-plan-walk", "plan-lines").withProperties(
            PropertyFactory.lineColor(c.otherStop.toInt()), PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineDasharray(arrayOf(0.1f, 2f)),
            PropertyFactory.lineOpacity(Expression.switchCase(Expression.eq(Expression.get("estimated"), true), Expression.literal(0.45f), Expression.literal(1f))),
        ).withFilter(Expression.eq(Expression.get("walk"), true)),
    )
    s.addLayer(
        CircleLayer("nod-plan-stops", "plan-marks").withProperties(
            PropertyFactory.circleRadius(6f), PropertyFactory.circleColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f), PropertyFactory.circleStrokeColor(c.routeCasing.toInt()),
        ).withFilter(Expression.neq(Expression.get("kind"), "end")),
    )
    s.addLayer(
        SymbolLayer("nod-plan-end", "plan-marks").withProperties(
            PropertyFactory.iconImage(MapStyle.PIN), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
        ).withFilter(Expression.eq(Expression.get("kind"), "end")),
    )
}

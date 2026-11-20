package app.nodstop.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.nodstop.engine.LatLng
import app.nodstop.ui.v2.NColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.geometry.LatLng as MLatLng

/**
 * The stop picker's map (PLAN Run 9, M5; mockup `stop`): you drag the map under a fixed pin (drawn by the
 * screen, over the map's centre). The wake circle follows the centre as the map moves. [onCentre] gets the
 * centre whenever the map comes to rest. [moveTick] animates to [moveTo] (a pasted place or your location).
 */
@Composable
fun PickerMap(
    start: LatLng,
    zoneM: Double,
    colors: NColors,
    moveTo: LatLng?,
    moveTick: Int,
    me: LatLng?,
    onCentre: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val mapView = rememberMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val zone by rememberUpdatedState(zoneM)
    val report by rememberUpdatedState(onCentre)

    fun drawZone(m: MapLibreMap, s: Style) {
        val t = m.cameraPosition.target ?: return
        s.getSourceAs<GeoJsonSource>("pick-zone")?.setGeoJson(circleJson(LatLng(t.latitude, t.longitude), zone))
    }

    LaunchedEffect(colors.dark) {
        mapView.getMapAsync { m ->
            val first = map == null
            if (first) m.cameraPosition = CameraPosition.Builder().target(MLatLng(start.lat, start.lng)).zoom(16.5).build()
            map = m
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            val px = ctx.resources.displayMetrics.density
            m.uiSettings.setCompassMargins(0, (116 * px).toInt(), (16 * px).toInt(), 0)
            if (first) {
                m.addOnCameraMoveListener { style?.let { drawZone(m, it) } }
                m.addOnCameraIdleListener {
                    m.cameraPosition.target?.let { report(LatLng(it.latitude, it.longitude)) }
                }
            }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                val pal = MapPalette.colors(colors.dark)
                MapStyle.applyGoogleLook(s, pal)
                MapStyle.addBusStops(s, pal)
                MapStyle.addImages(s, pal, px)
                addPickerLayers(s, pal)
                style = s
                drawZone(m, s)
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(style, zoneM) {
        val m = map ?: return@LaunchedEffect
        style?.let { drawZone(m, it) }
    }
    LaunchedEffect(style, me) {
        style?.getSourceAs<GeoJsonSource>("pick-me")?.setGeoJson(MapModel.meJson(me, MapModel.Me.PERSON))
    }
    LaunchedEffect(moveTick) {
        val p = moveTo ?: return@LaunchedEffect
        if (moveTick == 0) return@LaunchedEffect
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(MLatLng(p.lat, p.lng), 16.5), 600)
    }
}

private fun circleJson(c: LatLng, radiusM: Double): String {
    val ring = JsonArray(MapModel.circle(c, radiusM).map { buildJsonArray { add(JsonPrimitive(it.lng)); add(JsonPrimitive(it.lat)) } })
    return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[$ring]}}]}"""
}

private fun addPickerLayers(s: Style, c: MapPalette.Colors) {
    val empty = """{"type":"FeatureCollection","features":[]}"""
    for (id in listOf("pick-zone", "pick-me")) s.addSource(GeoJsonSource(id, empty))
    val blue = c.me.toInt()
    s.addLayer(FillLayer("nod-pick-zone-f", "pick-zone").withProperties(PropertyFactory.fillColor(blue), PropertyFactory.fillOpacity(0.10f)))
    s.addLayer(LineLayer("nod-pick-zone-l", "pick-zone").withProperties(PropertyFactory.lineColor(blue), PropertyFactory.lineOpacity(0.6f), PropertyFactory.lineWidth(2f)))
    MapStyle.addMe(s, "pick-me", c)
}

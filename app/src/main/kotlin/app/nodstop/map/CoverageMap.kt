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
import app.nodstop.engine.LatLng
import app.nodstop.ui.v2.NColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.geometry.LatLng as MLatLng

/** Where Sleepers works: each region's outline, shaded in route blue, framed (BACKLOG, product direction). */
@Composable
fun CoverageMap(outlines: List<List<LatLng>>, colors: NColors, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val mapView = rememberMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    LaunchedEffect(colors.dark) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                val pal = MapPalette.colors(colors.dark)
                MapStyle.applyGoogleLook(s, pal)
                s.addSource(GeoJsonSource("coverage", """{"type":"FeatureCollection","features":[]}"""))
                s.addLayer(FillLayer("nod-cover-f", "coverage").withProperties(PropertyFactory.fillColor(pal.route.toInt()), PropertyFactory.fillOpacity(0.15f)))
                s.addLayer(LineLayer("nod-cover-l", "coverage").withProperties(PropertyFactory.lineColor(pal.routeCasing.toInt()), PropertyFactory.lineWidth(2.5f)))
                style = s
            }
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
    LaunchedEffect(style, outlines) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("coverage")?.setGeoJson(json(outlines))
        val pts = outlines.flatten()
        if (pts.size >= 2) map?.moveCamera(
            CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(pts.map { MLatLng(it.lat, it.lng) }).build(), (32 * density).toInt()),
        )
    }
}

private fun json(outlines: List<List<LatLng>>): String = buildJsonObject {
    put("type", "FeatureCollection")
    put("features", JsonArray(outlines.filter { it.size >= 4 }.map { ring ->
        buildJsonObject {
            put("type", "Feature")
            put("properties", buildJsonObject { })
            put("geometry", buildJsonObject {
                put("type", "Polygon")
                put("coordinates", JsonArray(listOf(JsonArray(ring.map { buildJsonArray { add(JsonPrimitive(it.lng)); add(JsonPrimitive(it.lat)) } }))))
            })
        }
    }))
}.toString()

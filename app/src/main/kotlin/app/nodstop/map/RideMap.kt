package app.nodstop.map

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.nodstop.engine.LatLng
import app.nodstop.ui.v2.NColors
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.geometry.LatLng as MLatLng

/**
 * The v2 riding map (PLAN Run 8, M3): OpenFreeMap's plain "positron" style, recoloured at runtime to the
 * design's map tokens (light or dark), with only this trip's stops: your stop as an `fg` pin with its circle as
 * a faint `fg` ring, the learned route as an `fg` line, you as the standard blue dot. Follows you; a gesture
 * pauses following until [recentreTick] changes (the recentre button). Rotation and the compass stay.
 */
@Composable
fun RideMap(
    layers: MapModel.Layers,
    me: LatLng?,
    colors: NColors,
    recentreTick: Int,
    modifier: Modifier = Modifier,
    onUserGesture: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val mapView = rememberMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var following by remember { mutableStateOf(true) }

    LaunchedEffect(colors.dark) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            val px = ctx.resources.displayMetrics.density
            m.uiSettings.setCompassMargins(0, (56 * px).toInt(), (16 * px).toInt(), 0)
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) { following = false; onUserGesture() }
            }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                recolour(s, colors)
                addRideLayers(s, colors)
                style = s
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(style, layers) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("ride-tracks")?.setGeoJson(MapModel.tracksJson(layers))
        s.getSourceAs<GeoJsonSource>("ride-zone")?.setGeoJson(MapModel.zonesJson(layers.copy(pins = layers.pins.filter { it.current })))
        s.getSourceAs<GeoJsonSource>("ride-pins")?.setGeoJson(MapModel.pinsJson(layers))
    }
    LaunchedEffect(style, me) {
        style?.getSourceAs<GeoJsonSource>("ride-me")?.setGeoJson(MapModel.meJson(me, MapModel.Me.PERSON))
    }
    LaunchedEffect(recentreTick) { following = true }
    LaunchedEffect(style, me, following, recentreTick) {
        val m = map ?: return@LaunchedEffect
        if (style == null || !following) return@LaunchedEffect
        val pts = MapModel.frame(me, layers)
        when {
            pts.size >= 2 -> m.animateCamera(
                CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(pts.map { MLatLng(it.lat, it.lng) }).build(), 160), 600,
            )
            pts.size == 1 -> m.animateCamera(CameraUpdateFactory.newLatLngZoom(MLatLng(pts[0].lat, pts[0].lng), 15.5), 600)
        }
    }
}

/** A MapView tied to the activity's lifecycle, destroyed with the composable. */
@Composable
internal fun rememberMapView(): MapView {
    val ctx = LocalContext.current
    val mapView = remember { MapView(ctx).also { it.onCreate(null) } }
    val lifecycle = (ctx as ComponentActivity).lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

internal const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"

/** Positron in the design's map colours: land, roads, parks and water; labels in `muted`. Best effort per layer. */
internal fun recolour(s: Style, c: NColors) {
    val bg = c.mapBg.toArgb()
    val road = c.mapRoad.toArgb()
    val park = c.mapPark.toArgb()
    val water = c.mapWater.toArgb()
    val label = c.muted.toArgb()
    val halo = c.bg.toArgb()
    for (layer in s.layers) runCatching {
        val id = layer.id.lowercase()
        when (layer) {
            is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(bg))
            is FillLayer -> layer.setProperties(
                PropertyFactory.fillColor(
                    when {
                        "water" in id -> water
                        listOf("park", "wood", "grass", "forest", "green").any { it in id } -> park
                        else -> bg
                    },
                ),
            )
            is LineLayer -> when {
                "water" in id -> layer.setProperties(PropertyFactory.lineColor(water))
                "casing" in id -> layer.setProperties(PropertyFactory.lineColor(bg))
                listOf("road", "highway", "street", "bridge", "tunnel", "path", "rail").any { it in id } ->
                    layer.setProperties(PropertyFactory.lineColor(road))
                "boundary" in id -> layer.setProperties(PropertyFactory.lineColor(label), PropertyFactory.lineOpacity(0.3f))
            }
            is SymbolLayer -> layer.setProperties(PropertyFactory.textColor(label), PropertyFactory.textHaloColor(halo))
        }
    }
}

private fun addRideLayers(s: Style, c: NColors) {
    val empty = """{"type":"FeatureCollection","features":[]}"""
    for (id in listOf("ride-zone", "ride-tracks", "ride-pins", "ride-me")) s.addSource(GeoJsonSource(id, empty))
    val fg = c.fg.toArgb()
    val bg = c.bg.toArgb()
    s.addLayer(FillLayer("ride-zone-f", "ride-zone").withProperties(PropertyFactory.fillColor(fg), PropertyFactory.fillOpacity(0.045f)))
    s.addLayer(LineLayer("ride-zone-l", "ride-zone").withProperties(PropertyFactory.lineColor(fg), PropertyFactory.lineOpacity(0.28f), PropertyFactory.lineWidth(1.5f)))
    s.addLayer(
        LineLayer("ride-track", "ride-tracks").withProperties(
            PropertyFactory.lineColor(fg), PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
        ),
    )
    // Other stops of this trip: small and faint. Your stop: the pin.
    s.addLayer(
        CircleLayer("ride-pins-other", "ride-pins").withProperties(
            PropertyFactory.circleRadius(5f), PropertyFactory.circleColor(c.faint.toArgb()),
            PropertyFactory.circleStrokeWidth(2f), PropertyFactory.circleStrokeColor(bg),
        ).withFilter(Expression.eq(Expression.get("current"), false)),
    )
    s.addLayer(
        CircleLayer("ride-pin", "ride-pins").withProperties(
            PropertyFactory.circleRadius(11f), PropertyFactory.circleColor(fg),
        ).withFilter(Expression.eq(Expression.get("current"), true)),
    )
    s.addLayer(
        CircleLayer("ride-pin-hole", "ride-pins").withProperties(
            PropertyFactory.circleRadius(4f), PropertyFactory.circleColor(bg),
        ).withFilter(Expression.eq(Expression.get("current"), true)),
    )
    val blue = 0xFF1A73E8.toInt()
    s.addLayer(CircleLayer("ride-me-halo", "ride-me").withProperties(PropertyFactory.circleRadius(18f), PropertyFactory.circleColor(blue), PropertyFactory.circleOpacity(0.18f)))
    s.addLayer(
        CircleLayer("ride-me-dot", "ride-me").withProperties(
            PropertyFactory.circleRadius(8f), PropertyFactory.circleColor(blue),
            PropertyFactory.circleStrokeWidth(3f), PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
        ),
    )
}

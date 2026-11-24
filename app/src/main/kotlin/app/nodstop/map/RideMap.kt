package app.nodstop.map

import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import kotlin.math.hypot
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import app.nodstop.ui.v2.NColors
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.geometry.LatLng as MLatLng

/**
 * The riding map (PLAN Run 8, M3; Run 11, B2): OpenFreeMap's `liberty` in Google Maps' colours (light or dark), bus
 * stops, and only this trip's stops: your stop as Google's red pin with its circle shaded blue, the learned route in
 * route blue, you as the blue dot with a heading beam. It frames you and your stop, then follows you; a gesture
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
    /** Tapped inside your stop's circle: show its sizes (PLAN Run 11, B4). */
    onCircleTap: (MapModel.Pin) -> Unit = {},
    /** The stop whose circle is being edited (tapped on its stop), or null: only that circle's edge can be dragged. */
    selectedLeg: Int? = null,
    /** Dragged its edge to a new size, in metres: save it. */
    onCircleResize: (MapModel.Pin, Double) -> Unit = { _, _ -> },
) {
    // Read through Compose so a display change (font or screen size) reaches the map too.
    val density = LocalDensity.current.density
    val mapView = rememberMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var following by remember { mutableStateOf(true) }
    var cameraTick by remember { mutableIntStateOf(0) }
    // While you drag the circle's edge: its size, until the saved one comes back through [layers].
    var preview by remember { mutableStateOf<Pair<Int, Double>?>(null) } // which leg, and its size while dragging
    val pin = layers.pins.firstOrNull { it.current && it.alarm }
    // Every circle of the trip can be changed (owner, 2026-11-24): the stop it's watching and the ones after it.
    val editable by rememberUpdatedState(MapModel.tripZones(layers).filter { it.alarm })
    // Only the selected one moves (owner, 2026-11-24: "don't make the circles too easy to edit").
    val selected by rememberUpdatedState(editable.filter { it.legIndex == selectedLeg })
    val tap by rememberUpdatedState(onCircleTap)
    val resize by rememberUpdatedState(onCircleResize)

    LaunchedEffect(colors.dark) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            val px = density
            m.uiSettings.setCompassMargins(0, (56 * px).toInt(), (16 * px).toInt(), 0)
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) { following = false; onUserGesture() }
            }
            m.addOnCameraMoveListener { cameraTick++ }
            m.addOnCameraIdleListener { cameraTick++ }
            // Tap inside your stop's circle (with a thumb's slack): its sizes (PLAN B4).
            // Tap a stop (its pin or dot, within a finger's width) to edit its circle: the circle turns bold and its
            // sizes open; then its edge can be dragged.
            m.addOnMapClickListener { ll ->
                val at = m.projection.toScreenLocation(ll)
                val d = editable.map { p -> m.projection.toScreenLocation(MLatLng(p.pos.lat, p.pos.lng)).let { s -> hypot((s.x - at.x).toDouble(), (s.y - at.y).toDouble()) } }
                val i = CircleDrag.pickStop(d, STOP_TAP_DP * px) ?: return@addOnMapClickListener false
                tap(editable[i])
                true
            }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                val pal = MapPalette.colors(colors.dark)
                MapStyle.applyGoogleLook(s, pal)
                MapStyle.addBusStops(s, pal)
                MapStyle.addImages(s, pal, px)
                addRideLayers(s, pal)
                style = s
            }
        }
    }

    Box(modifier) {
        // Touch the circle's edge and drag to resize it; anywhere else moves the map (owner: "no dot").
        val edge = remember {
            EdgeDrag(
                density, { map },
                { selected.map { p -> p.pos to (preview?.takeIf { it.first == p.legIndex }?.second ?: p.zoneM) } },
                { i, r -> selected.getOrNull(i)?.let { preview = it.legIndex to r } },
                { i, r -> selected.getOrNull(i)?.let { resize(it, r) } },
                { i -> selected.getOrNull(i)?.let { tap(it) } },
            )
        }
        AndroidView(factory = { mapView.also { it.setOnTouchListener(edge) } }, modifier = Modifier.fillMaxSize())
        // How far is that (owner's feedback): a scale bar, like Google's, kept up to date as the map moves.
        val metresPerDp = remember(cameraTick, map) {
            map?.let { m -> m.projection.getMetersPerPixelAtLatitude(m.cameraPosition.target?.latitude ?: 0.0) * density }
        }
        metresPerDp?.let { ScaleBar(it, colors, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 24.dp)) }
    }

    LaunchedEffect(layers) { preview = null }
    LaunchedEffect(style, layers) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("ride-tracks")?.setGeoJson(MapModel.tracksJson(layers))
        s.getSourceAs<GeoJsonSource>("ride-legs")?.setGeoJson(MapModel.legPathsJson(layers))
        s.getSourceAs<GeoJsonSource>("ride-pins")?.setGeoJson(MapModel.pinsJson(layers))
    }
    LaunchedEffect(style, layers, preview, selectedLeg) {
        val s = style ?: return@LaunchedEffect
        val zones = MapModel.tripZones(layers).map { p -> preview?.takeIf { it.first == p.legIndex }?.let { p.copy(zoneM = it.second) } ?: p }
        s.getSourceAs<GeoJsonSource>("ride-zone")?.setGeoJson(MapModel.zonesJson(layers.copy(pins = zones), selectedLeg))
    }
    // Off one bus and on to the next stop (owner's feedback): frame you and the new stop again.
    LaunchedEffect(pin?.legIndex) { following = true }
    // Your heading, from where you've been (the blue dot's beam, like Google's): only once you've moved a little.
    var lastPos by remember { mutableStateOf<LatLng?>(null) }
    var bearing by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(me) {
        val p = me ?: return@LaunchedEffect
        val prev = lastPos
        if (prev == null) lastPos = p
        else if (Geo.distanceM(prev, p) >= 8.0) { bearing = Heading.bearing(prev, p); lastPos = p }
    }
    LaunchedEffect(style, me, bearing) {
        style?.getSourceAs<GeoJsonSource>("ride-me")?.setGeoJson(MapModel.meJson(me, MapModel.Me.PERSON, bearing))
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

/** A MapView tied to its screen's lifecycle, destroyed with the composable. */
@Composable
internal fun rememberMapView(): MapView {
    val ctx = LocalContext.current
    val mapView = remember { MapView(ctx).also { it.onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
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

/** How near a stop (on screen) a tap selects it for editing its circle. */
private const val STOP_TAP_DP = 32.0

/** OpenFreeMap's `liberty`: streets, parks, water, places and transit, recoloured to Google's map (PLAN B2). */
internal const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private fun addRideLayers(s: Style, c: MapPalette.Colors) {
    val empty = """{"type":"FeatureCollection","features":[]}"""
    for (id in listOf("ride-zone", "ride-legs", "ride-tracks", "ride-pins", "ride-me")) s.addSource(GeoJsonSource(id, empty))
    val blue = c.me.toInt()
    // Your stop's circle: a light blue disc, as Google shades an area. The trip's later stops: the same, fainter.
    val current = Expression.eq(Expression.get("current"), true)
    s.addLayer(
        FillLayer("nod-zone-f", "ride-zone").withProperties(
            PropertyFactory.fillColor(blue), PropertyFactory.fillOpacity(Expression.switchCase(current, Expression.literal(0.10f), Expression.literal(0.04f))),
        ),
    )
    s.addLayer(
        LineLayer("nod-zone-l", "ride-zone").withProperties(
            PropertyFactory.lineColor(blue), PropertyFactory.lineWidth(2f),
            PropertyFactory.lineOpacity(Expression.switchCase(current, Expression.literal(0.6f), Expression.literal(0.25f))),
        ).withFilter(Expression.eq(Expression.get("selected"), false)),
    )
    // The circle being edited: a bold, dashed edge (it's the one that moves when you drag it).
    s.addLayer(
        LineLayer("nod-zone-edit", "ride-zone").withProperties(
            PropertyFactory.lineColor(blue), PropertyFactory.lineWidth(5f), PropertyFactory.lineOpacity(1f),
            PropertyFactory.lineDasharray(arrayOf(2f, 1f)),
        ).withFilter(Expression.eq(Expression.get("selected"), true)),
    )
    // The trip's legs (owner, 2026-11-24): the others grey under, the one you're on in route blue on a darker edge.
    s.addLayer(
        LineLayer("nod-legs-other", "ride-legs").withProperties(
            PropertyFactory.lineColor(c.otherStop.toInt()), PropertyFactory.lineWidth(4.5f), PropertyFactory.lineOpacity(0.7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ).withFilter(Expression.eq(Expression.get("current"), false)),
    )
    for ((id, col, w) in listOf(Triple("nod-legs-casing", c.routeCasing, 8f), Triple("nod-legs-now", c.route, 5.5f))) {
        s.addLayer(
            LineLayer(id, "ride-legs").withProperties(
                PropertyFactory.lineColor(col.toInt()), PropertyFactory.lineWidth(w),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(Expression.eq(Expression.get("current"), true)),
        )
    }
    // The learned route: Google's route blue on a darker edge.
    for ((id, col, w) in listOf(Triple("nod-track-casing", c.routeCasing, 8f), Triple("nod-track", c.route, 5.5f))) {
        s.addLayer(
            LineLayer(id, "ride-tracks").withProperties(
                PropertyFactory.lineColor(col.toInt()), PropertyFactory.lineWidth(w),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    }
    // Other stops of this trip: small white discs. Your stop: the red pin.
    s.addLayer(
        CircleLayer("nod-pins-other", "ride-pins").withProperties(
            PropertyFactory.circleRadius(5f), PropertyFactory.circleColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(2.5f), PropertyFactory.circleStrokeColor(c.otherStop.toInt()),
        ).withFilter(Expression.eq(Expression.get("current"), false)),
    )
    s.addLayer(
        SymbolLayer("nod-pin", "ride-pins").withProperties(
            PropertyFactory.iconImage(MapStyle.PIN), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
        ).withFilter(Expression.eq(Expression.get("current"), true)),
    )
    MapStyle.addMe(s, "ride-me", c)
}

package app.nodstop.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.nodstop.engine.LatLng
import app.nodstop.engine.CoordParser
import app.nodstop.engine.Geo
import app.nodstop.App
import app.nodstop.data.LegDraft
import app.nodstop.data.Route
import app.nodstop.trip.FusedLocationSource
import app.nodstop.trip.TripService
import app.nodstop.ui.Readiness
import app.nodstop.ui.Screen
import app.nodstop.ui.Hint
import app.nodstop.ui.NodstopPalette
import app.nodstop.ui.PrimaryButton
import app.nodstop.ui.Radius
import app.nodstop.ui.SecondaryButton
import app.nodstop.ui.Space
import app.nodstop.ui.StatusChip
import app.nodstop.ui.Tone
import app.nodstop.ui.TopBar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.geometry.LatLng as MLatLng

/**
 * Where you are and where your stops are pinned, to check the pins sit on the right spots.
 * You're a blue dot while waiting (or off the bus) and a bus once you're on it. Faint lines are learned routes.
 */
@Composable
fun MapScreen(go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val app = App.instance
    val trip by TripService.status.collectAsState()
    val routes by app.routes.routes.collectAsState()
    val learned by app.learned.all.collectAsState()
    var ownPos by remember { mutableStateOf<LatLng?>(null) }

    // No trip running: get your position ourselves, only while the map is open.
    val noTrip = trip == null
    DisposableEffect(noTrip) {
        val src = if (noTrip && Readiness.canStartTrip(ctx)) FusedLocationSource(ctx) else null
        src?.start { ownPos = it.pos }
        onDispose { src?.stop() }
    }

    val t = trip
    val me = t?.status?.position ?: ownPos
    val kind = MapModel.meKind(t?.status?.phase, t?.status?.speedKmh)
    // A simulated trip's stops aren't in any route: show them too.
    val allRoutes = if (t != null && routes.none { it.name == t.routeName })
        routes + Route(name = t.routeName, legs = t.legNames.zip(t.legStops).map { (n, p) -> LegDraft(n, "${p.lat}, ${p.lng}") })
    else routes
    // Tap a stop pin to drag its wake zone; the circle follows the slider live, Save keeps it.
    var selected by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var draft by remember { mutableStateOf<Double?>(null) } // metres; null = Auto
    val sel0 = selected
    val shown = if (sel0 == null) allRoutes else allRoutes.map { r ->
        if (r.id != sel0.first) r else r.copy(legs = r.legs.mapIndexed { i, l -> if (i == sel0.second) l.copy(wakeRadiusM = draft) else l })
    }
    val layers = remember(shown, learned, t?.routeName, t?.legIndex) {
        MapModel.layers(shown, { learned[Route.stopKey(it)] }, t?.routeName, t?.legIndex)
    }
    // Follows you every time the map opens; a drag pauses it for 10 s (or until you tap Follow).
    val policy = remember { FollowPolicy() }
    var follow by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(FollowMode.NORTH_UP) }
    var dragTick by remember { mutableStateOf(0) }
    // Which way you're going (for heading-up), from your positions.
    var lastPos by remember { mutableStateOf<LatLng?>(null) }
    var heading by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(me) {
        val p = me ?: return@LaunchedEffect
        heading = Heading.update(lastPos, p, heading)
        val lp = lastPos
        if (lp == null || Geo.distanceM(lp, p) >= Heading.MIN_MOVE_M) lastPos = p
    }
    LaunchedEffect(dragTick) {
        while (!policy.following(System.currentTimeMillis())) {
            follow = false
            kotlinx.coroutines.delay(policy.resumesInMs(System.currentTimeMillis()).coerceAtLeast(200))
        }
        follow = true
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = Space.l)) { TopBar("Map", onBack = { go(Screen.Home) }) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MapLibreView(layers, me, kind, follow, mode, heading, onUserDrag = { policy.onUserDrag(System.currentTimeMillis()); dragTick++ },
                onPinTap = { routeId, leg ->
                    selected = routeId to leg
                    draft = allRoutes.firstOrNull { it.id == routeId }?.legs?.getOrNull(leg)?.wakeRadiusM
                })
            // Not following: tap to follow again. Following: tap to switch north up / heading up.
            FollowButton(follow, mode, Modifier.align(Alignment.BottomEnd).padding(Space.m)) {
                if (follow) mode = mode.next()
                policy.onFollowTapped(); follow = true; dragTick++
            }
        }
        val sel = selected
        // A simulated trip's stops aren't saved routes: they're editable too, applied to the running trip only.
        val selRoute = sel?.let { s -> allRoutes.firstOrNull { it.id == s.first } }
        val selLeg = sel?.let { s -> selRoute?.legs?.getOrNull(s.second) }
        if (sel != null && selRoute != null && selLeg != null) {
            val pin = layers.pins.firstOrNull { it.routeId == sel.first && it.legIndex == sel.second }
            ZoneSheet(
                legName = selLeg.name, draft = draft, autoM = pin?.zoneM ?: 0.0, onDraft = { draft = it },
                onSave = {
                    if (routes.any { it.id == selRoute.id }) {
                        app.routes.upsert(selRoute.copy(legs = selRoute.legs.mapIndexed { i, l -> if (i == sel.second) l.copy(wakeRadiusM = draft) else l }))
                    } else {
                        CoordParser.parse(selLeg.coords)?.let { p -> TripService.instance?.setWakeRadius(p, draft) }
                    }
                    selected = null
                },
                onCancel = { selected = null },
            )
        }
        if (sel == null) MapLegend(
            when {
                me == null -> "Waiting for your location…"
                kind == MapModel.Me.BUS -> "You're on the bus"
                else -> "That's you"
            },
        )
    }
}

/** Follow / North up / Heading up, bottom right of the map. */
@Composable
private fun FollowButton(follow: Boolean, mode: FollowMode, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = Radius.button,
        colors = if (follow) androidx.compose.material3.ButtonDefaults.buttonColors()
        else androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(
            when {
                !follow -> "◎  Follow me"
                mode == FollowMode.HEADING_UP -> "➤  Heading up"
                else -> "▲  North up"
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** What's on the map, in one line, under it. */
@Composable
private fun MapLegend(me: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(me, style = MaterialTheme.typography.titleSmall)
        Hint("Tap a stop pin to set where it rings · two fingers to turn or tilt · the compass puts north back up")
    }
}

/** A stop's wake zone: presets, the value in big type, a slider (most of its track under 1 km). Stateless. */
@Composable
fun ZoneSheet(legName: String, draft: Double?, autoM: Double, onDraft: (Double?) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    val d = draft
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = Space.l, vertical = Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Hint("Wake zone · $legName")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (d == null) "Auto" else ZoneScale.label(d), style = NodstopPalette.bigNumber)
            if (d == null) Hint("  about ${autoM.toInt()} m", Modifier.padding(bottom = Space.s))
        }
        Hint(
            if (d == null) "From past rides (or your lead × 25 km/h). Pick a size to set your own."
            else "Rings as soon as the bus is inside it, or earlier by the usual timing. Applies to a running trip too.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val options = listOf<Double?>(null) + ZoneScale.PRESETS
            for (p in options) {
                StatusChip(
                    if (p == null) "Auto" else ZoneScale.label(p), if (d == p) Tone.INFO else Tone.NEUTRAL,
                    Modifier.weight(1f), onClick = { onDraft(p) },
                )
            }
        }
        androidx.compose.material3.Slider(
            value = d?.let { ZoneScale.toPos(it) } ?: 0f,
            onValueChange = { v -> onDraft(ZoneScale.toMeters(v)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SecondaryButton("Cancel", onCancel, Modifier.weight(1f))
            PrimaryButton("Save", onSave, Modifier.weight(1f))
        }
    }
}

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val PIN = "#E5484D"
private const val CURRENT = "#FFB000"
private const val FAINT = "#6E7BA8"

@Composable
private fun MapLibreView(
    layers: MapModel.Layers, me: LatLng?, kind: MapModel.Me, follow: Boolean, mode: FollowMode, heading: Double?,
    onUserDrag: () -> Unit, onPinTap: (String, Int) -> Unit,
) {
    val ctx = LocalContext.current
    val mapView = remember { MapView(ctx).also { it.onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var framed by remember { mutableStateOf(false) }

    // Forward the activity lifecycle to the MapView.
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

    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            map = m
            // Turn and tilt with two fingers, like Google Maps; the compass appears once turned and resets north.
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isTiltGesturesEnabled = true
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.setCompassFadeFacingNorth(true)
            val px = ctx.resources.displayMetrics.density
            m.uiSettings.setCompassMargins(0, (16 * px).toInt(), (16 * px).toInt(), 0)
            // Only your own gestures pause following (not the camera moves we make ourselves).
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserDrag()
            }
            m.addOnMapClickListener { at ->
                val f = m.queryRenderedFeatures(m.projection.toScreenLocation(at), "pins-l", "pins-current").firstOrNull()
                val route = f?.getStringProperty("route")
                val leg = f?.getNumberProperty("leg")?.toInt()
                if (route != null && leg != null) { onPinTap(route, leg); true } else false
            }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                s.addImage("bus", badge(ctx, "🚌", AColor.parseColor("#FFB000")))
                s.addImage("person", dot(ctx))
                addLayers(s)
                style = s
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

    LaunchedEffect(style, layers) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("tracks")?.setGeoJson(MapModel.tracksJson(layers))
        s.getSourceAs<GeoJsonSource>("pins")?.setGeoJson(MapModel.pinsJson(layers))
        s.getSourceAs<GeoJsonSource>("zones")?.setGeoJson(MapModel.zonesJson(layers))
    }
    LaunchedEffect(style, me, kind) {
        style?.getSourceAs<GeoJsonSource>("me")?.setGeoJson(MapModel.meJson(me, kind))
    }
    LaunchedEffect(style, me, follow, mode, heading) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        if (!framed) {
            val pts = MapModel.frame(me, layers)
            when {
                pts.size >= 2 -> m.moveCamera(CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds.Builder().includes(pts.map { MLatLng(it.lat, it.lng) }).build(), 120))
                pts.size == 1 -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(MLatLng(pts[0].lat, pts[0].lng), 15.0))
                else -> return@LaunchedEffect
            }
            framed = me != null || pts.isNotEmpty()
        } else if (follow && me != null) {
            // Following: keep you centred (zoomed to street level if the first frame was wide). North up resets
            // the bearing; heading up turns the map so the way you're going is up, tilted like navigation.
            val zoom = maxOf(m.cameraPosition.zoom, 15.0)
            val bearing = if (mode.bearingFollows) heading ?: m.cameraPosition.bearing else 0.0
            val cam = CameraPosition.Builder().target(MLatLng(me.lat, me.lng)).zoom(zoom).bearing(bearing).tilt(mode.tilt).build()
            m.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 600)
        }
    }
}

private fun addLayers(s: Style) {
    val empty = """{"type":"FeatureCollection","features":[]}"""
    for (id in listOf("zones", "tracks", "pins", "me")) s.addSource(GeoJsonSource(id, empty))
    // Wake zones: where it will ring. Solid = yours, dashed = automatic.
    s.addLayer(org.maplibre.android.style.layers.FillLayer("zones-f", "zones").withProperties(
        org.maplibre.android.style.layers.PropertyFactory.fillColor(CURRENT),
        org.maplibre.android.style.layers.PropertyFactory.fillOpacity(0.10f)))
    s.addLayer(LineLayer("zones-yours", "zones").withProperties(lineColor(CURRENT), lineWidth(2.5f))
        .withFilter(org.maplibre.android.style.expressions.Expression.eq(get("yours"), true)))
    s.addLayer(LineLayer("zones-auto", "zones").withProperties(lineColor(CURRENT), lineWidth(2f), lineOpacity(0.7f), lineDasharray(arrayOf(3f, 2f)))
        .withFilter(org.maplibre.android.style.expressions.Expression.eq(get("yours"), false)))
    s.addLayer(LineLayer("tracks-l", "tracks").withProperties(lineColor(FAINT), lineWidth(3f), lineOpacity(0.35f)))
    s.addLayer(CircleLayer("pins-l", "pins").withProperties(
        circleRadius(8f), circleColor(PIN), circleStrokeWidth(2f), circleStrokeColor("#FFFFFF")))
    s.addLayer(CircleLayer("pins-current", "pins").withProperties(
        circleRadius(11f), circleColor(CURRENT), circleStrokeWidth(3f), circleStrokeColor("#FFFFFF")).withFilter(
        org.maplibre.android.style.expressions.Expression.eq(get("current"), true)))
    s.addLayer(SymbolLayer("pins-t", "pins").withProperties(
        textField(get("label")), textFont(arrayOf("Noto Sans Regular")), textSize(13f), textColor("#1B1B1F"),
        textOffset(arrayOf(0f, 1.2f)), textAnchor("top"), textHaloColor("#FFFFFF"), textHaloWidth(2f)))
    s.addLayer(SymbolLayer("me-l", "me").withProperties(
        iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true)))
}

private fun px(ctx: Context, dp: Float) = (dp * ctx.resources.displayMetrics.density).toInt()

/** The classic "you are here" dot. */
private fun dot(ctx: Context): Bitmap {
    val size = px(ctx, 26f)
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = AColor.WHITE; c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = AColor.parseColor("#1A73E8"); c.drawCircle(size / 2f, size / 2f, size / 2f - px(ctx, 4f), p)
    return b
}

/** An emoji on a round badge (the bus). */
private fun badge(ctx: Context, emoji: String, bg: Int): Bitmap {
    val size = px(ctx, 40f)
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = AColor.WHITE; c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = bg; c.drawCircle(size / 2f, size / 2f, size / 2f - px(ctx, 3f), p)
    p.textSize = size * 0.55f
    p.textAlign = Paint.Align.CENTER
    val y = size / 2f - (p.descent() + p.ascent()) / 2
    c.drawText(emoji, size / 2f, y, p)
    return b
}

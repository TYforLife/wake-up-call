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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.nodstop.App
import app.nodstop.data.LegDraft
import app.nodstop.data.Route
import app.nodstop.engine.LatLng
import app.nodstop.trip.FusedLocationSource
import app.nodstop.trip.TripService
import app.nodstop.ui.Readiness
import app.nodstop.ui.Screen
import app.nodstop.ui.TopBar
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
 * You're a blue dot while waiting (or off the bus) and a bus once you're on it. Faint markers are
 * suggestions (where you got off on past rides); faint lines are learned routes.
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
    val layers = remember(allRoutes, learned, t?.routeName, t?.legIndex) {
        MapModel.layers(allRoutes, { learned[Route.stopKey(it)] }, t?.routeName, t?.legIndex)
    }
    var follow by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp)) { TopBar("Map", onBack = { go(Screen.Home) }) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MapLibreView(layers, me, kind, follow)
            Row(
                Modifier.align(Alignment.BottomEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { follow = !follow }) { Text(if (follow) "Following you" else "Follow me") }
            }
        }
        Text(
            when {
                me == null -> "Waiting for your location…"
                kind == MapModel.Me.BUS -> "🚌 You're on the bus."
                else -> "🔵 That's you."
            } + "  📍 your pins · faint dots: suggested stops · faint lines: learned routes",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val PIN = "#E5484D"
private const val CURRENT = "#FFB000"
private const val FAINT = "#6E7BA8"

@Composable
private fun MapLibreView(layers: MapModel.Layers, me: LatLng?, kind: MapModel.Me, follow: Boolean) {
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
            m.uiSettings.isRotateGesturesEnabled = false
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
        s.getSourceAs<GeoJsonSource>("sugg-lines")?.setGeoJson(MapModel.suggestionLinesJson(layers))
        s.getSourceAs<GeoJsonSource>("sugg")?.setGeoJson(MapModel.suggestionsJson(layers))
        s.getSourceAs<GeoJsonSource>("pins")?.setGeoJson(MapModel.pinsJson(layers))
    }
    LaunchedEffect(style, me, kind) {
        style?.getSourceAs<GeoJsonSource>("me")?.setGeoJson(MapModel.meJson(me, kind))
    }
    LaunchedEffect(style, me, follow) {
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
            m.animateCamera(CameraUpdateFactory.newLatLng(MLatLng(me.lat, me.lng)), 600)
        }
    }
}

private fun addLayers(s: Style) {
    val empty = """{"type":"FeatureCollection","features":[]}"""
    for (id in listOf("tracks", "sugg-lines", "sugg", "pins", "me")) s.addSource(GeoJsonSource(id, empty))
    s.addLayer(LineLayer("tracks-l", "tracks").withProperties(lineColor(FAINT), lineWidth(3f), lineOpacity(0.35f)))
    s.addLayer(LineLayer("sugg-lines-l", "sugg-lines").withProperties(
        lineColor(FAINT), lineWidth(2f), lineOpacity(0.6f), lineDasharray(arrayOf(2f, 2f))))
    s.addLayer(CircleLayer("sugg-l", "sugg").withProperties(
        circleRadius(6f), circleColor(FAINT), circleOpacity(0.45f), circleStrokeWidth(1f), circleStrokeColor("#FFFFFF")))
    s.addLayer(SymbolLayer("sugg-t", "sugg").withProperties(
        textField(get("label")), textFont(arrayOf("Noto Sans Regular")), textSize(11f), textColor(FAINT),
        textOffset(arrayOf(0f, 1.3f)), textAnchor("top"), textHaloColor("#FFFFFF"), textHaloWidth(1.5f)))
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

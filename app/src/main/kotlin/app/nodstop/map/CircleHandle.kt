package app.nodstop.map

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.roundToInt
import org.maplibre.android.geometry.LatLng as MLatLng

/**
 * The handle on a stop's circle (PLAN Run 11, B4): a white dot on the edge, due east of the stop. Drag it in or out
 * and the circle follows your finger ([onDrag], metres); let go to save ([onDragEnd]). [cameraTick] changes as the
 * map moves, so the handle stays on the edge. Drawn over the map in the same box as its view.
 */
@Composable
internal fun CircleHandle(
    map: MapLibreMap?,
    center: LatLng?,
    radiusM: Double,
    cameraTick: Int,
    tint: Long,
    onDrag: (Double) -> Unit,
    onDragEnd: (Double) -> Unit,
) {
    val m = map ?: return
    val c = center ?: return
    val at = remember(cameraTick, c, radiusM) {
        runCatching { CircleDrag.edge(c, radiusM).let { m.projection.toScreenLocation(MLatLng(it.lat, it.lng)) } }.getOrNull()
    } ?: return
    var dragging by remember { mutableStateOf<Offset?>(null) }
    val start by rememberUpdatedState(Offset(at.x, at.y))
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val pos = dragging ?: Offset(at.x, at.y)
    val touch = with(LocalDensity.current) { 48.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((pos.x - touch / 2).roundToInt(), (pos.y - touch / 2).roundToInt()) }
            .size(48.dp)
            .semantics { contentDescription = "Drag to change the circle" }
            .pointerInput(c) {
                var last = radiusM
                detectDragGestures(
                    onDragStart = { dragging = start },
                    onDragEnd = { dragging = null; end(last) },
                    onDragCancel = { dragging = null },
                    onDrag = { change, d ->
                        change.consume()
                        val p = (dragging ?: start) + d
                        dragging = p
                        val ll = m.projection.fromScreenLocation(PointF(p.x, p.y))
                        last = CircleDrag.meters(Geo.distanceM(c, LatLng(ll.latitude, ll.longitude)))
                        drag(last)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp).padding(1.dp)) {
            drawCircle(Color.Black.copy(alpha = .2f), size.minDimension / 2, center = Offset(size.width / 2, size.height / 2 + 1.dp.toPx()))
            drawCircle(Color.White, size.minDimension / 2)
            drawCircle(Color(tint.toInt()), size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
        }
    }
}

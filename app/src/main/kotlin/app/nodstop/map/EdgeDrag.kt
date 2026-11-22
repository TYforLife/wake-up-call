package app.nodstop.map

import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import app.nodstop.engine.Geo
import app.nodstop.engine.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.hypot
import org.maplibre.android.geometry.LatLng as MLatLng

/**
 * Resize a stop's circle by touching its edge and dragging (owner, 2026-11-22: "no dot; touch and drag the edge").
 * A touch that lands on the edge (within a finger's width either side) is ours; any other touch goes to the map as
 * usual. [circle]: the stop and its size now. [onDrag] while you drag, [onDone] when you let go, in metres.
 * A touch on the edge that doesn't move is a tap ([onTap]).
 */
internal class EdgeDrag(
    private val density: Float,
    private val map: () -> MapLibreMap?,
    private val circle: () -> Pair<LatLng, Double>?,
    private val onDrag: (Double) -> Unit,
    private val onDone: (Double) -> Unit,
    private val onTap: () -> Unit,
) : View.OnTouchListener {
    private var dragging = false
    private var moved = false
    private var last = 0.0
    private var downX = 0f
    private var downY = 0f

    override fun onTouch(v: View, ev: MotionEvent): Boolean = when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            val m = map()
            val c = circle()
            if (m == null || c == null || ev.pointerCount > 1) false
            else {
                val (center, radius) = c
                val cp = m.projection.toScreenLocation(MLatLng(center.lat, center.lng))
                val e = CircleDrag.edge(center, radius).let { m.projection.toScreenLocation(MLatLng(it.lat, it.lng)) }
                val onEdge = CircleDrag.onEdge(hypot(ev.x - cp.x, ev.y - cp.y).toDouble(), hypot(e.x - cp.x, e.y - cp.y).toDouble(), TOUCH_DP * density.toDouble())
                if (onEdge) {
                    dragging = true; moved = false; last = radius; downX = ev.x; downY = ev.y
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                onEdge
            }
        }
        MotionEvent.ACTION_MOVE -> {
            if (dragging && (moved || hypot(ev.x - downX, ev.y - downY) >= SLOP_DP * density)) {
                moved = true
                val m = map()
                val c = circle()
                if (m != null && c != null) {
                    val ll = m.projection.fromScreenLocation(PointF(ev.x, ev.y))
                    last = CircleDrag.meters(Geo.distanceM(c.first, LatLng(ll.latitude, ll.longitude)))
                    onDrag(last)
                }
            }
            dragging
        }
        MotionEvent.ACTION_UP -> {
            val was = dragging
            if (dragging) { dragging = false; if (moved) onDone(last) else onTap(); v.performClick() }
            was
        }
        MotionEvent.ACTION_CANCEL -> {
            val was = dragging
            if (dragging) { dragging = false; if (moved) onDone(last) }
            was
        }
        else -> dragging
    }

    private companion object {
        /** How far from the drawn edge a touch still grabs it, either side. */
        const val TOUCH_DP = 28f
        /** Movement before a touch on the edge counts as a drag, not a tap. */
        const val SLOP_DP = 6f
    }
}

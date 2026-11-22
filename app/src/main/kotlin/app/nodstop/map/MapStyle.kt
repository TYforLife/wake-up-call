package app.nodstop.map

import app.nodstop.isolated
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style

/**
 * The map's look, shared by the riding map and Set a stop (PLAN Run 11, B2): OpenFreeMap's `liberty` (streets,
 * parks, water, place and transit labels) in Google Maps' colours from [MapPalette], plus bus stops, and Nodstop's
 * marks drawn the way Google draws them: a red pin, a blue route, the blue dot with a heading beam.
 */
internal object MapStyle {
    /** Our own layers start with this, so the recolouring leaves them alone. */
    private const val OURS = "nod-"

    /** The bus-stop dots: tap one in Set a stop to choose it (PLAN Run 12, C2). */
    const val BUS_STOPS = "nod-bus-stops"

    fun Long.argb(): Int = toInt()

    /** Every base layer in the palette's colours. Best effort per layer: a property a layer lacks is skipped. */
    fun applyGoogleLook(s: Style, c: MapPalette.Colors) {
        for (layer in s.layers) isolated("map colours for ${layer.id}") {
            if (layer.id.startsWith(OURS)) return@isolated
            val type = when (layer) {
                is BackgroundLayer -> "background"
                is FillLayer -> "fill"
                is LineLayer -> "line"
                is SymbolLayer -> "symbol"
                else -> return@isolated
            }
            val role = MapPalette.role(type, layer.id) ?: return@isolated
            val col = c.base.getValue(role).argb()
            when (layer) {
                is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(col))
                is FillLayer -> layer.setProperties(PropertyFactory.fillColor(col), PropertyFactory.fillOutlineColor(col))
                is LineLayer -> layer.setProperties(PropertyFactory.lineColor(col))
                is SymbolLayer -> layer.setProperties(
                    PropertyFactory.textColor(col),
                    PropertyFactory.textHaloColor(c.base.getValue(MapPalette.Role.LABEL_HALO).argb()),
                )
            }
        }
    }

    /** Bus stops from the base map's points of interest, as Google shows them: a small blue disc, then its name. */
    fun addBusStops(s: Style, c: MapPalette.Colors, below: String? = null) = isolated("bus stops layer") {
        if (s.getSource(MapPalette.SOURCE) == null) return@isolated
        val filter = Expression.any(
            Expression.eq(Expression.get("class"), MapPalette.BUS_STOP_CLASS),
            *MapPalette.BUS_STOP_SUBCLASSES.map { Expression.eq(Expression.get("subclass"), it) }.toTypedArray(),
        )
        val dot = CircleLayer(BUS_STOPS, MapPalette.SOURCE).apply {
            sourceLayer = MapPalette.POI_LAYER
            minZoom = MapPalette.BUS_STOP_MIN_ZOOM
            setFilter(filter)
            setProperties(
                PropertyFactory.circleRadius(5f), PropertyFactory.circleColor(c.busStop.argb()),
                PropertyFactory.circleStrokeWidth(2f), PropertyFactory.circleStrokeColor(Color.WHITE),
            )
        }
        val label = SymbolLayer("${OURS}bus-stop-names", MapPalette.SOURCE).apply {
            sourceLayer = MapPalette.POI_LAYER
            minZoom = MapPalette.BUS_STOP_LABEL_MIN_ZOOM
            setFilter(filter)
            setProperties(
                PropertyFactory.textField(Expression.get("name")), PropertyFactory.textSize(11f),
                PropertyFactory.textOffset(arrayOf(0f, 1.1f)), PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textColor(c.busStop.argb()),
                PropertyFactory.textHaloColor(c.base.getValue(MapPalette.Role.LABEL_HALO).argb()), PropertyFactory.textHaloWidth(1.2f),
                PropertyFactory.textOptional(true),
            )
        }
        add(s, dot, below)
        add(s, label, below)
    }

    private fun add(s: Style, l: Layer, below: String?) {
        if (below != null && s.getLayer(below) != null) s.addLayerBelow(l, below) else s.addLayer(l)
    }

    // ---- images ----

    const val PIN = "nod-pin"
    const val BEAM = "nod-beam"

    fun addImages(s: Style, c: MapPalette.Colors, density: Float) {
        s.addImage(PIN, pinBitmap(c, density))
        s.addImage(BEAM, beamBitmap(c, density))
    }

    /** Google's red map pin, 27 × 43 dp, its point at the bottom centre. */
    fun pinBitmap(c: MapPalette.Colors, density: Float): Bitmap {
        val w = (27 * density).toInt()
        val h = (43 * density).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val r = w / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path().apply {
            addArc(RectF(0f, 0f, w.toFloat(), w.toFloat()), 150f, 240f)
            lineTo(r, h.toFloat())
            close()
        }
        p.color = c.pin.argb()
        cv.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = density
        p.color = c.pinDot.argb()
        cv.drawPath(path, p)
        p.style = Paint.Style.FILL
        cv.drawCircle(r, r, r * 0.36f, p)
        return b
    }

    /** The heading beam behind the blue dot: a soft wedge pointing up (rotated by the map to your heading). */
    fun beamBitmap(c: MapPalette.Colors, density: Float): Bitmap {
        val size = (72 * density).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val mid = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val col = c.me.argb()
        p.shader = RadialGradient(
            mid, mid, mid,
            intArrayOf(Color.argb(150, Color.red(col), Color.green(col), Color.blue(col)), Color.argb(0, Color.red(col), Color.green(col), Color.blue(col))),
            null, Shader.TileMode.CLAMP,
        )
        cv.drawArc(RectF(0f, 0f, size.toFloat(), size.toFloat()), -125f, 70f, true, p)
        return b
    }

    // ---- you ----

    /** You: halo, heading beam (when known) and the blue dot with its white ring. [source] holds `meJson`. */
    fun addMe(s: Style, source: String, c: MapPalette.Colors) {
        val blue = c.me.argb()
        s.addLayer(CircleLayer("${OURS}me-halo", source).withProperties(
            PropertyFactory.circleRadius(18f), PropertyFactory.circleColor(blue), PropertyFactory.circleOpacity(0.15f),
        ))
        s.addLayer(SymbolLayer("${OURS}me-beam", source).withProperties(
            PropertyFactory.iconImage(BEAM), PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
        ).withFilter(Expression.has("bearing")))
        s.addLayer(CircleLayer("${OURS}me-dot", source).withProperties(
            PropertyFactory.circleRadius(8f), PropertyFactory.circleColor(blue),
            PropertyFactory.circleStrokeWidth(3f), PropertyFactory.circleStrokeColor(Color.WHITE),
        ))
    }
}

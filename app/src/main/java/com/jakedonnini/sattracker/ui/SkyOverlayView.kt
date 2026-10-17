package com.jakedonnini.sattracker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.withClip
import com.jakedonnini.sattracker.ar.FocusSelector
import com.jakedonnini.sattracker.ar.HitTarget
import com.jakedonnini.sattracker.ar.OverlayGeometry
import com.jakedonnini.sattracker.ar.Projector
import com.jakedonnini.sattracker.ar.ScreenPoint
import com.jakedonnini.sattracker.camera.CameraFov
import com.jakedonnini.sattracker.camera.MagnifyRequest
import com.jakedonnini.sattracker.camera.Magnifier
import com.jakedonnini.sattracker.geometry.AzEl
import com.jakedonnini.sattracker.geometry.Enu
import com.jakedonnini.sattracker.geometry.SkyMath
import com.jakedonnini.sattracker.orbit.Satellite
import com.jakedonnini.sattracker.orbit.SkyObject
import com.jakedonnini.sattracker.orbit.SkySnapshot
import com.jakedonnini.sattracker.orbit.TrackPoint
import com.jakedonnini.sattracker.orbit.Visibility
import com.jakedonnini.sattracker.sensors.Attitude
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * Draws the AR layer over the camera preview: horizon and compass points, a dot for every
 * satellite above the horizon, the focus reticle, the focused satellite's label and orbit
 * path, and every locked satellite in its own color (with an edge arrow when off-screen).
 * Redraws every display frame from the latest sensor reading.
 */
class SkyOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Inputs for one frame; read fresh on every draw. */
    class Frame(
        val attitude: Attitude,
        val snapshot: SkySnapshot?,
        val fov: CameraFov?,
        val focus: TrackedSatellite?,
        val locked: List<TrackedSatellite>,
    )

    var frameSource: (() -> Frame?)? = null
    var onFocusChanged: ((Satellite?) -> Unit)? = null
    /** A satellite marker was tapped. */
    var onSatelliteTapped: ((Satellite) -> Unit)? = null

    /**
     * The Moon's true direction (null when below the horizon). Its marker is drawn where the
     * current heading puts it, so the gap to the real Moon in the camera shows the heading error.
     */
    var moonMarker: Enu? = null
    /** While aligning, the Moon marker is emphasized and an edge arrow points to it when off screen. */
    var aligning = false

    private val focusSelector = FocusSelector()
    private var focusedSince = 0L

    // "Hole": when a locked satellite sits in the reticle, the overlay opens a clear window at its
    // position so the real satellite can be seen in the camera feed, unobstructed by markers.
    private var holeTargetId: Int? = null
    private var holeSince = 0L
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val holeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Zoom inside the hole (1 = none); set by the slider. Only the hole is magnified. */
    var zoom: Float = 1f
    /** Supplies magnified camera crops for the hole; null disables zoom. */
    var magnifier: Magnifier? = null
    /** Called (posted) when the hole opens or closes, so the zoom slider can show/hide. */
    var onHoleChanged: ((Boolean) -> Unit)? = null
    private var holeOpen = false
    private val lensPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val lensClip = Path()
    private val lensRect = RectF()
    private val dp = resources.displayMetrics.density

    /** Markers drawn in the last frame, for hit-testing taps. */
    private var hitTargets: List<HitTarget> = emptyList()
    private var hitSatellites: Map<Int, Satellite> = emptyMap()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = OverlayGeometry.nearest(hitTargets, e.x, e.y, TAP_RADIUS_DP * dp) ?: return false
            hitSatellites[hit.id]?.let { onSatelliteTapped?.invoke(it) }
            performClick()
            return true
        }
    })

    private val dotPaint = fill(Color.argb(220, 230, 240, 255))
    /** Satellites that pass every naked-eye test for the user right now (design doc §12). */
    private val visibleDotPaint = fill(VISIBLE_COLOR).apply { setShadowLayer(4 * dp, 0f, 0f, VISIBLE_COLOR) }
    private val focusDotPaint = fill(FOCUS_COLOR)
    private val focusRingPaint = stroke(FOCUS_COLOR, 2f)
    private val reticlePaint = stroke(Color.argb(160, 255, 255, 255), 1.5f)
    private val alignPaint = stroke(ALIGN_COLOR, 2f)
    private val horizonPaint = stroke(Color.argb(140, 120, 220, 140), 1.5f)
    private val cardinalPaint = text(Color.argb(200, 120, 220, 140), 16f)
    private val labelPaint = text(Color.WHITE, 15f).apply { setShadowLayer(4 * dp, 0f, 0f, Color.BLACK) }
    private val dashEffect = DashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f)

    // Reused per draw and recolored for each tracked satellite.
    private val trackPaint = stroke(Color.WHITE, 2.5f)
    private val markPaint = fill(Color.WHITE)
    private val ringPaint = stroke(Color.WHITE, 2f)
    private val arrowPaint = fill(Color.WHITE).apply { setShadowLayer(3 * dp, 0f, 0f, Color.BLACK) }
    private val arrowLabelPaint = text(Color.WHITE, 12f).apply { setShadowLayer(3 * dp, 0f, 0f, Color.BLACK) }

    private val path = Path()

    @SuppressLint("ClickableViewAccessibility") // taps are forwarded to performClick()
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = frameSource?.invoke()
        if (frame != null) drawFrame(canvas, frame)
        postInvalidateOnAnimation()
    }

    private fun drawFrame(canvas: Canvas, frame: Frame) {
        val w = width.toFloat()
        val h = height.toFloat()
        val longFov = frame.fov?.horizontalDeg ?: DEFAULT_LONG_FOV_DEG
        val projector = Projector(
            frame.attitude.rotationMatrix,
            frame.attitude.declinationDeg,
            w, h,
            Projector.focalLengthPx(w, h, longFov),
        )
        val boresight = SkyMath.toEnu(frame.attitude.boresight)
        val now = System.currentTimeMillis()
        val lockedById = frame.locked.associateBy { it.satellite.noradId }

        // Draw into an offscreen layer whenever something is locked, so a hole can be cleared
        // through everything drawn this frame (see drawHole).
        val layer = if (frame.locked.isNotEmpty()) canvas.saveLayer(0f, 0f, w, h, null) else -1

        drawHorizon(canvas, projector)

        // Dots, plus focus candidates near the boresight and positions of locked satellites.
        val snapshot = frame.snapshot
        val candidates = HashMap<Int, Enu>()
        val objectsById = HashMap<Int, SkyObject>()
        val lockedPositions = HashMap<Int, Enu>()
        val targets = ArrayList<HitTarget>()
        val satellites = HashMap<Int, Satellite>()
        val focusedId = focusSelector.currentId
        if (snapshot != null) {
            val dt = (now - snapshot.epochMillis) / 1000.0
            val candidateCos = cos(Math.toRadians(focusSelector.exitDeg + 1.0))
            val skyDark = snapshot.sunElevationDeg <= Visibility.DARK_SKY_SUN_ELEVATION_DEG
            val minSinEl = kotlin.math.sin(Math.toRadians(Visibility.MIN_ELEVATION_DEG))
            for (obj in snapshot.objects) {
                val p = obj.positionAfter(dt)
                if (p.u <= 0) continue // below the horizon
                val id = obj.satellite.noradId
                val unit = p.unit()
                if ((unit dot boresight) > candidateCos) {
                    candidates[id] = unit
                    objectsById[id] = obj
                }
                if (id in lockedById) lockedPositions[id] = p
                val sp = projector.project(p) ?: continue
                if (sp.x < -MARGIN || sp.x > w + MARGIN || sp.y < -MARGIN || sp.y > h + MARGIN) continue
                targets += HitTarget(id, sp.x, sp.y)
                satellites[id] = obj.satellite
                if (id != focusedId && id !in lockedById) {
                    val mag = obj.magnitude
                    val likelyVisible = skyDark && mag != null && mag <= Visibility.NAKED_EYE_LIMIT_MAG &&
                        unit.u >= minSinEl
                    if (likelyVisible) {
                        canvas.drawCircle(sp.x, sp.y, VISIBLE_DOT_RADIUS_DP * dp, visibleDotPaint)
                    } else {
                        canvas.drawCircle(sp.x, sp.y, DOT_RADIUS_DP * dp, dotPaint)
                    }
                }
            }
        }
        hitTargets = targets
        hitSatellites = satellites

        // Focus selection, with a callback when it changes.
        val newFocusId = focusSelector.update(boresight, candidates)
        if (newFocusId != focusedId) {
            focusedSince = now
            onFocusChanged?.invoke(newFocusId?.let { objectsById[it]?.satellite })
        }

        // Locked satellites: their own color, path, label; edge arrow when off-screen.
        for (tracked in frame.locked) {
            val id = tracked.satellite.noradId
            val color = LOCK_COLORS[(tracked.colorSlot ?: 0) % LOCK_COLORS.size]
            drawTrack(canvas, projector, tracked.track, now, color)
            val pos = lockedPositions[id]
            val sp = pos?.let { projector.project(it) }
            if (sp != null && sp.x in 0f..w && sp.y in 0f..h) {
                drawMarker(canvas, sp, color, LOCKED_DOT_RADIUS_DP * dp, ringGap = 6 * dp)
                drawLabel(canvas, sp, LOCKED_DOT_RADIUS_DP * dp, tracked.satellite.name)
            } else {
                // Off-screen, behind the camera, or below the horizon: point the way.
                val dir = pos ?: tracked.details?.let {
                    SkyMath.toEnu(AzEl(it.azimuthDeg, it.elevationDeg))
                } ?: continue
                drawEdgeArrow(canvas, projector, dir, color, tracked.satellite.name, belowHorizon = pos == null)
            }
        }

        // Focused (but not locked) satellite: path, growing dot, label.
        if (newFocusId != null && newFocusId !in lockedById) {
            val focus = frame.focus?.takeIf { it.satellite.noradId == newFocusId }
            if (focus != null) drawTrack(canvas, projector, focus.track, now, FOCUS_COLOR)
            val sp = objectsById[newFocusId]?.let {
                projector.project(it.positionAfter((now - snapshot!!.epochMillis) / 1000.0))
            }
            if (sp != null) {
                val grow = min(1f, (now - focusedSince) / GROW_MS)
                val r = (DOT_RADIUS_DP + (FOCUS_DOT_RADIUS_DP - DOT_RADIUS_DP) * grow) * dp
                canvas.drawCircle(sp.x, sp.y, r, focusDotPaint)
                canvas.drawCircle(sp.x, sp.y, r + 6 * dp * grow, focusRingPaint)
                val name = focus?.satellite?.name ?: objectsById[newFocusId]?.satellite?.name
                if (name != null) drawLabel(canvas, sp, r, name)
            }
        } else if (newFocusId != null) {
            // Focused and locked: just add the focus ring around its colored marker.
            lockedPositions[newFocusId]?.let { projector.project(it) }?.let { sp ->
                canvas.drawCircle(sp.x, sp.y, (LOCKED_DOT_RADIUS_DP + 11) * dp, focusRingPaint)
            }
        }

        // Reticle: the circle a satellite must enter to take focus.
        canvas.drawCircle(w / 2, h / 2, projector.radiusPx(focusSelector.enterDeg), reticlePaint)

        moonMarker?.let { drawMoonMarker(canvas, projector, it) }

        // Hole: a locked satellite in the reticle gets a clear window onto the camera feed.
        val holeTracked = newFocusId?.let { lockedById[it] }
        val holePoint = newFocusId?.let { lockedPositions[it] }?.let { projector.project(it) }
        if (holeTracked == null || holePoint == null) {
            holeTargetId = null
        } else if (holeTargetId != newFocusId) {
            holeTargetId = newFocusId
            holeSince = now
        }
        val isOpen = holeTracked != null && holePoint != null
        if (isOpen != holeOpen) {
            holeOpen = isOpen
            post { onHoleChanged?.invoke(isOpen) }
        }
        // The lens widens as you zoom in, from HOLE_RADIUS_DP at 1x to LENS_RADIUS_DP by 2x.
        val zoomed = isOpen && zoom > 1.01f && magnifier != null
        val fullRadius = (HOLE_RADIUS_DP + (LENS_RADIUS_DP - HOLE_RADIUS_DP) * ((zoom - 1f).coerceIn(0f, 1f))) * dp
        val holeRadius = if (isOpen) fullRadius * min(1f, (now - holeSince) / HOLE_OPEN_MS) else 0f
        if (holeRadius > 0f) canvas.drawCircle(holePoint!!.x, holePoint.y, holeRadius, clearPaint)
        if (layer >= 0) canvas.restoreToCount(layer)

        // Zoom lens: ask the camera for the crop under the hole, draw the latest one inside it.
        magnifier?.request = if (zoomed) {
            MagnifyRequest(holePoint!!.x, holePoint.y, 2 * fullRadius / zoom, w, h)
        } else null
        if (zoomed && holeRadius > 0f) drawLens(canvas, holePoint!!, holeRadius, now)

        if (holeRadius > 0f) {
            val color = LOCK_COLORS[(holeTracked!!.colorSlot ?: 0) % LOCK_COLORS.size]
            drawHoleFrame(canvas, holePoint!!, holeRadius, color, holeTracked.satellite.name)
        }
    }

    private fun drawLens(canvas: Canvas, c: ScreenPoint, radius: Float, now: Long) {
        val frame = magnifier?.latest ?: return
        if (now - frame.timestampMillis > LENS_STALE_MS) return
        lensClip.reset()
        lensClip.addCircle(c.x, c.y, radius, Path.Direction.CW)
        lensRect.set(c.x - radius, c.y - radius, c.x + radius, c.y + radius)
        canvas.withClip(lensClip) { drawBitmap(frame.bitmap, null, lensRect, lensPaint) }
        val label = "%.1fx".format(zoom)
        canvas.drawText(label, c.x - labelPaint.measureText(label) / 2, c.y + radius + 30 * dp, labelPaint)
    }

    /** Thin ring plus four aiming ticks around the hole, and the name outside it. */
    private fun drawHoleFrame(canvas: Canvas, c: ScreenPoint, radius: Float, color: Int, name: String) {
        holeRingPaint.color = color
        holeRingPaint.strokeWidth = 1.5f * dp
        canvas.drawCircle(c.x, c.y, radius + 2 * dp, holeRingPaint)
        holeRingPaint.strokeWidth = 2.5f * dp
        val tickIn = radius + 6 * dp
        val tickOut = radius + 14 * dp
        canvas.drawLine(c.x, c.y - tickOut, c.x, c.y - tickIn, holeRingPaint)
        canvas.drawLine(c.x, c.y + tickIn, c.x, c.y + tickOut, holeRingPaint)
        canvas.drawLine(c.x - tickOut, c.y, c.x - tickIn, c.y, holeRingPaint)
        canvas.drawLine(c.x + tickIn, c.y, c.x + tickOut, c.y, holeRingPaint)
        drawLabel(canvas, c, radius + 8 * dp, name)
    }

    /** Open diamond (distinct from satellite dots) on the Moon; an edge arrow only while aligning. */
    private fun drawMoonMarker(canvas: Canvas, projector: Projector, dir: Enu) {
        val sp = projector.project(dir)
        if (sp == null || sp.x !in 0f..width.toFloat() || sp.y !in 0f..height.toFloat()) {
            if (aligning) drawEdgeArrow(canvas, projector, dir, ALIGN_COLOR, MOON_LABEL, belowHorizon = false)
            return
        }
        alignPaint.alpha = if (aligning) 255 else PASSIVE_MOON_ALPHA
        val r = 12 * dp
        path.reset()
        path.moveTo(sp.x, sp.y - r)
        path.lineTo(sp.x + r, sp.y)
        path.lineTo(sp.x, sp.y + r)
        path.lineTo(sp.x - r, sp.y)
        path.close()
        canvas.drawPath(path, alignPaint)
        if (aligning) drawLabel(canvas, sp, r, MOON_LABEL)
    }

    private fun drawMarker(canvas: Canvas, sp: ScreenPoint, color: Int, radius: Float, ringGap: Float) {
        markPaint.color = color
        ringPaint.color = color
        canvas.drawCircle(sp.x, sp.y, radius, markPaint)
        canvas.drawCircle(sp.x, sp.y, radius + ringGap, ringPaint)
    }

    private fun drawLabel(canvas: Canvas, sp: ScreenPoint, radius: Float, name: String) {
        canvas.drawText(name, sp.x + radius + 10 * dp, sp.y - radius, labelPaint)
    }

    private fun drawEdgeArrow(
        canvas: Canvas,
        projector: Projector,
        direction: Enu,
        color: Int,
        name: String,
        belowHorizon: Boolean,
    ) {
        val dir = projector.screenDirection(direction)
        val len = hypot(dir.x, dir.y)
        if (len < 1e-6f) return
        val ux = dir.x / len
        val uy = dir.y / len
        val tip = OverlayGeometry.edgePoint(ux, uy, width.toFloat(), height.toFloat(), EDGE_INSET_DP * dp) ?: return

        // Triangle pointing outward along (ux, uy).
        val size = 14 * dp
        val angle = atan2(uy, ux)
        path.reset()
        path.moveTo(tip.x, tip.y)
        path.lineTo(tip.x - size * cos(angle - 0.5f), tip.y - size * kotlin.math.sin(angle - 0.5f))
        path.lineTo(tip.x - size * cos(angle + 0.5f), tip.y - size * kotlin.math.sin(angle + 0.5f))
        path.close()
        arrowPaint.color = color
        arrowPaint.alpha = if (belowHorizon) 110 else 255
        canvas.drawPath(path, arrowPaint)

        // Label sits just inside the arrow, clamped on screen.
        val label = if (belowHorizon) "$name (below horizon)" else name
        val textW = arrowLabelPaint.measureText(label)
        val lx = (tip.x - ux * (size + 6 * dp) - textW / 2).coerceIn(4 * dp, width - textW - 4 * dp)
        val ly = (tip.y - uy * (size + 6 * dp) + 4 * dp).coerceIn(16 * dp, height - 4 * dp)
        arrowLabelPaint.color = color
        canvas.drawText(label, lx, ly, arrowLabelPaint)
    }

    private fun drawHorizon(canvas: Canvas, projector: Projector) {
        path.reset()
        var penDown = false
        for (az in 0..360 step 3) {
            val sp = projector.project(SkyMath.toEnu(AzEl(az.toDouble(), 0.0)))
            if (sp == null) {
                penDown = false
            } else if (penDown) {
                path.lineTo(sp.x, sp.y)
            } else {
                path.moveTo(sp.x, sp.y)
                penDown = true
            }
        }
        canvas.drawPath(path, horizonPaint)

        for ((label, az) in CARDINALS) {
            val sp = projector.project(SkyMath.toEnu(AzEl(az, 1.5))) ?: continue
            canvas.drawText(label, sp.x - cardinalPaint.measureText(label) / 2, sp.y, cardinalPaint)
        }
    }

    /** Past part dashed and dimmer, future solid, with a tick each minute; only above the horizon. */
    private fun drawTrack(canvas: Canvas, projector: Projector, track: List<TrackPoint>, now: Long, color: Int) {
        if (track.size < 2) return
        val projected = track.map { pt -> if (pt.direction.u > 0) projector.project(pt.direction) else null }
        trackPaint.color = color
        markPaint.color = color
        for (i in 1 until track.size) {
            val a = projected[i - 1] ?: continue
            val b = projected[i] ?: continue
            val past = track[i].epochMillis <= now
            trackPaint.pathEffect = if (past) dashEffect else null
            trackPaint.alpha = if (past) 150 else 255
            canvas.drawLine(a.x, a.y, b.x, b.y, trackPaint)
        }
        val first = track.first().epochMillis
        track.forEachIndexed { i, pt ->
            val sp = projected[i] ?: return@forEachIndexed
            if (pt.epochMillis > now && (pt.epochMillis - first) % 60_000L == 0L) {
                canvas.drawCircle(sp.x, sp.y, 3 * dp, markPaint)
            }
        }
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun stroke(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthDp * dp
        strokeCap = Paint.Cap.ROUND
    }

    private fun text(color: Int, sizeSp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        isFakeBoldText = true
    }

    companion object {
        val FOCUS_COLOR = Color.rgb(255, 193, 7)
        /** Moon (heading alignment) marker. */
        val ALIGN_COLOR = Color.rgb(90, 200, 250)
        private const val MOON_LABEL = "Moon"
        private const val PASSIVE_MOON_ALPHA = 140
        /** Same green as the globe's golden-zone (likely visible) satellites. */
        val VISIBLE_COLOR = Color.rgb(52, 199, 89)
        /** Globe: likely visible and inside the camera view (green + yellow). */
        val VISIBLE_IN_VIEW_COLOR = Color.rgb(255, 149, 0)

        /** Colors for locked satellites, chosen to stay distinct from the amber focus color. */
        val LOCK_COLORS = intArrayOf(
            Color.rgb(0, 229, 255),   // cyan
            Color.rgb(255, 64, 129),  // pink
            Color.rgb(198, 255, 0),   // lime
            Color.rgb(179, 136, 255), // violet
            Color.rgb(255, 145, 0),   // orange
            Color.rgb(105, 240, 174), // mint
        )

        private const val DOT_RADIUS_DP = 2.5f
        private const val VISIBLE_DOT_RADIUS_DP = 3.5f
        private const val FOCUS_DOT_RADIUS_DP = 8f
        private const val LOCKED_DOT_RADIUS_DP = 7f
        private const val HOLE_RADIUS_DP = 26f
        private const val LENS_RADIUS_DP = 60f
        /** Hide the lens image if the camera stops delivering crops (e.g. while rebinding). */
        private const val LENS_STALE_MS = 500L
        private const val HOLE_OPEN_MS = 250f
        private const val TAP_RADIUS_DP = 36f
        private const val EDGE_INSET_DP = 28f
        private const val GROW_MS = 250f
        private const val MARGIN = 20f
        /** Used until CameraX reports the real FOV (or if camera permission is denied). */
        private const val DEFAULT_LONG_FOV_DEG = 70.0
        private val CARDINALS = listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)
    }
}

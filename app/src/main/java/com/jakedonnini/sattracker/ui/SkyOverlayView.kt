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
import com.jakedonnini.sattracker.ar.OverlayGeometry
import com.jakedonnini.sattracker.ar.Projector
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
import kotlin.math.sqrt

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

    // drawFrame runs every display frame (up to 120 Hz) over hundreds of satellites, so it works
    // in these buffers, reused across frames, instead of allocating per satellite or per point.

    /** Markers drawn in the last frame, for hit-testing taps: marker i is at (hitXs[i], hitYs[i]). */
    private var hitXs = FloatArray(INITIAL_HIT_CAPACITY)
    private var hitYs = FloatArray(INITIAL_HIT_CAPACITY)
    private var hitSatellites = arrayOfNulls<Satellite>(INITIAL_HIT_CAPACITY)
    private var hitCount = 0

    /** Focus candidates near the boresight (id -> unit direction) and their sky objects. */
    private val candidates = HashMap<Int, Enu>()
    private val candidateObjects = HashMap<Int, SkyObject>()
    /** Current positions of locked satellites that are above the horizon. */
    private val lockedPositions = HashMap<Int, Enu>()
    /** One projected point (x, y). */
    private val pt = FloatArray(2)
    /** Projected track points (x, y pairs) and whether each one projected. */
    private var trackXy = FloatArray(0)
    private var trackOk = BooleanArray(0)
    private var lensLabel = ""
    private var lensLabelZoom = Float.NaN

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = OverlayGeometry.nearestIndex(hitXs, hitYs, hitCount, e.x, e.y, TAP_RADIUS_DP * dp)
            if (hit < 0) return false
            hitSatellites[hit]?.let { onSatelliteTapped?.invoke(it) }
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
        val locked = frame.locked

        // Draw into an offscreen layer whenever something is locked, so a hole can be cleared
        // through everything drawn this frame (see drawHole).
        val layer = if (locked.isNotEmpty()) canvas.saveLayer(0f, 0f, w, h, null) else -1

        drawHorizon(canvas, projector)

        // Dots, plus focus candidates near the boresight and positions of locked satellites.
        val snapshot = frame.snapshot
        candidates.clear()
        candidateObjects.clear()
        lockedPositions.clear()
        hitCount = 0
        val focusedId = focusSelector.currentId
        val focusedRaw = focusedId ?: NO_ID
        if (snapshot != null) {
            val dt = (now - snapshot.epochMillis) / 1000.0
            val candidateCos = cos(Math.toRadians(focusSelector.exitDeg + 1.0))
            val skyDark = snapshot.sunElevationDeg <= Visibility.DARK_SKY_SUN_ELEVATION_DEG
            val minSinEl = kotlin.math.sin(Math.toRadians(Visibility.MIN_ELEVATION_DEG))
            val objects = snapshot.objects
            ensureHitCapacity(objects.size)
            for (i in objects.indices) {
                val obj = objects[i]
                // SkyObject.positionAfter(dt) and Enu.unit() inlined: same arithmetic, no objects.
                val pos = obj.position
                val vel = obj.velocity
                val pe = pos.e + vel.e * dt
                val pn = pos.n + vel.n * dt
                val pu = pos.u + vel.u * dt
                if (pu <= 0) continue // below the horizon
                val id = obj.satellite.noradId
                val norm = sqrt(pe * pe + pn * pn + pu * pu)
                val ue = pe / norm
                val un = pn / norm
                val uu = pu / norm
                if (ue * boresight.e + un * boresight.n + uu * boresight.u > candidateCos) {
                    candidates[id] = Enu(ue, un, uu)
                    candidateObjects[id] = obj
                }
                val isLocked = lockedEntry(locked, id) != null
                if (isLocked) lockedPositions[id] = Enu(pe, pn, pu)
                if (!projector.project(pe, pn, pu, pt)) continue
                val x = pt[0]
                val y = pt[1]
                if (x < -MARGIN || x > w + MARGIN || y < -MARGIN || y > h + MARGIN) continue
                hitXs[hitCount] = x
                hitYs[hitCount] = y
                hitSatellites[hitCount] = obj.satellite
                hitCount++
                if (id != focusedRaw && !isLocked) {
                    val mag = obj.magnitude
                    val likelyVisible = skyDark && mag != null && mag <= Visibility.NAKED_EYE_LIMIT_MAG &&
                        uu >= minSinEl
                    if (likelyVisible) {
                        canvas.drawCircle(x, y, VISIBLE_DOT_RADIUS_DP * dp, visibleDotPaint)
                    } else {
                        canvas.drawCircle(x, y, DOT_RADIUS_DP * dp, dotPaint)
                    }
                }
            }
        }

        // Focus selection, with a callback when it changes.
        val newFocusId = focusSelector.update(boresight, candidates)
        if (newFocusId != focusedId) {
            focusedSince = now
            onFocusChanged?.invoke(newFocusId?.let { candidateObjects[it]?.satellite })
        }
        val focusLocked = newFocusId?.let { lockedEntry(locked, it) }

        // Locked satellites: their own color, path, label; edge arrow when off-screen.
        for (i in locked.indices) {
            val tracked = locked[i]
            val id = tracked.satellite.noradId
            val color = LOCK_COLORS[(tracked.colorSlot ?: 0) % LOCK_COLORS.size]
            drawTrack(canvas, projector, tracked.track, now, color)
            val pos = lockedPositions[id]
            if (pos != null && projector.project(pos.e, pos.n, pos.u, pt) &&
                pt[0] >= 0f && pt[0] <= w && pt[1] >= 0f && pt[1] <= h
            ) {
                drawMarker(canvas, pt[0], pt[1], color, LOCKED_DOT_RADIUS_DP * dp, ringGap = 6 * dp)
                drawLabel(canvas, pt[0], pt[1], LOCKED_DOT_RADIUS_DP * dp, tracked.satellite.name)
            } else {
                // Off-screen, behind the camera, or below the horizon: point the way.
                val dir = pos ?: tracked.details?.let {
                    SkyMath.toEnu(AzEl(it.azimuthDeg, it.elevationDeg))
                } ?: continue
                drawEdgeArrow(canvas, projector, dir, color, tracked.satellite.name, belowHorizon = pos == null)
            }
        }

        // Focused (but not locked) satellite: path, growing dot, label.
        if (newFocusId != null && focusLocked == null) {
            val focus = frame.focus?.takeIf { it.satellite.noradId == newFocusId }
            if (focus != null) drawTrack(canvas, projector, focus.track, now, FOCUS_COLOR)
            val obj = candidateObjects[newFocusId]
            if (obj != null) {
                val p = obj.positionAfter((now - snapshot!!.epochMillis) / 1000.0)
                if (projector.project(p.e, p.n, p.u, pt)) {
                    val x = pt[0]
                    val y = pt[1]
                    val grow = min(1f, (now - focusedSince) / GROW_MS)
                    val r = (DOT_RADIUS_DP + (FOCUS_DOT_RADIUS_DP - DOT_RADIUS_DP) * grow) * dp
                    canvas.drawCircle(x, y, r, focusDotPaint)
                    canvas.drawCircle(x, y, r + 6 * dp * grow, focusRingPaint)
                    drawLabel(canvas, x, y, r, focus?.satellite?.name ?: obj.satellite.name)
                }
            }
        } else if (newFocusId != null) {
            // Focused and locked: just add the focus ring around its colored marker.
            val pos = lockedPositions[newFocusId]
            if (pos != null && projector.project(pos.e, pos.n, pos.u, pt)) {
                canvas.drawCircle(pt[0], pt[1], (LOCKED_DOT_RADIUS_DP + 11) * dp, focusRingPaint)
            }
        }

        // Reticle: the circle a satellite must enter to take focus.
        canvas.drawCircle(w / 2, h / 2, projector.radiusPx(focusSelector.enterDeg), reticlePaint)

        // Hole: a locked satellite in the reticle gets a clear window onto the camera feed.
        val holeTracked = focusLocked
        val holePos = newFocusId?.let { lockedPositions[it] }
        val isOpen = holeTracked != null && holePos != null &&
            projector.project(holePos.e, holePos.n, holePos.u, pt)
        val holeX = pt[0]
        val holeY = pt[1]
        if (!isOpen) {
            holeTargetId = null
        } else if (holeTargetId != newFocusId) {
            holeTargetId = newFocusId
            holeSince = now
        }
        if (isOpen != holeOpen) {
            holeOpen = isOpen
            post { onHoleChanged?.invoke(isOpen) }
        }
        // The lens widens as you zoom in, from HOLE_RADIUS_DP at 1x to LENS_RADIUS_DP by 2x.
        val zoomed = isOpen && zoom > 1.01f && magnifier != null
        val fullRadius = (HOLE_RADIUS_DP + (LENS_RADIUS_DP - HOLE_RADIUS_DP) * ((zoom - 1f).coerceIn(0f, 1f))) * dp
        val holeRadius = if (isOpen) fullRadius * min(1f, (now - holeSince) / HOLE_OPEN_MS) else 0f
        if (holeRadius > 0f) canvas.drawCircle(holeX, holeY, holeRadius, clearPaint)
        if (layer >= 0) canvas.restoreToCount(layer)

        // Zoom lens: ask the camera for the crop under the hole, draw the latest one inside it.
        magnifier?.request = if (zoomed) {
            MagnifyRequest(holeX, holeY, 2 * fullRadius / zoom, w, h)
        } else null
        if (zoomed && holeRadius > 0f) drawLens(canvas, holeX, holeY, holeRadius, now)

        if (holeRadius > 0f) {
            val color = LOCK_COLORS[(holeTracked!!.colorSlot ?: 0) % LOCK_COLORS.size]
            drawHoleFrame(canvas, holeX, holeY, holeRadius, color, holeTracked.satellite.name)
        }
    }

    /** The locked entry for [id], or null. A plain scan: there are only ever a few locks. */
    private fun lockedEntry(locked: List<TrackedSatellite>, id: Int): TrackedSatellite? {
        for (i in locked.indices) {
            if (locked[i].satellite.noradId == id) return locked[i]
        }
        return null
    }

    private fun ensureHitCapacity(n: Int) {
        if (hitXs.size >= n) return
        hitXs = FloatArray(n)
        hitYs = FloatArray(n)
        hitSatellites = arrayOfNulls(n)
    }

    private fun drawLens(canvas: Canvas, cx: Float, cy: Float, radius: Float, now: Long) {
        val frame = magnifier?.latest ?: return
        if (now - frame.timestampMillis > LENS_STALE_MS) return
        lensClip.reset()
        lensClip.addCircle(cx, cy, radius, Path.Direction.CW)
        lensRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.withClip(lensClip) { drawBitmap(frame.bitmap, null, lensRect, lensPaint) }
        // Formatting allocates heavily, so only redo it when the zoom changes.
        if (zoom != lensLabelZoom) {
            lensLabel = "%.1fx".format(zoom)
            lensLabelZoom = zoom
        }
        val label = lensLabel
        canvas.drawText(label, cx - labelPaint.measureText(label) / 2, cy + radius + 30 * dp, labelPaint)
    }

    /** Thin ring plus four aiming ticks around the hole, and the name outside it. */
    private fun drawHoleFrame(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, name: String) {
        holeRingPaint.color = color
        holeRingPaint.strokeWidth = 1.5f * dp
        canvas.drawCircle(cx, cy, radius + 2 * dp, holeRingPaint)
        holeRingPaint.strokeWidth = 2.5f * dp
        val tickIn = radius + 6 * dp
        val tickOut = radius + 14 * dp
        canvas.drawLine(cx, cy - tickOut, cx, cy - tickIn, holeRingPaint)
        canvas.drawLine(cx, cy + tickIn, cx, cy + tickOut, holeRingPaint)
        canvas.drawLine(cx - tickOut, cy, cx - tickIn, cy, holeRingPaint)
        canvas.drawLine(cx + tickIn, cy, cx + tickOut, cy, holeRingPaint)
        drawLabel(canvas, cx, cy, radius + 8 * dp, name)
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float, color: Int, radius: Float, ringGap: Float) {
        markPaint.color = color
        ringPaint.color = color
        canvas.drawCircle(x, y, radius, markPaint)
        canvas.drawCircle(x, y, radius + ringGap, ringPaint)
    }

    private fun drawLabel(canvas: Canvas, x: Float, y: Float, radius: Float, name: String) {
        canvas.drawText(name, x + radius + 10 * dp, y - radius, labelPaint)
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
        for (dir in HORIZON) {
            if (!projector.project(dir.e, dir.n, dir.u, pt)) {
                penDown = false
            } else if (penDown) {
                path.lineTo(pt[0], pt[1])
            } else {
                path.moveTo(pt[0], pt[1])
                penDown = true
            }
        }
        canvas.drawPath(path, horizonPaint)

        for (i in CARDINAL_LABELS.indices) {
            val dir = CARDINAL_DIRS[i]
            if (!projector.project(dir.e, dir.n, dir.u, pt)) continue
            val label = CARDINAL_LABELS[i]
            canvas.drawText(label, pt[0] - cardinalPaint.measureText(label) / 2, pt[1], cardinalPaint)
        }
    }

    /** Past part dashed and dimmer, future solid, with a tick each minute; only above the horizon. */
    private fun drawTrack(canvas: Canvas, projector: Projector, track: List<TrackPoint>, now: Long, color: Int) {
        val n = track.size
        if (n < 2) return
        if (trackOk.size < n) {
            trackXy = FloatArray(2 * n)
            trackOk = BooleanArray(n)
        }
        val xy = trackXy
        for (i in 0 until n) {
            val d = track[i].direction
            trackOk[i] = d.u > 0 && projector.project(d.e, d.n, d.u, xy, 2 * i)
        }
        trackPaint.color = color
        markPaint.color = color
        for (i in 1 until n) {
            if (!trackOk[i - 1] || !trackOk[i]) continue
            val past = track[i].epochMillis <= now
            trackPaint.pathEffect = if (past) dashEffect else null
            trackPaint.alpha = if (past) 150 else 255
            canvas.drawLine(xy[2 * i - 2], xy[2 * i - 1], xy[2 * i], xy[2 * i + 1], trackPaint)
        }
        val first = track[0].epochMillis
        for (i in 0 until n) {
            if (!trackOk[i]) continue
            val t = track[i].epochMillis
            if (t > now && (t - first) % 60_000L == 0L) {
                canvas.drawCircle(xy[2 * i], xy[2 * i + 1], 3 * dp, markPaint)
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
        private const val INITIAL_HIT_CAPACITY = 1024
        /** Stands in for "no focus" in the per-satellite loop; NORAD ids are positive. */
        private const val NO_ID = Int.MIN_VALUE
        /** Horizon polyline, every 3 degrees of azimuth (0..360 inclusive, so it closes). */
        private val HORIZON = (0..360 step 3).map { SkyMath.toEnu(AzEl(it.toDouble(), 0.0)) }.toTypedArray()
        private val CARDINAL_LABELS = arrayOf("N", "E", "S", "W")
        private val CARDINAL_DIRS = doubleArrayOf(0.0, 90.0, 180.0, 270.0).map { SkyMath.toEnu(AzEl(it, 1.5)) }.toTypedArray()
    }
}

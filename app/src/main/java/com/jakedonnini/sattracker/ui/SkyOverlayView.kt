package com.jakedonnini.sattracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.jakedonnini.sattracker.ar.FocusSelector
import com.jakedonnini.sattracker.ar.Projector
import com.jakedonnini.sattracker.ar.ScreenPoint
import com.jakedonnini.sattracker.camera.CameraFov
import com.jakedonnini.sattracker.geometry.AzEl
import com.jakedonnini.sattracker.geometry.Enu
import com.jakedonnini.sattracker.geometry.SkyMath
import com.jakedonnini.sattracker.orbit.Satellite
import com.jakedonnini.sattracker.orbit.SkyObject
import com.jakedonnini.sattracker.orbit.SkySnapshot
import com.jakedonnini.sattracker.orbit.TrackPoint
import com.jakedonnini.sattracker.sensors.Attitude
import kotlin.math.cos
import kotlin.math.min

/**
 * Draws the AR layer over the camera preview: horizon and compass points, a dot for every
 * satellite above the horizon, the focus reticle, and the focused satellite's label and
 * orbit path. Redraws every display frame from the latest sensor reading.
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
        val focus: FocusState?,
    )

    var frameSource: (() -> Frame?)? = null
    var onFocusChanged: ((Satellite?) -> Unit)? = null

    private val focusSelector = FocusSelector()
    private var focusedSince = 0L
    private val dp = resources.displayMetrics.density

    private val dotPaint = fill(Color.argb(220, 230, 240, 255))
    private val focusDotPaint = fill(FOCUS_COLOR)
    private val focusRingPaint = stroke(FOCUS_COLOR, 2f)
    private val reticlePaint = stroke(Color.argb(160, 255, 255, 255), 1.5f)
    private val horizonPaint = stroke(Color.argb(140, 120, 220, 140), 1.5f)
    private val cardinalPaint = text(Color.argb(200, 120, 220, 140), 16f, bold = true)
    private val labelPaint = text(Color.WHITE, 15f, bold = true).apply {
        setShadowLayer(4 * dp, 0f, 0f, Color.BLACK)
    }
    private val futureTrackPaint = stroke(FOCUS_COLOR, 2.5f)
    private val pastTrackPaint = stroke(Color.argb(140, 255, 193, 7), 2f).apply {
        pathEffect = DashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f)
    }
    private val tickPaint = fill(FOCUS_COLOR)

    private val path = Path()

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

        drawHorizon(canvas, projector)

        // Dots, plus focus candidates near the boresight.
        val snapshot = frame.snapshot
        val candidates = HashMap<Int, Enu>()
        val objectsById = HashMap<Int, SkyObject>()
        val focusedId = focusSelector.currentId
        var focusedPoint: ScreenPoint? = null
        if (snapshot != null) {
            val dt = (now - snapshot.epochMillis) / 1000.0
            val candidateCos = cos(Math.toRadians(focusSelector.exitDeg + 1.0))
            for (obj in snapshot.objects) {
                val p = obj.positionAfter(dt)
                if (p.u <= 0) continue // below the horizon
                val id = obj.satellite.noradId
                val unit = p.unit()
                if ((unit dot boresight) > candidateCos) {
                    candidates[id] = unit
                    objectsById[id] = obj
                }
                val sp = projector.project(p) ?: continue
                if (sp.x < -MARGIN || sp.x > w + MARGIN || sp.y < -MARGIN || sp.y > h + MARGIN) continue
                if (id == focusedId) focusedPoint = sp
                else canvas.drawCircle(sp.x, sp.y, DOT_RADIUS_DP * dp, dotPaint)
            }
        }

        // Focus selection, with a callback when it changes.
        val newFocusId = focusSelector.update(boresight, candidates)
        if (newFocusId != focusedId) {
            focusedSince = now
            focusedPoint = null
            onFocusChanged?.invoke(newFocusId?.let { objectsById[it]?.satellite })
        }

        // Focused satellite: orbit path, grown dot, label.
        val focus = frame.focus?.takeIf { it.satellite.noradId == newFocusId }
        if (focus != null) drawTrack(canvas, projector, focus.track, now)
        if (newFocusId != null) {
            val sp = focusedPoint ?: snapshot?.objects
                ?.firstOrNull { it.satellite.noradId == newFocusId }
                ?.let { projector.project(it.positionAfter((now - snapshot.epochMillis) / 1000.0)) }
            if (sp != null) {
                val grow = min(1f, (now - focusedSince) / GROW_MS)
                val r = (DOT_RADIUS_DP + (FOCUS_DOT_RADIUS_DP - DOT_RADIUS_DP) * grow) * dp
                canvas.drawCircle(sp.x, sp.y, r, focusDotPaint)
                canvas.drawCircle(sp.x, sp.y, r + 6 * dp * grow, focusRingPaint)
                val name = focus?.satellite?.name ?: objectsById[newFocusId]?.satellite?.name
                if (name != null) canvas.drawText(name, sp.x + r + 10 * dp, sp.y - r, labelPaint)
            }
        }

        // Reticle: the circle a satellite must enter to take focus.
        canvas.drawCircle(w / 2, h / 2, projector.radiusPx(focusSelector.enterDeg), reticlePaint)
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

    /** Past part dashed, future solid, with a tick each minute; only above the horizon. */
    private fun drawTrack(canvas: Canvas, projector: Projector, track: List<TrackPoint>, now: Long) {
        if (track.size < 2) return
        val projected = track.map { pt -> if (pt.direction.u > 0) projector.project(pt.direction) else null }
        for (i in 1 until track.size) {
            val a = projected[i - 1] ?: continue
            val b = projected[i] ?: continue
            val paint = if (track[i].epochMillis <= now) pastTrackPaint else futureTrackPaint
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
        val first = track.first().epochMillis
        track.forEachIndexed { i, pt ->
            val sp = projected[i] ?: return@forEachIndexed
            if (pt.epochMillis > now && (pt.epochMillis - first) % 60_000L == 0L) {
                canvas.drawCircle(sp.x, sp.y, 3 * dp, tickPaint)
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

    private fun text(color: Int, sizeSp: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        isFakeBoldText = bold
    }

    private companion object {
        val FOCUS_COLOR = Color.rgb(255, 193, 7)
        const val DOT_RADIUS_DP = 2.5f
        const val FOCUS_DOT_RADIUS_DP = 8f
        const val GROW_MS = 250f
        const val MARGIN = 20f
        /** Used until CameraX reports the real FOV (or if camera permission is denied). */
        const val DEFAULT_LONG_FOV_DEG = 70.0
        val CARDINALS = listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)
    }
}

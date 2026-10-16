package com.jakedonnini.sattracker.ar

import com.jakedonnini.sattracker.geometry.Enu
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** A projected point in view pixels (origin top-left, y down). */
data class ScreenPoint(val x: Float, val y: Float)

/**
 * Pinhole projection of sky directions onto the camera preview.
 *
 * @param rotation device→world rotation (East, magnetic North, Up), row-major 3x3, from
 *   the rotation-vector sensor. The activity is portrait-locked, so device X = screen right
 *   and device Y = screen up; the back camera looks along device −Z.
 * @param declinationDeg magnetic declination, used to bring true-north vectors into the
 *   sensor's magnetic world frame.
 * @param focalPx focal length in view pixels (see [focalLengthPx]).
 */
class Projector(
    private val rotation: FloatArray,
    declinationDeg: Double,
    private val viewWidth: Float,
    private val viewHeight: Float,
    val focalPx: Float,
) {
    private val cosD = cos(Math.toRadians(declinationDeg))
    private val sinD = sin(Math.toRadians(declinationDeg))

    /**
     * Projects a direction given in the true-north ENU frame (any length).
     * Returns null when it's behind or nearly perpendicular to the camera axis.
     */
    fun project(trueEnu: Enu): ScreenPoint? {
        // True azimuth α → magnetic azimuth α − δ (rotate about Up).
        val e = trueEnu.e * cosD - trueEnu.n * sinD
        val n = trueEnu.n * cosD + trueEnu.e * sinD
        val u = trueEnu.u

        // Device coordinates: d = Rᵀ w.
        val r = rotation
        val dx = r[0] * e + r[3] * n + r[6] * u
        val dy = r[1] * e + r[4] * n + r[7] * u
        val dz = r[2] * e + r[5] * n + r[8] * u

        val depth = -dz
        if (depth <= MIN_DEPTH_RATIO * trueEnu.norm) return null
        return ScreenPoint(
            (viewWidth / 2 + focalPx * dx / depth).toFloat(),
            (viewHeight / 2 - focalPx * dy / depth).toFloat(),
        )
    }

    /**
     * Which way to turn toward a direction, as a screen-space vector (x right, y down),
     * ignoring perspective. Works even when the direction is behind the camera, which is what
     * off-screen indicators need. Returns (0, 0) when it's exactly on the camera axis.
     */
    fun screenDirection(trueEnu: Enu): ScreenPoint {
        val e = trueEnu.e * cosD - trueEnu.n * sinD
        val n = trueEnu.n * cosD + trueEnu.e * sinD
        val u = trueEnu.u
        val r = rotation
        val dx = r[0] * e + r[3] * n + r[6] * u
        val dy = r[1] * e + r[4] * n + r[7] * u
        return ScreenPoint(dx.toFloat(), (-dy).toFloat())
    }

    /** Radius in pixels of a cone of [angleDeg] around the view center. */
    fun radiusPx(angleDeg: Double): Float = (focalPx * tan(Math.toRadians(angleDeg))).toFloat()

    companion object {
        /** Points more than ~84° off-axis are dropped; their projection explodes. */
        private const val MIN_DEPTH_RATIO = 0.1

        /**
         * Focal length in view pixels for a CameraX preview shown with PreviewView's default
         * FILL_CENTER scaling, in a portrait view.
         *
         * The preview stream is 4:3 (CameraX default), so in portrait the image is 3 wide × 4
         * tall and its long side spans the sensor's long-side field of view. FILL_CENTER scales
         * the image to cover the view, cropping the overflow.
         */
        /**
         * The camera's view as five unit directions in the **true-north** ENU frame: the
         * boresight, then the four corners of the visible preview (top-left, top-right,
         * bottom-right, bottom-left). Uses the same intrinsics as [project], so the globe's
         * view pyramid matches exactly what the AR screen shows.
         */
        fun viewDirections(
            rotation: FloatArray,
            declinationDeg: Double,
            viewWidth: Float,
            viewHeight: Float,
            sensorLongSideFovDeg: Double,
        ): List<Enu> {
            val f = focalLengthPx(viewWidth, viewHeight, sensorLongSideFovDeg).toDouble()
            val tx = viewWidth / 2 / f
            val ty = viewHeight / 2 / f
            val cosD = cos(Math.toRadians(declinationDeg))
            val sinD = sin(Math.toRadians(declinationDeg))
            // Device frame: x = screen right, y = screen up, camera looks along −z.
            val device = listOf(
                doubleArrayOf(0.0, 0.0, -1.0),
                doubleArrayOf(-tx, ty, -1.0),
                doubleArrayOf(tx, ty, -1.0),
                doubleArrayOf(tx, -ty, -1.0),
                doubleArrayOf(-tx, -ty, -1.0),
            )
            return device.map { d ->
                val r = rotation
                val em = r[0] * d[0] + r[1] * d[1] + r[2] * d[2]
                val nm = r[3] * d[0] + r[4] * d[1] + r[5] * d[2]
                val u = r[6] * d[0] + r[7] * d[1] + r[8] * d[2]
                // Magnetic → true north: inverse of the rotation in [project].
                Enu(em * cosD + nm * sinD, nm * cosD - em * sinD, u).unit()
            }
        }

        fun focalLengthPx(viewWidth: Float, viewHeight: Float, sensorLongSideFovDeg: Double): Float {
            val scale = max(viewWidth / 3f, viewHeight / 4f)
            val imageHeight = 4f * scale
            return (imageHeight / 2 / tan(Math.toRadians(sensorLongSideFovDeg / 2))).toFloat()
        }
    }
}

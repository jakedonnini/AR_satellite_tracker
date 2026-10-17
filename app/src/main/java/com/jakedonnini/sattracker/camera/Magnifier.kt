package com.jakedonnini.sattracker.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.roundToInt

/** What part of the screen to magnify: a square of side [screenSide] around ([screenX], [screenY]). */
data class MagnifyRequest(
    val screenX: Float,
    val screenY: Float,
    val screenSide: Float,
    val viewW: Float,
    val viewH: Float,
)

/** A magnified crop, ready to draw over the requested screen square. */
class MagnifiedFrame(val bitmap: Bitmap, val request: MagnifyRequest, val timestampMillis: Long)

/**
 * Zoom lens for the camera hole (design doc §13.3). Receives the full-resolution analysis
 * stream and, only while a [request] is set, converts just the requested crop (a few hundred
 * pixels square) from YUV to an upright ARGB bitmap. Everything else in the frame is skipped,
 * so the cost is tiny compared with converting whole frames.
 */
class Magnifier : ImageAnalysis.Analyzer {

    @Volatile var request: MagnifyRequest? = null
    @Volatile var latest: MagnifiedFrame? = null
        private set

    /** Analysis resolution actually delivered, for the HUD / logs. */
    @Volatile var streamSize: Pair<Int, Int>? = null
        private set

    override fun analyze(image: ImageProxy) {
        image.use {
            if (streamSize?.let { s -> s.first == it.width && s.second == it.height } != true) {
                streamSize = it.width to it.height
            }
            val req = request ?: return
            val rotation = it.imageInfo.rotationDegrees
            val crop = ZoomCrop.cropForScreen(
                req.screenX, req.screenY, req.screenSide, req.viewW, req.viewH,
                it.width, it.height, rotation,
            )
            latest = MagnifiedFrame(cropToBitmap(it, crop, rotation), req, System.currentTimeMillis())
        }
    }

    private var pixelBuffer = IntArray(0)

    private fun cropToBitmap(image: ImageProxy, crop: UprightCrop, rotation: Int): Bitmap {
        // Output at the crop's native resolution (capped so tiny zooms stay cheap).
        val n = crop.side.roundToInt().coerceIn(8, MAX_OUTPUT_PX)
        val step = crop.side / n
        val left = crop.centerX - crop.side / 2
        val top = crop.centerY - crop.side / 2

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uvRow = uPlane.rowStride
        val uvPix = uPlane.pixelStride
        val w = image.width
        val h = image.height

        // Reused across frames (analysis runs on one thread); createBitmap copies it.
        if (pixelBuffer.size < n * n) pixelBuffer = IntArray(MAX_OUTPUT_PX * MAX_OUTPUT_PX)
        val pixels = pixelBuffer
        for (oy in 0 until n) {
            for (ox in 0 until n) {
                val ux = left + (ox + 0.5f) * step
                val uy = top + (oy + 0.5f) * step
                val bx = ZoomCrop.uprightToBufferX(ux, uy, w, rotation).toInt()
                val by = ZoomCrop.uprightToBufferY(ux, uy, h, rotation).toInt()
                if (bx < 0 || by < 0 || bx >= w || by >= h) {
                    pixels[oy * n + ox] = 0xFF000000.toInt()
                    continue
                }
                val yv = (yBuf.get(by * yRow + bx * yPix).toInt() and 0xFF).toFloat()
                val uvIndex = (by / 2) * uvRow + (bx / 2) * uvPix
                val u = (uBuf.get(uvIndex).toInt() and 0xFF) - 128f
                val v = (vBuf.get(uvIndex).toInt() and 0xFF) - 128f
                // Full-range BT.601, as Camera2 YUV_420_888 delivers.
                val r = (yv + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (yv - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                val b = (yv + 1.772f * u).toInt().coerceIn(0, 255)
                pixels[oy * n + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, 0, n, n, n, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val MAX_OUTPUT_PX = 320
    }
}

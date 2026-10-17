package com.jakedonnini.sattracker.camera

import kotlin.math.max

/**
 * A square region of the *upright* (display-oriented) camera image, in image pixels.
 * [centerX]/[centerY]/[side] are in the rotated frame where the image is the right way up.
 */
data class UprightCrop(val centerX: Float, val centerY: Float, val side: Float)

/**
 * Maps a region of the screen to the camera image for the zoom magnifier (design doc §13.3).
 *
 * Assumes the analysis image covers the same field of view as the preview (both 4:3, full
 * sensor) and that PreviewView uses FILL_CENTER, which is what CameraController sets up. Pure.
 */
object ZoomCrop {

    /** Size of the image once rotated upright. */
    fun uprightSize(imageW: Int, imageH: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (rotationDegrees % 180 == 0) imageW to imageH else imageH to imageW

    /**
     * The upright-image region shown under a screen square centered at ([screenX], [screenY])
     * with side [screenSide], for a [viewW]×[viewH] PreviewView in FILL_CENTER.
     */
    fun cropForScreen(
        screenX: Float,
        screenY: Float,
        screenSide: Float,
        viewW: Float,
        viewH: Float,
        imageW: Int,
        imageH: Int,
        rotationDegrees: Int,
    ): UprightCrop {
        val (uw, uh) = uprightSize(imageW, imageH, rotationDegrees)
        val scale = max(viewW / uw, viewH / uh)           // screen px per image px
        val offsetX = (viewW - uw * scale) / 2
        val offsetY = (viewH - uh * scale) / 2
        return UprightCrop(
            centerX = (screenX - offsetX) / scale,
            centerY = (screenY - offsetY) / scale,
            side = screenSide / scale,
        )
    }

    /**
     * Upright-image pixel (x, y) → pixel in the camera buffer ([imageW]×[imageH], as delivered),
     * where [rotationDegrees] is how far the buffer must be turned clockwise to be upright.
     */
    fun uprightToBuffer(x: Float, y: Float, imageW: Int, imageH: Int, rotationDegrees: Int): Pair<Float, Float> =
        uprightToBufferX(x, y, imageW, rotationDegrees) to uprightToBufferY(x, y, imageH, rotationDegrees)

    /** The x half of [uprightToBuffer], without allocating (the magnifier calls it per pixel). */
    fun uprightToBufferX(x: Float, y: Float, imageW: Int, rotationDegrees: Int): Float =
        when (((rotationDegrees % 360) + 360) % 360) {
            90 -> y
            180 -> imageW - x
            270 -> imageW - y
            else -> x
        }

    /** The y half of [uprightToBuffer], without allocating (the magnifier calls it per pixel). */
    fun uprightToBufferY(x: Float, y: Float, imageH: Int, rotationDegrees: Int): Float =
        when (((rotationDegrees % 360) + 360) % 360) {
            90 -> imageH - x
            180 -> imageH - y
            270 -> x
            else -> y
        }
}

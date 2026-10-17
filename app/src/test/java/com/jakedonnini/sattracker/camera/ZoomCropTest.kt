package com.jakedonnini.sattracker.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomCropTest {

    // Pixel 6 Pro-like: 4032x3024 sensor buffer delivered landscape, rotated 90° for portrait,
    // shown in a 1440x3120 portrait view (FILL_CENTER crops the sides).
    private val w = 4032
    private val h = 3024

    @Test
    fun `screen center maps to the image center`() {
        val c = ZoomCrop.cropForScreen(720f, 1560f, 100f, 1440f, 3120f, w, h, 90)
        assertEquals(h / 2f, c.centerX, 0.01f)   // upright width is the buffer height
        assertEquals(w / 2f, c.centerY, 0.01f)
    }

    @Test
    fun `screen side converts with the fill scale`() {
        // Height-bound fill: scale = 3120 / 4032 screen px per image px.
        val c = ZoomCrop.cropForScreen(720f, 1560f, 156f, 1440f, 3120f, w, h, 90)
        assertEquals(156f / (3120f / 4032f), c.side, 0.01f)
    }

    @Test
    fun `cropped sides are accounted for`() {
        // Left edge of the view is not the left edge of the image: FILL_CENTER crops the sides.
        val c = ZoomCrop.cropForScreen(0f, 0f, 10f, 1440f, 3120f, w, h, 90)
        val scale = 3120f / 4032f
        val uprightW = h * scale
        assertEquals(((uprightW - 1440f) / 2) / scale, c.centerX, 0.01f)
        assertEquals(0f, c.centerY, 0.01f)
    }

    @Test
    fun `upright to buffer for each rotation`() {
        // Rotation 90: upright top-left (0,0) is the buffer's bottom-left (0, h).
        assertEquals(0f to h.toFloat(), ZoomCrop.uprightToBuffer(0f, 0f, w, h, 90))
        // Upright (x, y) = (h, 0) (top-right) is buffer (0, 0).
        assertEquals(0f to 0f, ZoomCrop.uprightToBuffer(h.toFloat(), 0f, w, h, 90))
        assertEquals(10f to 20f, ZoomCrop.uprightToBuffer(10f, 20f, w, h, 0))
        assertEquals((w - 10f) to (h - 20f), ZoomCrop.uprightToBuffer(10f, 20f, w, h, 180))
        assertEquals((w - 20f) to 10f, ZoomCrop.uprightToBuffer(10f, 20f, w, h, 270))
    }

    @Test
    fun `round trip through rotation 90 is consistent with a clockwise turn`() {
        // A buffer pixel at the top-left (0,0) must appear at the upright top-right.
        val (bx, by) = ZoomCrop.uprightToBuffer(h - 1f, 0f, w, h, 90)
        assertEquals(0f, bx, 1e-3f)
        assertEquals(1f, by, 1e-3f)
    }
}

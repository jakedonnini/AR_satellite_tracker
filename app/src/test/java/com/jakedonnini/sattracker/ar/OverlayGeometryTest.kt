package com.jakedonnini.sattracker.ar

import com.jakedonnini.sattracker.geometry.AzEl
import com.jakedonnini.sattracker.geometry.SkyMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {

    @Test
    fun `nearest picks the closest target inside the radius`() {
        val xs = floatArrayOf(100f, 130f, 500f, 125f)
        val ys = floatArrayOf(100f, 100f, 500f, 102f)
        // Only the first three are live; the stale fourth entry must be ignored.
        assertEquals(1, OverlayGeometry.nearestIndex(xs, ys, 3, 125f, 102f, radius = 40f))
        assertEquals(-1, OverlayGeometry.nearestIndex(xs, ys, 3, 300f, 300f, radius = 40f))
    }

    @Test
    fun `edge point lands on the inset border in the given direction`() {
        val w = 1000f
        val h = 2000f
        val inset = 50f
        // Straight right → right edge, vertically centered.
        val right = OverlayGeometry.edgePoint(1f, 0f, w, h, inset)!!
        assertEquals(w - inset, right.x, 1e-3f)
        assertEquals(h / 2, right.y, 1e-3f)
        // Up-left at 45° in a tall view → hits the left edge first.
        val upLeft = OverlayGeometry.edgePoint(-1f, -1f, w, h, inset)!!
        assertEquals(inset, upLeft.x, 1e-3f)
        assertEquals(h / 2 - (w / 2 - inset), upLeft.y, 1e-3f)
        // Straight down → bottom edge.
        assertEquals(h - inset, OverlayGeometry.edgePoint(0f, 3f, w, h, inset)!!.y, 1e-3f)
        assertNull(OverlayGeometry.edgePoint(0f, 0f, w, h, inset))
    }

    @Test
    fun `color slots reuse the lowest free slot and wrap when full`() {
        assertEquals(0, OverlayGeometry.nextColorSlot(emptyList(), 6))
        assertEquals(1, OverlayGeometry.nextColorSlot(listOf(0, 2), 6))
        assertEquals(0, OverlayGeometry.nextColorSlot(listOf(1, 2), 6))
        assertEquals(0, OverlayGeometry.nextColorSlot(listOf(0, 1, 2, 3, 4, 5), 6))
        assertEquals(1, OverlayGeometry.nextColorSlot(listOf(0, 1, 2, 3, 4, 5, 0), 6))
    }

    @Test
    fun `screen direction points the way to turn, even behind the camera`() {
        // Upright, facing magnetic north.
        val r = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        val p = Projector(r, 0.0, 1000f, 2000f, 800f)
        // East is to the right.
        assertTrue(p.screenDirection(SkyMath.toEnu(AzEl(90.0, 0.0))).x > 0)
        // Behind and slightly east (az 170°): turn right.
        assertTrue(p.screenDirection(SkyMath.toEnu(AzEl(170.0, 0.0))).x > 0)
        // High overhead: turn up (negative y on screen).
        assertTrue(p.screenDirection(SkyMath.toEnu(AzEl(0.0, 80.0))).y < 0)
    }
}

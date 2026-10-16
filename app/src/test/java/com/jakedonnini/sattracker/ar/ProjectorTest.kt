package com.jakedonnini.sattracker.ar

import com.jakedonnini.sattracker.geometry.AzEl
import com.jakedonnini.sattracker.geometry.SkyMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class ProjectorTest {

    // Phone upright in portrait, back camera facing magnetic north (see SkyMathTest).
    private val facingNorth = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
    private val w = 1000f
    private val h = 2000f
    private val f = 800f

    private fun projector(declination: Double = 0.0) = Projector(facingNorth, declination, w, h, f)

    @Test
    fun `boresight direction lands at view center`() {
        val p = projector().project(SkyMath.toEnu(AzEl(0.0, 0.0)))!!
        assertEquals(w / 2, p.x, 1e-3f)
        assertEquals(h / 2, p.y, 1e-3f)
    }

    @Test
    fun `east of boresight is right, above is up`() {
        val right = projector().project(SkyMath.toEnu(AzEl(10.0, 0.0)))!!
        assertEquals(w / 2 + f * tan(Math.toRadians(10.0)).toFloat(), right.x, 1e-2f)
        assertEquals(h / 2, right.y, 1e-3f)

        val up = projector().project(SkyMath.toEnu(AzEl(0.0, 10.0)))!!
        assertEquals(w / 2, up.x, 1e-3f)
        assertTrue("above horizon should be above center", up.y < h / 2)
    }

    @Test
    fun `behind the camera is not projected`() {
        assertNull(projector().project(SkyMath.toEnu(AzEl(180.0, 0.0))))
        assertNull(projector().project(SkyMath.toEnu(AzEl(90.0, 0.0)))) // 90° off-axis
    }

    @Test
    fun `declination is applied - true azimuth equal to declination is dead ahead`() {
        // Magnetic north is 10° east of true north, so the camera is looking at true az 10°.
        val p = projector(declination = 10.0).project(SkyMath.toEnu(AzEl(10.0, 0.0)))!!
        assertEquals(w / 2, p.x, 1e-2f)
    }

    @Test
    fun `focal length for 4-3 preview filling a tall portrait view`() {
        // 1440x3120 view: height-bound, so the 4-unit image side = 3120 px spans the long FOV.
        val fPx = Projector.focalLengthPx(1440f, 3120f, 71.4)
        assertEquals(1560f / tan(Math.toRadians(35.7)).toFloat(), fPx, 0.5f)
    }

    @Test
    fun `view directions are the boresight and the four visible corners`() {
        val decl = 7.0
        val longFov = 71.4
        val dirs = Projector.viewDirections(facingNorth, decl, 1440f, 3120f, longFov)
        assertEquals(5, dirs.size)

        // Boresight: magnetic north = true azimuth equal to the declination.
        val bore = SkyMath.toAzEl(dirs[0])
        assertEquals(decl, bore.azimuthDeg, 1e-4)
        assertEquals(0.0, bore.elevationDeg, 1e-4)

        // Each corner projects back onto the matching view corner.
        val p = Projector(facingNorth, decl, 1440f, 3120f, Projector.focalLengthPx(1440f, 3120f, longFov))
        val expected = listOf(0f to 0f, 1440f to 0f, 1440f to 3120f, 0f to 3120f)
        expected.forEachIndexed { i, (x, y) ->
            val sp = p.project(dirs[i + 1])!!
            assertEquals("corner $i x", x, sp.x, 0.5f)
            assertEquals("corner $i y", y, sp.y, 0.5f)
        }
    }

    @Test
    fun `reticle radius matches the angle`() {
        assertEquals(f * tan(Math.toRadians(3.0)).toFloat(), projector().radiusPx(3.0), 1e-3f)
    }
}

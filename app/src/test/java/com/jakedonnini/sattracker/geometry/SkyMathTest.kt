package com.jakedonnini.sattracker.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class SkyMathTest {

    private fun assertAzEl(expectedAz: Double, expectedEl: Double, actual: AzEl) {
        assertEquals("azimuth", expectedAz, actual.azimuthDeg, 1e-6)
        assertEquals("elevation", expectedEl, actual.elevationDeg, 1e-6)
    }

    @Test
    fun `phone flat on a table, screen up - camera points straight down`() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertEquals(-90.0, SkyMath.toAzEl(SkyMath.cameraBoresight(identity)).elevationDeg, 1e-6)
    }

    @Test
    fun `phone upright in portrait, back camera facing magnetic north`() {
        // Device X → East, device Y → Up, device Z (out of screen) → South.
        val r = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        assertAzEl(0.0, 0.0, SkyMath.toAzEl(SkyMath.cameraBoresight(r)))
    }

    @Test
    fun `declination shifts magnetic azimuth to true azimuth`() {
        val r = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        // Philadelphia is ~ -11.5° (magnetic north is west of true north).
        assertAzEl(348.5, 0.0, SkyMath.toAzEl(SkyMath.cameraBoresight(r), declinationDeg = -11.5))
    }

    @Test
    fun `camera tilted 45 degrees up while facing east`() {
        // Device X → South, Y (top edge) → up-and-west, Z (toward user) → down-and-west,
        // so the boresight −Z is (E, N, U) = (cos45, 0, sin45).
        val c = Math.cos(Math.PI / 4).toFloat()
        val r = floatArrayOf(
            0f, -c, -c,   // East row
            -1f, 0f, 0f,  // North row
            0f, c, -c,    // Up row
        )
        assertAzEl(90.0, 45.0, SkyMath.toAzEl(SkyMath.cameraBoresight(r)))
    }

    @Test
    fun `azel round-trips through ENU`() {
        val dir = AzEl(217.0, 33.0)
        assertAzEl(217.0, 33.0, SkyMath.toAzEl(SkyMath.toEnu(dir)))
    }

    @Test
    fun `separation between directions`() {
        assertEquals(90.0, SkyMath.separationDeg(AzEl(0.0, 0.0), AzEl(90.0, 0.0)), 1e-9)
        assertEquals(45.0, SkyMath.separationDeg(AzEl(10.0, 45.0), AzEl(10.0, 90.0)), 1e-9)
        assertEquals(0.0, SkyMath.separationDeg(AzEl(359.5, 0.0), AzEl(359.5, 0.0)), 1e-6)
    }
}

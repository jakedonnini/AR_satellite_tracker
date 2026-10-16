package com.jakedonnini.sattracker.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class GeodesyTest {

    private fun assertEcef(x: Double, y: Double, z: Double, actual: Ecef, tol: Double) {
        assertEquals("x", x, actual.x, tol)
        assertEquals("y", y, actual.y, tol)
        assertEquals("z", z, actual.z, tol)
    }

    @Test
    fun `equator and prime meridian sit on the x axis at the equatorial radius`() {
        assertEcef(6378.137, 0.0, 0.0, Geodesy.geodeticToEcefKm(0.0, 0.0, 0.0), 1e-6)
        assertEcef(0.0, 6378.137, 0.0, Geodesy.geodeticToEcefKm(0.0, 90.0, 0.0), 1e-6)
    }

    @Test
    fun `north pole is at the polar radius`() {
        assertEcef(0.0, 0.0, 6356.752314, Geodesy.geodeticToEcefKm(90.0, 0.0, 0.0), 1e-5)
    }

    @Test
    fun `height adds along the normal`() {
        assertEcef(6379.137, 0.0, 0.0, Geodesy.geodeticToEcefKm(0.0, 0.0, 1000.0), 1e-6)
    }

    @Test
    fun `ENU axes map to the expected ECEF directions`() {
        // At (0, 0): east = +y, north = +z, up = +x.
        assertEcef(0.0, 1.0, 0.0, Geodesy.enuToEcef(Enu(1.0, 0.0, 0.0), 0.0, 0.0), 1e-12)
        assertEcef(0.0, 0.0, 1.0, Geodesy.enuToEcef(Enu(0.0, 1.0, 0.0), 0.0, 0.0), 1e-12)
        assertEcef(1.0, 0.0, 0.0, Geodesy.enuToEcef(Enu(0.0, 0.0, 1.0), 0.0, 0.0), 1e-12)
        // At the north pole on the prime meridian: north points to −x, up = +z.
        assertEcef(-1.0, 0.0, 0.0, Geodesy.enuToEcef(Enu(0.0, 1.0, 0.0), 90.0, 0.0), 1e-12)
        assertEcef(0.0, 0.0, 1.0, Geodesy.enuToEcef(Enu(0.0, 0.0, 1.0), 90.0, 0.0), 1e-12)
    }
}

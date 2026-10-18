package com.jakedonnini.sattracker.geometry

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Earth-centered, Earth-fixed coordinates (WGS-84 / ITRF axes). Units depend on the caller. */
data class Ecef(val x: Double, val y: Double, val z: Double)

/** WGS-84 conversions between geodetic, ECEF and local East-North-Up. */
object Geodesy {
    private const val A = 6378137.0                 // equatorial radius, m
    private const val F = 1 / 298.257223563
    private const val E2 = F * (2 - F)              // first eccentricity squared

    /** Geodetic latitude/longitude (deg) and ellipsoidal height (m) → ECEF in **km**. */
    fun geodeticToEcefKm(latDeg: Double, lonDeg: Double, altMeters: Double): Ecef {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val n = A / sqrt(1 - E2 * sin(lat) * sin(lat))
        return Ecef(
            (n + altMeters) * cos(lat) * cos(lon) / 1000,
            (n + altMeters) * cos(lat) * sin(lon) / 1000,
            (n * (1 - E2) + altMeters) * sin(lat) / 1000,
        )
    }

    /** Rotates an ECEF vector (dx, dy, dz) into local East-North-Up axes at (lat, lon) (same units). */
    fun ecefToEnu(dx: Double, dy: Double, dz: Double, latDeg: Double, lonDeg: Double): Enu {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val sLat = sin(lat); val cLat = cos(lat)
        val sLon = sin(lon); val cLon = cos(lon)
        return Enu(
            -sLon * dx + cLon * dy,
            -sLat * cLon * dx - sLat * sLon * dy + cLat * dz,
            cLat * cLon * dx + cLat * sLon * dy + sLat * dz,
        )
    }

    /** Rotates a local East-North-Up vector at (lat, lon) into ECEF axes (same units). */
    fun enuToEcef(v: Enu, latDeg: Double, lonDeg: Double): Ecef {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val sLat = sin(lat); val cLat = cos(lat)
        val sLon = sin(lon); val cLon = cos(lon)
        return Ecef(
            -sLon * v.e - sLat * cLon * v.n + cLat * cLon * v.u,
            cLon * v.e - sLat * sLon * v.n + cLat * sLon * v.u,
            cLat * v.n + sLat * v.u,
        )
    }
}

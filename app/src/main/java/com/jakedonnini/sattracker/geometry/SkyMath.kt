package com.jakedonnini.sattracker.geometry

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A vector in the local East-North-Up frame. */
data class Enu(val e: Double, val n: Double, val u: Double) {
    val norm: Double get() = sqrt(e * e + n * n + u * u)
    fun unit(): Enu = norm.let { Enu(e / it, n / it, u / it) }
    infix fun dot(o: Enu): Double = e * o.e + n * o.n + u * o.u
}

/** A sky direction. Azimuth is clockwise from north, in [0, 360); elevation in [-90, 90]. */
data class AzEl(val azimuthDeg: Double, val elevationDeg: Double)

/**
 * Pure pointing math, independent of Android so it can be unit-tested on the JVM.
 *
 * Android's rotation matrix R (row-major, from SensorManager.getRotationMatrixFromVector) maps
 * device coordinates to world coordinates where X = East, Y = magnetic North, Z = Up.
 */
object SkyMath {

    /** World direction of the back camera's optical axis (device −Z): −(third column of R). */
    fun cameraBoresight(r: FloatArray): Enu {
        require(r.size == 9) { "expected a 3x3 rotation matrix" }
        return Enu(-r[2].toDouble(), -r[5].toDouble(), -r[8].toDouble())
    }

    /**
     * Converts a magnetic-frame ENU vector to true azimuth/elevation.
     * [declinationDeg] is positive when magnetic north is east of true north.
     */
    fun toAzEl(v: Enu, declinationDeg: Double = 0.0): AzEl {
        val norm = sqrt(v.e * v.e + v.n * v.n + v.u * v.u)
        val elevation = Math.toDegrees(asin((v.u / norm).coerceIn(-1.0, 1.0)))
        val magneticAz = Math.toDegrees(atan2(v.e, v.n))
        return AzEl(normalizeDeg(magneticAz + declinationDeg), elevation)
    }

    /** Unit ENU vector (true-north frame) for an azimuth/elevation. */
    fun toEnu(dir: AzEl): Enu {
        val az = Math.toRadians(dir.azimuthDeg)
        val el = Math.toRadians(dir.elevationDeg)
        return Enu(cos(el) * sin(az), cos(el) * cos(az), sin(el))
    }

    /** Great-circle angle between two sky directions, in degrees. */
    fun separationDeg(a: AzEl, b: AzEl): Double {
        val va = toEnu(a)
        val vb = toEnu(b)
        val dot = va.e * vb.e + va.n * vb.n + va.u * vb.u
        return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
    }

    private fun normalizeDeg(deg: Double) = ((deg % 360.0) + 360.0) % 360.0
}

package com.jakedonnini.sattracker.sensors

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Combines a smooth-but-unreferenced orientation with a noisy-but-absolute one.
 *
 * - "Game" rotation (TYPE_GAME_ROTATION_VECTOR: gyro + accelerometer) is smooth and has exact
 *   tilt, but its yaw reference is arbitrary and drifts slowly.
 * - "Absolute" rotation (TYPE_ROTATION_VECTOR: + magnetometer) knows where north is, but the
 *   magnetometer is noisy and gets pushed around by nearby metal and electronics.
 *
 * Both share the same "up" (gravity), so they differ only by a rotation about the vertical
 * axis: R_abs ≈ Rz(ψ)·R_game. We estimate ψ with a slow complementary filter (time constant
 * [tauSeconds]) and output Rz(ψ)·R_game. The result moves like the gyro and points north like
 * the *average* of the compass.
 *
 * Pure Kotlin (no Android types) so it can be unit-tested. Matrices are row-major 3x3.
 */
class HeadingFusion(
    private val tauSeconds: Double = 8.0,
    /** Fast convergence right after start / recalibration. */
    private val warmupTauSeconds: Double = 0.5,
    private val warmupSeconds: Double = 3.0,
    /** Compass samples further than this from the estimate are treated as disturbances... */
    private val outlierDeg: Double = 15.0,
    /** ...and only nudge it very slowly... */
    private val outlierTauSeconds: Double = 60.0,
    /** ...unless the disagreement persists this long (e.g. the game frame was reset). */
    private val outlierResetSeconds: Double = 3.0,
) {
    private var offsetRad: Double? = null
    private var lastNanos = 0L
    private var warmupUntilNanos = Long.MIN_VALUE
    private var outlierSinceNanos: Long? = null

    /** Re-enter fast convergence, e.g. after the user recalibrates the compass. */
    fun rewarm(nowNanos: Long) {
        warmupUntilNanos = nowNanos + (warmupSeconds * 1e9).toLong()
    }

    /** Feed one absolute sample, paired with the most recent game rotation. */
    fun onAbsolute(rAbs: FloatArray, rGame: FloatArray, timestampNanos: Long) {
        val sample = yawOffset(rAbs, rGame)
        val current = offsetRad
        if (current == null) {
            offsetRad = sample
            lastNanos = timestampNanos
            rewarm(timestampNanos)
            return
        }

        val dt = ((timestampNanos - lastNanos) / 1e9).coerceIn(0.0, 0.5)
        lastNanos = timestampNanos
        val innovation = wrap(sample - current)

        val tau = when {
            timestampNanos < warmupUntilNanos -> warmupTauSeconds
            abs(Math.toDegrees(innovation)) > outlierDeg -> {
                val since = outlierSinceNanos ?: timestampNanos.also { outlierSinceNanos = it }
                if ((timestampNanos - since) / 1e9 > outlierResetSeconds) {
                    // Persistent disagreement: the estimate is what's wrong. Re-converge fast.
                    outlierSinceNanos = null
                    rewarm(timestampNanos)
                    warmupTauSeconds
                } else {
                    outlierTauSeconds
                }
            }
            else -> {
                outlierSinceNanos = null
                tauSeconds
            }
        }
        offsetRad = wrap(current + innovation * min(1.0, dt / tau))
    }

    /** Fused device→world(magnetic) rotation, or null until the first absolute sample. */
    fun fuse(rGame: FloatArray): FloatArray? {
        val psi = offsetRad ?: return null
        return rotateAboutUp(psi, rGame)
    }

    companion object {
        /** ψ such that rAbs ≈ Rz(ψ)·rGame, from M = rAbs·rGameᵀ. */
        fun yawOffset(rAbs: FloatArray, rGame: FloatArray): Double {
            fun m(i: Int, j: Int): Double {
                var s = 0.0
                for (k in 0 until 3) s += rAbs[3 * i + k].toDouble() * rGame[3 * j + k]
                return s
            }
            // For a pure Z rotation, M10 − M01 = 2 sin ψ and M00 + M11 = 2 cos ψ.
            return atan2(m(1, 0) - m(0, 1), m(0, 0) + m(1, 1))
        }

        /** Rz(ψ)·r. */
        fun rotateAboutUp(psi: Double, r: FloatArray): FloatArray {
            val c = cos(psi).toFloat()
            val s = sin(psi).toFloat()
            return FloatArray(9).also { out ->
                for (j in 0 until 3) {
                    out[j] = c * r[j] - s * r[3 + j]
                    out[3 + j] = s * r[j] + c * r[3 + j]
                    out[6 + j] = r[6 + j]
                }
            }
        }

        private fun wrap(a: Double): Double {
            var x = (a + PI) % (2 * PI)
            if (x < 0) x += 2 * PI
            return x - PI
        }
    }
}

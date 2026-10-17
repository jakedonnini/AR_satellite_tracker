package com.jakedonnini.sattracker.sensors

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Gyro-first heading (design doc §6.2a).
 *
 * - "Game" rotation (TYPE_GAME_ROTATION_VECTOR: gyro + accelerometer) is smooth with exact tilt;
 *   measured on a Pixel 6 Pro it held yaw within 0.04° over four minutes, but its yaw reference
 *   is arbitrary.
 * - "Absolute" rotation (TYPE_ROTATION_VECTOR: + magnetometer) knows north, but the compass
 *   wanders whenever the local field is disturbed (it drifted 3° in the same test).
 *
 * Both share "up", so R_abs ≈ Rz(ψ)·R_game. The output is Rz(ψ)·R_game; how ψ is maintained:
 * - **Aligned** (the user centred a known star / planet / the Moon): ψ is fixed by that sighting
 *   and the compass is ignored, since the gyro holds it far better than the compass could.
 * - **Compass clean** (field matches the World Magnetic Model): ψ follows the compass. After a
 *   (re)start it snaps on during a short warm-up, then the time constant grows with elapsed
 *   time up to [tauSeconds] (minutes), which behaves like a running mean: compass noise
 *   averages away as 1/√N instead of the first rushed estimate lingering.
 * - **Compass disturbed**: ψ is held; only the gyro moves the heading.
 *
 * Pure Kotlin (no Android types) so it can be unit-tested. Matrices are row-major 3x3.
 */
class HeadingFusion(
    private val tauSeconds: Double = 120.0,
    /** Fast convergence right after start / recalibration. */
    private val warmupTauSeconds: Double = 0.5,
    private val warmupSeconds: Double = 3.0,
    /** A clean compass that disagrees by more than this for [outlierResetSeconds]... */
    private val outlierDeg: Double = 15.0,
    /** ...means the gyro frame itself jumped (e.g. reset): re-converge fast. */
    private val outlierResetSeconds: Double = 5.0,
) {
    enum class State { WARMUP, TRACKING, RECOVERING, HELD, ALIGNED }

    private var offsetRad: Double? = null
    private var lastNanos = 0L
    private var warmupUntilNanos = Long.MIN_VALUE
    /** When the current averaging run started (last warm-up), for the growing time constant. */
    private var settleStartNanos = 0L
    private var recovering = false
    private var outlierSinceNanos: Long? = null
    private var lastField = FieldQuality.UNKNOWN

    private var aligned = false
    /** ψ_aligned − compass sample at alignment, to re-apply after a gyro frame restart. */
    private var alignedMinusSample = 0.0
    private var frameRestarted = false

    /** Current yaw offset estimate (compass frame − gyro frame), degrees; null before the first sample. */
    val offsetDeg: Double? get() = offsetRad?.let { Math.toDegrees(it) }
    var lastSampleDeg: Double = 0.0
        private set
    var lastInnovationDeg: Double = 0.0
        private set
    var state: State = State.WARMUP
        private set
    val isAligned: Boolean get() = aligned

    /** Re-enter fast convergence, e.g. after the user recalibrates the compass. */
    fun rewarm(nowNanos: Long) {
        if (aligned) return
        warmupUntilNanos = nowNanos + (warmupSeconds * 1e9).toLong()
        settleStartNanos = nowNanos
    }

    /**
     * The gyro (game) frame may restart with a new yaw reference when sensors are re-registered.
     * Call on sensor (re)start: the next compass sample re-seeds ψ, re-applying any alignment as
     * a correction to that sample (approximate, so the UI should suggest re-aligning).
     */
    fun onFrameRestart() {
        frameRestarted = true
    }

    /**
     * Applies a sky sighting: the heading is wrong by [azimuthErrorDeg] (target azimuth minus the
     * camera's current azimuth). Rotating the world frame by ψ subtracts ψ from azimuth, so ψ −= error.
     */
    fun align(azimuthErrorDeg: Double) {
        val current = offsetRad ?: return
        val updated = wrap(current - Math.toRadians(azimuthErrorDeg))
        offsetRad = updated
        aligned = true
        alignedMinusSample = wrap(updated - Math.toRadians(lastSampleDeg))
        state = State.ALIGNED
    }

    fun clearAlignment(nowNanos: Long) {
        aligned = false
        rewarm(nowNanos)
    }

    /** Feed one absolute (compass) sample, paired with the most recent game rotation. */
    fun onAbsolute(rAbs: FloatArray, rGame: FloatArray, timestampNanos: Long, field: FieldQuality) {
        val sample = yawOffset(rAbs, rGame)
        lastSampleDeg = Math.toDegrees(sample)
        val current = offsetRad
        if (current == null || frameRestarted) {
            frameRestarted = false
            offsetRad = if (aligned) wrap(sample + alignedMinusSample) else sample
            lastNanos = timestampNanos
            lastField = field
            rewarm(timestampNanos)
            state = if (aligned) State.ALIGNED else State.WARMUP
            return
        }

        val dt = ((timestampNanos - lastNanos) / 1e9).coerceIn(0.0, 0.5)
        lastNanos = timestampNanos
        val innovation = wrap(sample - current)
        lastInnovationDeg = Math.toDegrees(innovation)

        val wasDisturbed = lastField == FieldQuality.DISTURBED
        lastField = field
        if (aligned) {
            state = State.ALIGNED
            return
        }
        if (field == FieldQuality.DISTURBED) {
            state = State.HELD
            outlierSinceNanos = null
            return
        }
        if (wasDisturbed) {
            // The compass is trustworthy again: re-seed from it rather than keep a stale hold.
            rewarm(timestampNanos)
            recovering = true
        }

        val settled = ((timestampNanos - settleStartNanos) / 1e9).coerceIn(warmupTauSeconds, tauSeconds)
        val tau = when {
            timestampNanos < warmupUntilNanos -> {
                state = if (recovering) State.RECOVERING else State.WARMUP
                warmupTauSeconds
            }
            abs(lastInnovationDeg) > outlierDeg -> {
                recovering = false
                val since = outlierSinceNanos ?: timestampNanos.also { outlierSinceNanos = it }
                if ((timestampNanos - since) / 1e9 > outlierResetSeconds) {
                    // A clean compass persistently far off: the gyro frame jumped. Re-converge fast.
                    outlierSinceNanos = null
                    rewarm(timestampNanos)
                    state = State.WARMUP
                    warmupTauSeconds
                } else {
                    state = State.TRACKING
                    tauSeconds
                }
            }
            else -> {
                recovering = false
                outlierSinceNanos = null
                state = State.TRACKING
                settled
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

package com.jakedonnini.sattracker.sensors

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Whether the magnetometer is currently reading the Earth's field or something else. */
enum class FieldQuality { UNKNOWN, CLEAN, DISTURBED }

/**
 * Magnetic interference detection (design doc §6.2a). The Earth's field at a place has a fixed
 * strength and dip angle (the World Magnetic Model gives both), so a measured field that differs
 * from them means nearby metal or electronics are bending it, and the compass heading can't be
 * trusted. On the test phone indoors this caught +13 % strength / +8° dip while Android still
 * reported "high" magnetometer accuracy. Pure, so it can be unit-tested.
 */
object MagneticCheck {
    /** Allowed relative difference in field strength from the model. */
    const val MAX_STRENGTH_DEVIATION = 0.08
    /** Allowed difference in dip angle from the model, degrees. */
    const val MAX_DIP_DEVIATION_DEG = 3.0

    fun strengthUt(b: FloatArray): Double =
        sqrt((b[0] * b[0] + b[1] * b[1] + b[2] * b[2]).toDouble())

    /**
     * Dip (inclination) of the field, degrees, positive downward: the device-frame field [b]
     * rotated into the world frame by [rotation] (device→world, rows East, North, Up).
     */
    fun dipDeg(rotation: FloatArray, b: FloatArray): Double {
        val e = rotation[0] * b[0] + rotation[1] * b[1] + rotation[2] * b[2]
        val n = rotation[3] * b[0] + rotation[4] * b[1] + rotation[5] * b[2]
        val u = rotation[6] * b[0] + rotation[7] * b[1] + rotation[8] * b[2]
        return Math.toDegrees(atan2(-u.toDouble(), hypot(e.toDouble(), n.toDouble())))
    }

    fun classify(strengthUt: Double, dipDeg: Double, expectedStrengthUt: Double, expectedDipDeg: Double): FieldQuality {
        if (expectedStrengthUt.isNaN() || expectedDipDeg.isNaN() || strengthUt <= 0) return FieldQuality.UNKNOWN
        val strengthOff = abs(strengthUt / expectedStrengthUt - 1)
        val dipOff = abs(dipDeg - expectedDipDeg)
        return if (strengthOff <= MAX_STRENGTH_DEVIATION && dipOff <= MAX_DIP_DEVIATION_DEG) FieldQuality.CLEAN
        else FieldQuality.DISTURBED
    }
}

/**
 * Debounces per-sample [FieldQuality]: the compass is called disturbed after [disturbAfterSeconds]
 * of disturbed samples, and trusted again only after [cleanAfterSeconds] of clean ones, so
 * walking past a lamp post doesn't flip the heading source back and forth.
 */
class FieldQualityFilter(
    private val disturbAfterSeconds: Double = 0.5,
    private val cleanAfterSeconds: Double = 3.0,
) {
    var current: FieldQuality = FieldQuality.UNKNOWN
        private set
    private var candidate: FieldQuality = FieldQuality.UNKNOWN
    private var candidateSince = 0L

    fun update(sample: FieldQuality, timestampNanos: Long): FieldQuality {
        if (sample == FieldQuality.UNKNOWN) {
            if (current == FieldQuality.UNKNOWN) return current
            // Keep the last verdict while the model expectation is briefly unavailable.
            return current
        }
        if (sample != candidate) {
            candidate = sample
            candidateSince = timestampNanos
        }
        val held = (timestampNanos - candidateSince) / 1e9
        val needed = if (candidate == FieldQuality.DISTURBED) disturbAfterSeconds else cleanAfterSeconds
        if (candidate != current && (held >= needed || current == FieldQuality.UNKNOWN)) current = candidate
        return current
    }
}

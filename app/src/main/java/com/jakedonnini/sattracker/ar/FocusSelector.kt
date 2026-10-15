package com.jakedonnini.sattracker.ar

import com.jakedonnini.sattracker.geometry.Enu
import kotlin.math.acos

/**
 * Chooses which satellite is "in focus": the one closest to the camera boresight.
 *
 * Hysteresis keeps the choice stable: a satellite takes focus within [enterDeg] of the
 * boresight, and keeps it until it drifts past [exitDeg] or another one is clearly closer.
 */
class FocusSelector(
    val enterDeg: Double = 3.0,
    val exitDeg: Double = 5.0,
    /** A challenger must be this much closer (deg) than the current focus to steal it. */
    private val switchMarginDeg: Double = 1.0,
) {
    var currentId: Int? = null
        private set

    /**
     * @param boresight unit vector of the camera axis, true-north ENU.
     * @param candidates satellite id → unit direction, true-north ENU.
     * @return the focused id, or null.
     */
    fun update(boresight: Enu, candidates: Map<Int, Enu>): Int? {
        var bestId: Int? = null
        var bestAngle = Double.MAX_VALUE
        for ((id, dir) in candidates) {
            val angle = angleDeg(boresight, dir)
            if (angle < bestAngle) {
                bestAngle = angle
                bestId = id
            }
        }

        val current = currentId
        val currentAngle = current?.let { candidates[it] }?.let { angleDeg(boresight, it) }

        currentId = when {
            currentAngle != null && currentAngle <= exitDeg &&
                (bestId == current || bestAngle > currentAngle - switchMarginDeg) -> current
            bestId != null && bestAngle <= enterDeg -> bestId
            else -> null
        }
        return currentId
    }

    private fun angleDeg(a: Enu, b: Enu) = Math.toDegrees(acos((a dot b).coerceIn(-1.0, 1.0)))
}

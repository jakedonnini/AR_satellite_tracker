package com.jakedonnini.sattracker.sensors

import com.jakedonnini.sattracker.geometry.Enu
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * RMS angular deviation of the camera boresight from its mean over a sliding window.
 * With the phone at rest this is pure sensor noise, which is what makes the overlay shimmer.
 */
class JitterMeter(private val windowSize: Int = 100) {
    private val buffer = ArrayDeque<Enu>(windowSize)

    /** Adds a unit direction; returns RMS deviation in degrees (null until the window fills). */
    fun add(direction: Enu): Double? {
        if (buffer.size == windowSize) buffer.removeFirst()
        buffer.addLast(direction)
        if (buffer.size < windowSize) return null

        var e = 0.0; var n = 0.0; var u = 0.0
        for (d in buffer) { e += d.e; n += d.n; u += d.u }
        val mean = Enu(e, n, u).unit()
        var sumSq = 0.0
        for (d in buffer) {
            val angle = Math.toDegrees(acos((d dot mean).coerceIn(-1.0, 1.0)))
            sumSq += angle * angle
        }
        return sqrt(sumSq / buffer.size)
    }

    fun reset() = buffer.clear()
}

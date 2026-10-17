package com.jakedonnini.sattracker.ar

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Small pure helpers for overlay interaction, kept here so they can be unit-tested. */
object OverlayGeometry {

    /**
     * The tappable marker nearest to (x, y) within [radius] pixels. Markers are kept in parallel
     * arrays (marker i at ([xs][i], [ys][i]), i < [count]) so the overlay can rebuild them every
     * frame without allocating. Returns the marker's index, or -1.
     */
    fun nearestIndex(xs: FloatArray, ys: FloatArray, count: Int, x: Float, y: Float, radius: Float): Int {
        var best = -1
        var bestDist = radius
        for (i in 0 until count) {
            val d = hypot(xs[i] - x, ys[i] - y)
            if (d <= bestDist) {
                best = i
                bestDist = d
            }
        }
        return best
    }

    /**
     * Where to put an off-screen indicator: the point where a ray from the view center in
     * direction ([dx], [dy]) (screen coordinates, y down) leaves the rectangle inset by [inset].
     * Returns null for a zero direction.
     */
    fun edgePoint(dx: Float, dy: Float, width: Float, height: Float, inset: Float): ScreenPoint? {
        if (dx == 0f && dy == 0f) return null
        val halfW = width / 2 - inset
        val halfH = height / 2 - inset
        val sx = if (dx != 0f) halfW / abs(dx) else Float.MAX_VALUE
        val sy = if (dy != 0f) halfH / abs(dy) else Float.MAX_VALUE
        val s = min(sx, sy)
        return ScreenPoint(width / 2 + dx * s, height / 2 + dy * s)
    }

    /** Lowest palette slot not in [used]; wraps around once every slot is taken. */
    fun nextColorSlot(used: Collection<Int>, paletteSize: Int): Int =
        (0 until paletteSize).firstOrNull { it !in used } ?: (used.size % paletteSize)
}

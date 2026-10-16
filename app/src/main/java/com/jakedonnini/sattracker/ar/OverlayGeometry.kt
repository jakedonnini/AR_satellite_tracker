package com.jakedonnini.sattracker.ar

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** A tappable satellite marker from the last drawn frame. */
data class HitTarget(val id: Int, val x: Float, val y: Float)

/** Small pure helpers for overlay interaction, kept here so they can be unit-tested. */
object OverlayGeometry {

    /** The target nearest to (x, y) within [radius] pixels, or null. */
    fun nearest(targets: List<HitTarget>, x: Float, y: Float, radius: Float): HitTarget? {
        var best: HitTarget? = null
        var bestDist = radius
        for (t in targets) {
            val d = hypot(t.x - x, t.y - y)
            if (d <= bestDist) {
                best = t
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

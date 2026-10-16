package com.jakedonnini.sattracker.sensors

import com.jakedonnini.sattracker.geometry.SkyMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class HeadingFusionTest {

    /** Device→world rotation for a phone in portrait, camera at [az] (deg), tilted up [el] (deg). */
    private fun pose(az: Double, el: Double): FloatArray {
        // Start from "upright, facing north" (device X=E, Y=Up, Z=S), tilt about device X, then yaw.
        val upright = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, -1.0), doubleArrayOf(0.0, 1.0, 0.0))
        val t = Math.toRadians(el)
        val tilt = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(t), -sin(t)), doubleArrayOf(0.0, sin(t), cos(t)))
        val y = Math.toRadians(-az) // positive az = clockwise from north = negative rotation about Up
        val yaw = arrayOf(doubleArrayOf(cos(y), -sin(y), 0.0), doubleArrayOf(sin(y), cos(y), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val r = mul(yaw, mul(upright, tilt))
        return FloatArray(9) { r[it / 3][it % 3].toFloat() }
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { a[i][it] * b[it][j] } } }

    private fun boresightAz(r: FloatArray) = SkyMath.toAzEl(SkyMath.cameraBoresight(r)).azimuthDeg

    private fun angleDiff(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)

    @Test
    fun `pose helper produces the intended boresight`() {
        val dir = SkyMath.toAzEl(SkyMath.cameraBoresight(pose(az = 30.0, el = 20.0)))
        assertEquals(30.0, dir.azimuthDeg, 1e-4)
        assertEquals(20.0, dir.elevationDeg, 1e-4)
    }

    @Test
    fun `yaw offset recovers the rotation between frames`() {
        val game = pose(az = 10.0, el = 35.0)
        val abs = pose(az = 10.0 + 42.0, el = 35.0) // same pose, frame rotated 42° clockwise
        // Clockwise azimuth shift = negative rotation about Up.
        assertEquals(-42.0, Math.toDegrees(HeadingFusion.yawOffset(abs, game)), 1e-4)
    }

    @Test
    fun `no output before the first absolute sample`() {
        assertNull(HeadingFusion().fuse(pose(0.0, 0.0)))
    }

    @Test
    fun `fused heading follows the compass while keeping gyro tilt`() {
        val fusion = HeadingFusion()
        val game = pose(az = 100.0, el = 25.0)    // game frame thinks we face 100°
        val abs = pose(az = 250.0, el = 25.0)     // compass says 250°
        var t = 0L
        repeat(500) { // 10 s at 50 Hz
            fusion.onAbsolute(abs, game, t)
            t += 20_000_000L
        }
        val fused = SkyMath.toAzEl(SkyMath.cameraBoresight(fusion.fuse(game)!!))
        assertEquals(250.0, fused.azimuthDeg, 0.05)
        assertEquals(25.0, fused.elevationDeg, 1e-4)
    }

    @Test
    fun `compass noise is strongly attenuated`() {
        val rnd = Random(42)
        val fusion = HeadingFusion()
        val game = pose(az = 0.0, el = 30.0)
        val trueAz = 60.0
        val noiseStdDeg = 3.0
        var t = 0L
        val raw = mutableListOf<Double>()
        val fused = mutableListOf<Double>()
        repeat(50 * 60) { i -> // 60 s at 50 Hz
            val noisyAz = trueAz + rnd.nextGaussian() * noiseStdDeg
            val abs = pose(noisyAz, 30.0)
            fusion.onAbsolute(abs, game, t)
            t += 20_000_000L
            if (i > 50 * 20) { // after settling
                raw += angleDiff(boresightAz(abs), trueAz)
                fused += angleDiff(boresightAz(fusion.fuse(game)!!), trueAz)
            }
        }
        val rawRms = sqrt(raw.sumOf { it * it } / raw.size)
        val fusedRms = sqrt(fused.sumOf { it * it } / fused.size)
        assertTrue("raw $rawRms° fused $fusedRms°", fusedRms < rawRms / 10)
        assertTrue("fused error $fusedRms°", fusedRms < 0.3)
    }

    @Test
    fun `brief magnetic disturbance barely moves the estimate`() {
        val fusion = HeadingFusion()
        val game = pose(0.0, 30.0)
        var t = 0L
        repeat(500) { fusion.onAbsolute(pose(60.0, 30.0), game, t); t += 20_000_000L }
        // 2 s next to a fridge: compass swings 40°.
        repeat(100) { fusion.onAbsolute(pose(100.0, 30.0), game, t); t += 20_000_000L }
        val az = boresightAz(fusion.fuse(game)!!)
        assertTrue("estimate moved to $az", angleDiff(az, 60.0) < 2.0)
    }

    @Test
    fun `persistent disagreement is accepted after the reset window`() {
        val fusion = HeadingFusion()
        val game = pose(0.0, 30.0)
        var t = 0L
        repeat(500) { fusion.onAbsolute(pose(60.0, 30.0), game, t); t += 20_000_000L }
        // Game frame got reset (or user walked somewhere the field really differs) for 8 s.
        repeat(400) { fusion.onAbsolute(pose(100.0, 30.0), game, t); t += 20_000_000L }
        assertEquals(100.0, boresightAz(fusion.fuse(game)!!), 0.5)
    }

    @Test
    fun `wraps correctly across north`() {
        val fusion = HeadingFusion()
        val game = pose(0.0, 10.0)
        var t = 0L
        repeat(500) { i ->
            // Compass alternates 358° / 2°: mean is 0°, not 180°.
            fusion.onAbsolute(pose(if (i % 2 == 0) 358.0 else 2.0, 10.0), game, t)
            t += 20_000_000L
        }
        assertTrue(angleDiff(boresightAz(fusion.fuse(game)!!), 0.0) < 0.5)
    }
}

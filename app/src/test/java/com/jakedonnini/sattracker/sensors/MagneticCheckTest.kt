package com.jakedonnini.sattracker.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

class MagneticCheckTest {

    // Phone flat, screen up: device axes = world axes (E, N, U).
    private val flat = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    @Test
    fun `dip angle of a field pointing north and down`() {
        // 30 µT horizontal north, 40 µT down: dip = atan(40/30) ≈ 53.13°, strength 50 µT.
        val b = floatArrayOf(0f, 30f, -40f)
        assertEquals(50.0, MagneticCheck.strengthUt(b), 1e-6)
        assertEquals(53.130, MagneticCheck.dipDeg(flat, b), 1e-3)
    }

    @Test
    fun `classification against the World Magnetic Model`() {
        // Expected at the test site: 46.8 µT, dip 56.8°.
        assertEquals(FieldQuality.CLEAN, MagneticCheck.classify(47.5, 57.9, 46.8, 56.8))
        // What the phone measured indoors: +13 % and +8°.
        assertEquals(FieldQuality.DISTURBED, MagneticCheck.classify(53.0, 64.9, 46.8, 56.8))
        assertEquals(FieldQuality.DISTURBED, MagneticCheck.classify(46.8, 61.0, 46.8, 56.8))
        assertEquals(FieldQuality.UNKNOWN, MagneticCheck.classify(47.0, 57.0, Double.NaN, Double.NaN))
    }

    @Test
    fun `filter flags disturbance fast and trusts again slowly`() {
        val f = FieldQualityFilter(disturbAfterSeconds = 0.5, cleanAfterSeconds = 3.0)
        var t = 0L
        fun step(q: FieldQuality, seconds: Double): FieldQuality {
            var out = f.current
            repeat((seconds * 50).toInt()) { out = f.update(q, t); t += 20_000_000L }
            return out
        }
        assertEquals(FieldQuality.CLEAN, step(FieldQuality.CLEAN, 1.0))     // first verdict is immediate
        assertEquals(FieldQuality.CLEAN, step(FieldQuality.DISTURBED, 0.3)) // brief blip ignored
        assertEquals(FieldQuality.DISTURBED, step(FieldQuality.DISTURBED, 1.0))
        assertEquals(FieldQuality.DISTURBED, step(FieldQuality.CLEAN, 2.0))  // not yet trusted
        assertEquals(FieldQuality.CLEAN, step(FieldQuality.CLEAN, 2.0))
    }
}

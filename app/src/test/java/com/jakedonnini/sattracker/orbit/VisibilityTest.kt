package com.jakedonnini.sattracker.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class VisibilityTest {

    private val sunAlongX = doubleArrayOf(1.0, 0.0, 0.0)

    @Test
    fun `shadow is a cylinder behind the Earth`() {
        // Day side.
        assertTrue(Visibility.isSunlit(doubleArrayOf(7000.0, 0.0, 0.0), sunAlongX))
        // Directly behind the Earth: eclipsed.
        assertFalse(Visibility.isSunlit(doubleArrayOf(-7000.0, 0.0, 0.0), sunAlongX))
        // Behind, but offset more than an Earth radius sideways: sunlit.
        assertTrue(Visibility.isSunlit(doubleArrayOf(-3000.0, 6500.0, 0.0), sunAlongX))
        // Behind and just inside the shadow edge.
        assertFalse(Visibility.isSunlit(doubleArrayOf(-3000.0, 6300.0, 0.0), sunAlongX))
    }

    @Test
    fun `magnitude equals the 1000-km magnitude at 1000 km and 90 degrees phase`() {
        assertEquals(5.5, Visibility.apparentMagnitude(5.5, 1000.0, PI / 2), 1e-9)
    }

    @Test
    fun `magnitude follows range and phase`() {
        // 10x farther = 5 magnitudes fainter.
        assertEquals(10.5, Visibility.apparentMagnitude(5.5, 10_000.0, PI / 2), 1e-9)
        // Fully lit (phase 0): brighter by 2.5·log10(π) ≈ 1.24 mag.
        assertEquals(5.5 - 1.2429, Visibility.apparentMagnitude(5.5, 1000.0, 0.0), 1e-3)
        // Backlit (phase near 180°): much fainter.
        assertTrue(Visibility.apparentMagnitude(5.5, 1000.0, PI * 0.95) > 9.0)
    }

    @Test
    fun `phase angle between sightlines`() {
        assertEquals(PI / 2, Visibility.phaseAngle(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 5.0, 0.0)), 1e-12)
        assertEquals(0.0, Visibility.phaseAngle(doubleArrayOf(2.0, 0.0, 0.0), doubleArrayOf(3.0, 0.0, 0.0)), 1e-9)
    }

    @Test
    fun `twilight classification`() {
        assertEquals(SkyCondition.DAYLIGHT, Visibility.skyCondition(10.0))
        assertEquals(SkyCondition.CIVIL_TWILIGHT, Visibility.skyCondition(-3.0))
        assertEquals(SkyCondition.NAUTICAL_TWILIGHT, Visibility.skyCondition(-9.0))
        assertEquals(SkyCondition.ASTRONOMICAL_TWILIGHT, Visibility.skyCondition(-15.0))
        assertEquals(SkyCondition.NIGHT, Visibility.skyCondition(-25.0))
    }

    @Test
    fun `intrinsic magnitude heuristics`() {
        assertEquals(-1.3, Visibility.intrinsicMagnitude("ISS (ZARYA)", 25544), 0.0)
        assertEquals(5.5, Visibility.intrinsicMagnitude("STARLINK-1234", 44000), 0.0)
        assertEquals(4.0, Visibility.intrinsicMagnitude("CZ-4C R/B", 50000), 0.0)
        assertEquals(8.0, Visibility.intrinsicMagnitude("FENGYUN 1C DEB", 30000), 0.0)
        assertEquals(6.0, Visibility.intrinsicMagnitude("SOME SAT", 1), 0.0)
    }

    private val iss = Visibility.intrinsicMagnitude("ISS (ZARYA)", 25544)

    @Test
    fun `classification follows the four criteria in order`() {
        // Observer on +y axis at the surface, Sun along +x, ISS 500 km straight above the observer.
        val observer = doubleArrayOf(0.0, 6371.0, 0.0)
        val overhead = doubleArrayOf(0.0, 6871.0, 0.0)
        val shadowed = doubleArrayOf(-6871.0, 0.0, 0.0)

        val dark = Visibility.estimate(iss, overhead, observer, sunAlongX, 90.0, -10.0)
        assertEquals(NakedEye.LIKELY_VISIBLE, dark.status)
        assertTrue("ISS overhead is bright: ${dark.magnitude}", dark.magnitude!! < 0.0)

        assertEquals(NakedEye.SKY_TOO_BRIGHT, Visibility.estimate(iss, overhead, observer, sunAlongX, 90.0, -2.0).status)
        assertEquals(NakedEye.TOO_LOW, Visibility.estimate(iss, overhead, observer, sunAlongX, 5.0, -10.0).status)
        val inShadow = Visibility.estimate(iss, shadowed, observer, sunAlongX, 30.0, -30.0)
        assertEquals(NakedEye.IN_SHADOW, inShadow.status)
        assertNull(inShadow.magnitude)
    }
}

package com.jakedonnini.sattracker.sensors

import com.jakedonnini.sattracker.geometry.AzEl
import com.jakedonnini.sattracker.geometry.SkyMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JitterMeterTest {

    @Test
    fun `null until the window fills, zero when still`() {
        val meter = JitterMeter(windowSize = 10)
        val dir = SkyMath.toEnu(AzEl(45.0, 30.0))
        repeat(9) { assertNull(meter.add(dir)) }
        assertEquals(0.0, meter.add(dir)!!, 1e-6)
    }

    @Test
    fun `alternating plus-minus one degree reads one degree RMS`() {
        val meter = JitterMeter(windowSize = 10)
        var last: Double? = null
        repeat(10) { i -> last = meter.add(SkyMath.toEnu(AzEl(0.0, if (i % 2 == 0) 1.0 else -1.0))) }
        assertEquals(1.0, last!!, 1e-3)
    }
}

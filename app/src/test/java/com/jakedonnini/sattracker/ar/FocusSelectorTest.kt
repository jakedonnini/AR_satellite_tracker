package com.jakedonnini.sattracker.ar

import com.jakedonnini.sattracker.geometry.AzEl
import com.jakedonnini.sattracker.geometry.SkyMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusSelectorTest {

    private val boresight = SkyMath.toEnu(AzEl(0.0, 45.0))
    private fun at(az: Double, el: Double) = SkyMath.toEnu(AzEl(az, el))

    @Test
    fun `nothing within enter angle means no focus`() {
        assertNull(FocusSelector().update(boresight, mapOf(1 to at(0.0, 40.0))))
    }

    @Test
    fun `closest satellite within enter angle takes focus`() {
        val sel = FocusSelector()
        assertEquals(2, sel.update(boresight, mapOf(1 to at(0.0, 47.5), 2 to at(0.0, 46.0))))
    }

    @Test
    fun `focus is kept out to the exit angle`() {
        val sel = FocusSelector()
        sel.update(boresight, mapOf(1 to at(0.0, 46.0)))
        assertEquals(1, sel.update(boresight, mapOf(1 to at(0.0, 49.0))))  // 4° off: still held
        assertNull(sel.update(boresight, mapOf(1 to at(0.0, 51.0))))       // 6° off: released
    }

    @Test
    fun `a challenger must be clearly closer to steal focus`() {
        val sel = FocusSelector()
        sel.update(boresight, mapOf(1 to at(0.0, 47.0)))
        // Challenger only 0.5° closer: focus stays.
        assertEquals(1, sel.update(boresight, mapOf(1 to at(0.0, 47.0), 2 to at(0.0, 43.5))))
        // Challenger 1.5° closer: focus moves.
        assertEquals(2, sel.update(boresight, mapOf(1 to at(0.0, 47.0), 2 to at(0.0, 44.5))))
    }

    @Test
    fun `focused satellite disappearing releases focus`() {
        val sel = FocusSelector()
        sel.update(boresight, mapOf(1 to at(0.0, 46.0)))
        assertNull(sel.update(boresight, emptyMap()))
    }
}

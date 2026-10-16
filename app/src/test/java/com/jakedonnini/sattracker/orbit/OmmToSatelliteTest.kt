package com.jakedonnini.sattracker.orbit

import com.jakedonnini.sattracker.OrekitTestData
import com.jakedonnini.sattracker.data.remote.OmmRecord
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.orekit.frames.FramesFactory
import org.orekit.propagation.analytical.tle.TLE
import org.orekit.propagation.analytical.tle.TLEPropagator

class OmmToSatelliteTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() = OrekitTestData.load()

    private fun issFromJson() =
        json.decodeFromString<List<OmmRecord>>(OrekitTestData.fixture("iss.json")).single().toSatellite()

    private fun issFromTleText(): TLE {
        val lines = OrekitTestData.fixture("iss.tle").lines().map { it.trim() }.filter { it.isNotEmpty() }
        return TLE(lines[1], lines[2])
    }

    @Test
    fun `OMM elements match the TLE text to the TLE format's precision`() {
        val fromOmm = issFromJson().tle
        val fromText = issFromTleText()
        // TLE text rounds fields (e.g. e to 7 decimals, angles to 1e-4°); OMM keeps more digits.
        val angleTol = Math.toRadians(0.5e-4)

        assertEquals(fromText.satelliteNumber, fromOmm.satelliteNumber)
        assertEquals(0.0, fromText.date.durationFrom(fromOmm.date), 1e-3)
        assertEquals(fromText.meanMotion, fromOmm.meanMotion, 1e-12)
        assertEquals(fromText.meanMotionFirstDerivative, fromOmm.meanMotionFirstDerivative, 1e-18)
        assertEquals(fromText.e, fromOmm.e, 0.5e-7)
        assertEquals(fromText.i, fromOmm.i, angleTol)
        assertEquals(fromText.raan, fromOmm.raan, angleTol)
        assertEquals(fromText.perigeeArgument, fromOmm.perigeeArgument, angleTol)
        assertEquals(fromText.meanAnomaly, fromOmm.meanAnomaly, angleTol)
        assertEquals(fromText.bStar, fromOmm.bStar, 1e-4 * Math.abs(fromText.bStar)) // 5 significant digits
    }

    @Test
    fun `OMM and TLE text propagate to the same position a day later`() {
        val fromOmm = issFromJson().tle
        val fromText = issFromTleText()
        val date = fromText.date.shiftedBy(86_400.0)
        val teme = FramesFactory.getTEME()

        val a = TLEPropagator.selectExtrapolator(fromOmm).getPVCoordinates(date, teme).position
        val b = TLEPropagator.selectExtrapolator(fromText).getPVCoordinates(date, teme).position

        // TLE text rounds B* to 5 significant digits; OMM keeps more, so allow a few metres.
        assertEquals(0.0, a.subtract(b).norm, 5.0)
    }

    @Test
    fun `six-digit catalog numbers are supported`() {
        val record = json.decodeFromString<List<OmmRecord>>(OrekitTestData.fixture("iss.json")).single()
            .copy(noradCatId = 123456, objectId = "2026-150B")
        val sat = record.toSatellite()

        assertEquals(123456, sat.noradId)
        assertEquals(123456, sat.tle.satelliteNumber)
        // Must propagate without ever formatting a TLE line.
        TLEPropagator.selectExtrapolator(sat.tle).propagate(sat.tle.date.shiftedBy(3600.0))
    }
}

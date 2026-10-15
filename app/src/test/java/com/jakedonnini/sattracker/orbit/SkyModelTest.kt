package com.jakedonnini.sattracker.orbit

import com.jakedonnini.sattracker.OrekitTestData
import com.jakedonnini.sattracker.data.remote.OmmRecord
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.orekit.bodies.GeodeticPoint
import org.orekit.bodies.OneAxisEllipsoid
import org.orekit.frames.FramesFactory
import org.orekit.frames.TopocentricFrame
import org.orekit.propagation.analytical.tle.TLEPropagator
import org.orekit.utils.Constants
import org.orekit.utils.IERSConventions

class SkyModelTest {

    @Before
    fun setUp() = OrekitTestData.load()

    @Test
    fun `shared-transform az-el matches Orekit's TopocentricFrame`() {
        val sat = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<OmmRecord>>(OrekitTestData.fixture("iss.json")).single().toSatellite()
        val observer = Observer(latDeg = 39.95, lonDeg = -75.19, altMeters = 20.0) // Philadelphia
        val earth = OneAxisEllipsoid(
            Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
            Constants.WGS84_EARTH_FLATTENING,
            FramesFactory.getITRF(IERSConventions.IERS_2010, true),
        )
        val topo = TopocentricFrame(
            earth,
            GeodeticPoint(Math.toRadians(observer.latDeg), Math.toRadians(observer.lonDeg), observer.altMeters),
            "ref",
        )
        val teme = FramesFactory.getTEME()
        val propagator = TLEPropagator.selectExtrapolator(sat.tle)
        val model = SkyModel(listOf(sat))

        // Sample a day at 10-minute steps so both above- and below-horizon cases are covered.
        for (step in 0 until 144) {
            val date = sat.tle.date.shiftedBy(step * 600.0)
            val p = propagator.getPVCoordinates(date, teme).position
            val expectedEl = Math.toDegrees(topo.getElevation(p, teme, date))
            val expectedAz = Math.toDegrees(topo.getAzimuth(p, teme, date))

            val actual = model.positions(observer, date, minElevationDeg = -90.0).single()
            assertEquals("elevation at step $step", expectedEl, actual.elevationDeg, 1e-6)
            val azDiff = Math.abs(((expectedAz - actual.azimuthDeg + 540.0) % 360.0) - 180.0)
            assertTrue("azimuth at step $step: $expectedAz vs ${actual.azimuthDeg}", azDiff < 1e-6)
        }
    }
}

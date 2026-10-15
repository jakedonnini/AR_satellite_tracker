package com.jakedonnini.sattracker.orbit

import com.jakedonnini.sattracker.OrekitTestData
import com.jakedonnini.sattracker.data.remote.OmmRecord
import com.jakedonnini.sattracker.geometry.SkyMath
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
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import org.orekit.utils.Constants
import org.orekit.utils.IERSConventions

class SkyModelTest {

    private val observer = Observer(latDeg = 39.95, lonDeg = -75.19, altMeters = 20.0) // Philadelphia
    private lateinit var sat: Satellite
    private lateinit var model: SkyModel
    private lateinit var topo: TopocentricFrame

    @Before
    fun setUp() {
        OrekitTestData.load()
        sat = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<OmmRecord>>(OrekitTestData.fixture("iss.json")).single().toSatellite()
        model = SkyModel(listOf(sat))
        val earth = OneAxisEllipsoid(
            Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
            Constants.WGS84_EARTH_FLATTENING,
            FramesFactory.getITRF(IERSConventions.IERS_2010, true),
        )
        topo = TopocentricFrame(
            earth,
            GeodeticPoint(Math.toRadians(observer.latDeg), Math.toRadians(observer.lonDeg), observer.altMeters),
            "ref",
        )
    }

    private fun millisOf(date: AbsoluteDate) = date.toDate(TimeScalesFactory.getUTC()).time

    @Test
    fun `snapshot az-el matches Orekit's TopocentricFrame`() {
        val teme = FramesFactory.getTEME()
        val propagator = TLEPropagator.selectExtrapolator(sat.tle)

        // Sample a day at 10-minute steps so both above- and below-horizon cases are covered.
        for (step in 0 until 144) {
            val millis = millisOf(sat.tle.date) + step * 600_000L
            val date = AbsoluteDate(java.util.Date(millis), TimeScalesFactory.getUTC())
            val p = propagator.getPVCoordinates(date, teme).position
            val expectedEl = Math.toDegrees(topo.getElevation(p, teme, date))
            val expectedAz = Math.toDegrees(topo.getAzimuth(p, teme, date))

            val obj = model.snapshot(observer, millis, minElevationDeg = -90.0).objects.single()
            val actual = SkyMath.toAzEl(obj.position)
            assertEquals("elevation at step $step", expectedEl, actual.elevationDeg, 1e-6)
            val azDiff = Math.abs(((expectedAz - actual.azimuthDeg + 540.0) % 360.0) - 180.0)
            assertTrue("azimuth at step $step: $expectedAz vs ${actual.azimuthDeg}", azDiff < 1e-6)
        }
    }

    @Test
    fun `velocity extrapolation stays within 0_05 deg over 3 seconds`() {
        val t0 = millisOf(sat.tle.date)
        for (step in 0 until 144) {
            val start = t0 + step * 600_000L
            val obj = model.snapshot(observer, start, minElevationDeg = -90.0).objects.single()
            val truth = model.snapshot(observer, start + 3000, minElevationDeg = -90.0).objects.single()
            val angle = Math.toDegrees(
                Math.acos((obj.positionAfter(3.0).unit() dot truth.position.unit()).coerceIn(-1.0, 1.0))
            )
            assertTrue("step $step drift $angle°", angle < 0.05)
        }
    }

    @Test
    fun `elevation mask filters the snapshot`() {
        val t0 = millisOf(sat.tle.date)
        val all = (0 until 144).map { model.snapshot(observer, t0 + it * 600_000L, -90.0).objects.single() }
        val visibleCount = (0 until 144).count { model.snapshot(observer, t0 + it * 600_000L, 0.0).objects.isNotEmpty() }
        assertEquals(all.count { it.position.u > 0 }, visibleCount)
    }

    @Test
    fun `details are physically sensible for the ISS`() {
        val d = model.details(sat, observer, millisOf(sat.tle.date))
        assertTrue("altitude ${d.altitudeKm}", d.altitudeKm in 370.0..460.0)
        assertTrue("speed ${d.speedKmS}", d.speedKmS in 7.5..7.8)
        assertTrue("range ${d.rangeKm}", d.rangeKm > 370.0)
    }

    @Test
    fun `rising flag agrees with elevation one second later`() {
        val t0 = millisOf(sat.tle.date)
        for (step in 0 until 144) {
            val t = t0 + step * 600_000L
            val now = model.details(sat, observer, t)
            val later = model.details(sat, observer, t + 1000)
            assertEquals("step $step", later.elevationDeg > now.elevationDeg, now.rising)
        }
    }

    @Test
    fun `track points are unit vectors at the requested times`() {
        val t0 = millisOf(sat.tle.date)
        val track = model.track(sat, observer, t0, t0 + 600_000L, stepSeconds = 30)
        assertEquals(21, track.size)
        assertEquals(t0 + 300_000L, track[10].epochMillis)
        track.forEach { assertEquals(1.0, it.direction.norm, 1e-9) }
        // Matches the snapshot direction at the same instant.
        val snap = model.snapshot(observer, track[10].epochMillis, -90.0).objects.single().position.unit()
        assertEquals(1.0, track[10].direction dot snap, 1e-12)
    }
}

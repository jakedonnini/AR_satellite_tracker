package com.jakedonnini.sattracker.air

import com.jakedonnini.sattracker.OrekitTestData
import com.jakedonnini.sattracker.data.remote.OpenSkyClient
import com.jakedonnini.sattracker.orbit.Observer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftTest {

    /** Real `/states/all` response for a box around Philadelphia, captured 2026-10-17 (time 1792266445). */
    private val body = OrekitTestData.fixture("opensky_philadelphia.json")
    private val now = 1792266445L

    @Test
    fun `parse keeps only airborne aircraft with a fresh position`() {
        val list = OpenSkyClient.parse(body, now)
        // 40 states: on-ground, missing and >30 s old positions dropped.
        assertEquals(31, list.size)
        val viv = list.first { it.icao24 == "0d1218" }
        assertEquals("VIV101", viv.callsign) // padding trimmed
        assertEquals("Mexico", viv.originCountry)
        assertEquals(40.1559, viv.latDeg, 1e-9)
        assertEquals(-75.0771, viv.lonDeg, 1e-9)
        assertEquals(7299.96, viv.altitudeM, 1e-9) // geometric altitude preferred
        assertTrue(viv.altitudeIsGeometric)
        assertEquals(171.8, viv.speedMs, 1e-9)
        assertEquals(263.81, viv.trackDeg, 1e-9)
    }

    @Test
    fun `parse treats null states as no aircraft`() {
        assertEquals(0, OpenSkyClient.parse("""{"time":1,"states":null}""", 1).size)
    }

    @Test
    fun `aircraft overhead is straight up at its height`() {
        val obs = Observer(39.95, -75.19, 20.0)
        val a = aircraft(lat = 39.95, lon = -75.19, altM = 10_020.0, speed = 250.0, track = 90.0)
        val s = AirGeometry.toSky(a, obs)
        assertEquals(10.0, s.rangeKm, 0.001)
        assertEquals(90.0, s.elevationDeg, 0.01)
        // Flying east at 250 m/s.
        assertEquals(0.25, s.ve, 1e-9)
        assertEquals(0.0, s.vn, 1e-9)
    }

    @Test
    fun `aircraft to the north is north, at the expected elevation`() {
        val obs = Observer(39.95, -75.19, 0.0)
        // 0.3° of latitude ≈ 33.3 km north, 10 km up → about 16.6° (less a little for Earth's curvature).
        val s = AirGeometry.toSky(aircraft(lat = 40.25, lon = -75.19, altM = 10_000.0), obs)
        assertTrue(s.n > 33.0 && s.n < 33.5)
        assertEquals(0.0, s.e, 0.01)
        assertEquals(16.4, s.elevationDeg, 0.3)
        assertEquals("TEST1  10.0 km", s.label)
    }

    private fun aircraft(lat: Double, lon: Double, altM: Double, speed: Double = 0.0, track: Double = 0.0) = Aircraft(
        icao24 = "abc123", callsign = "TEST1", originCountry = "United States",
        latDeg = lat, lonDeg = lon, altitudeM = altM, altitudeIsGeometric = true,
        speedMs = speed, trackDeg = track, verticalRateMs = 0.0, positionTimeSec = 0,
    )
}

package com.jakedonnini.sattracker.air

import com.jakedonnini.sattracker.OrekitTestData
import com.jakedonnini.sattracker.data.remote.AdsbdbClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** adsbdb responses captured 2026-10-18. */
class RouteCheckTest {

    private val thy = AdsbdbClient.parseRoute(OrekitTestData.fixture("adsbdb_route_thy8br.json"))!!
    private val dal = AdsbdbClient.parseRoute(OrekitTestData.fixture("adsbdb_route_dal2412.json"))!!

    @Test
    fun `parses route and airline`() {
        assertEquals("Turkish Airlines", thy.airline?.name)
        assertEquals("EBL", thy.origin.code)
        assertEquals("IST", thy.destination.code)
        assertEquals("Turkey", thy.destination.country)
    }

    @Test
    fun `parses airframe details, and unknown addresses as null`() {
        val a = AdsbdbClient.parseAircraft(OrekitTestData.fixture("adsbdb_aircraft_a379d3.json"))!!
        assertEquals("A220-300", a.type)
        assertEquals("N323DU", a.registration)
        assertEquals("Delta Air Lines", a.owner)
        assertNull(AdsbdbClient.parseAircraft(OrekitTestData.fixture("adsbdb_aircraft_unknown.json")))
    }

    @Test
    fun `route fits an aircraft midway and heading for the destination`() {
        // Between Erbil and Istanbul, flying west-north-west.
        val toIst = RouteCheck.bearingDeg(38.9, 36.6, thy.destination.latitude, thy.destination.longitude)
        assertTrue(RouteCheck.plausible(thy, 38.9, 36.6, toIst))
    }

    @Test
    fun `route is rejected when flying away from the destination`() {
        val toIst = RouteCheck.bearingDeg(38.9, 36.6, thy.destination.latitude, thy.destination.longitude)
        assertFalse(RouteCheck.plausible(thy, 38.9, 36.6, (toIst + 180) % 360))
        // Unknown track: position alone decides.
        assertTrue(RouteCheck.plausible(thy, 38.9, 36.6, null))
    }

    @Test
    fun `stale database route is rejected for an aircraft far from it`() {
        // DAL2412 was over Philadelphia (OpenSky fixture) while adsbdb lists LAX to SFO.
        assertFalse(RouteCheck.plausible(dal, 40.0438, -74.8405, 70.66))
    }

    @Test
    fun `any heading is fine near an airport`() {
        // 30 km from Istanbul Airport, turning onto the approach heading away from it.
        assertTrue(RouteCheck.plausible(thy, 41.0, 29.0, 120.0))
    }
}

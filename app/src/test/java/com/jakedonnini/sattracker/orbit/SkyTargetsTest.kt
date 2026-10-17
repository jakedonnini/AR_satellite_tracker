package com.jakedonnini.sattracker.orbit

import com.jakedonnini.sattracker.OrekitTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class SkyTargetsTest {

    private val philly = Observer(latDeg = 39.95, lonDeg = -75.19, altMeters = 20.0)
    private lateinit var targets: SkyTargets

    @Before
    fun setUp() {
        OrekitTestData.load()
        targets = SkyTargets()
    }

    @Test
    fun `Polaris sits near the pole at the observer's latitude`() {
        val t = Instant.parse("2026-10-17T02:00:00Z").toEpochMilli()
        val p = targets.position("Polaris", philly, t)!!
        // Polaris is 0.73° from the pole, so it's within that of (az 0, el = latitude).
        assertEquals(philly.latDeg, p.elevationDeg, 0.8)
        val azOff = Math.abs(((p.azimuthDeg + 180) % 360) - 180)
        assertTrue("Polaris azimuth ${p.azimuthDeg}", azOff < 1.2)
    }

    @Test
    fun `Sun elevation agrees with the sky model's`() {
        val sat = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<List<com.jakedonnini.sattracker.data.remote.OmmRecord>>(OrekitTestData.fixture("iss.json"))
            .single().toSatellite()
        val t = Instant.parse("2026-10-17T15:00:00Z").toEpochMilli()
        val sun = targets.position("Sun", philly, t)!!
        val snap = SkyModel(listOf(sat)).snapshot(philly, t, -90.0)
        assertEquals(snap.sunElevationDeg, sun.elevationDeg, 0.01)
    }

    @Test
    fun `star catalogue is the expected bright stars`() {
        assertEquals(42, SkyTargets.STARS.size)
        val sirius = SkyTargets.STARS.first { it.name == "Sirius" }
        assertEquals(-16.716, sirius.decDeg, 0.001)
        assertTrue(SkyTargets.STARS.any { it.name == "Vega" })
    }

    @Test
    fun `visible list is filtered by elevation and ordered Moon, planets, stars, Sun`() {
        val t = Instant.parse("2026-10-17T02:00:00Z").toEpochMilli()
        val list = targets.visible(philly, t)
        assertTrue(list.all { it.elevationDeg >= 10.0 })
        val kinds = list.map { it.kind.ordinal }
        val order = listOf(TargetKind.MOON, TargetKind.PLANET, TargetKind.STAR, TargetKind.SUN)
        assertEquals(kinds.sortedBy { order.indexOf(TargetKind.entries[it]) }, kinds)
        assertNotNull(list.firstOrNull { it.kind == TargetKind.STAR })
    }
}

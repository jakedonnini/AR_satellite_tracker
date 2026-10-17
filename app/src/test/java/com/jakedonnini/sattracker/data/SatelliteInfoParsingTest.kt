package com.jakedonnini.sattracker.data

import com.jakedonnini.sattracker.OrekitTestData
import com.jakedonnini.sattracker.data.remote.SatelliteInfoClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing tested against real responses saved in test/resources/fixtures (2026-10-16/17).
 * satnogs_fox1b.json is trimmed to the fields we read.
 */
class SatelliteInfoParsingTest {

    @Test
    fun `SATCAT record for the ISS`() {
        val r = SatelliteInfoClient.parseSatcat(OrekitTestData.fixture("satcat_iss.json"))!!
        assertEquals(25544, r.noradId)
        assertEquals("ISS (ZARYA)", r.name)
        assertEquals("PAY", r.objectType)
        assertEquals("ISS", r.owner)
        assertEquals("1998-11-20", r.launchDate)
        assertEquals("TYMSC", r.launchSite)
        assertEquals(92.97, r.periodMin!!, 1e-9)
        assertEquals(425.0, r.apogeeKm!!, 1e-9)
        assertTrue(r.rcsM2!! > 100)
    }

    @Test
    fun `Wikidata search finds the item with that catalog number`() {
        assertEquals("Q25271", SatelliteInfoClient.parseWikidataSearch(OrekitTestData.fixture("wikidata_search_iss.json")))
        assertNull(SatelliteInfoClient.parseWikidataSearch("""{"batchcomplete":"","query":{"searchinfo":{"totalhits":0},"search":[]}}"""))
    }

    @Test
    fun `Wikidata sitelink gives the English Wikipedia title`() {
        assertEquals(
            "International Space Station",
            SatelliteInfoClient.parseEnwikiTitle(OrekitTestData.fixture("wikidata_sitelinks_iss.json"), "Q25271"),
        )
        assertNull(SatelliteInfoClient.parseEnwikiTitle("""{"entities":{"Q1":{"sitelinks":{}}}}""", "Q1"))
    }

    @Test
    fun `Wikipedia summary has text, description and thumbnail`() {
        val s = SatelliteInfoClient.parseSummary(OrekitTestData.fixture("wikipedia_summary_iss.json"))!!
        assertEquals("International Space Station", s.title)
        assertTrue(s.extract.startsWith("The International Space Station"))
        assertTrue(s.thumbnail!!.source.startsWith("https://"))
        assertTrue(s.pageUrl!!.contains("International_Space_Station"))
    }

    @Test
    fun `SatNOGS record has aliases, photo URL and filters the None operator`() {
        val iss = SatelliteInfoClient.parseSatnogs(OrekitTestData.fixture("satnogs_iss.json"))!!
        assertEquals("ISS", iss.name)
        assertEquals(listOf("ZARYA", "RS0ISS", "NA1SS"), iss.aliases)
        assertEquals("https://db-satnogs.freetls.fastly.net/media/satellites/ISS.jpg", iss.imageUrl)
        assertNull(iss.operatorName) // SatNOGS writes "None"
        assertEquals("RU,US", iss.countries)

        val fox = SatelliteInfoClient.parseSatnogs(OrekitTestData.fixture("satnogs_fox1b.json"))!!
        assertEquals("FOX-1B", fox.name)
        assertTrue(fox.aliases.contains("AO-91"))
        assertTrue(fox.website!!.startsWith("https://"))
        assertNull(SatelliteInfoClient.parseSatnogs("[]"))
    }

    @Test
    fun `Wikipedia search lists titles in rank order`() {
        assertEquals(
            listOf("NOAA-19", "GOES-19", "NOAA-20"),
            SatelliteInfoClient.parseWikipediaSearch(OrekitTestData.fixture("wikipedia_search_noaa19.json")),
        )
        assertEquals(emptyList<String>(), SatelliteInfoClient.parseWikipediaSearch("oops"))
    }

    @Test
    fun `search candidates split aliases and skip junk`() {
        assertEquals(
            listOf("ISS (ZARYA)", "ISS", "ZARYA", "RS0ISS"),
            SatelliteFacts.searchCandidates(listOf("ISS (ZARYA)", "ISS", "RS0ISS", " ")),
        )
        assertEquals(emptyList<String>(), SatelliteFacts.searchCandidates(listOf("CZ-4C R/B", "FENGYUN 1C DEB", "AB")))
    }

    @Test
    fun `relevance check accepts true matches and rejects neighbours`() {
        assertTrue(SatelliteFacts.isRelevantTitle("NOAA 19", "NOAA-19"))
        assertTrue(SatelliteFacts.isRelevantTitle("SENTINEL-2A", "Sentinel-2"))
        assertTrue(SatelliteFacts.isRelevantTitle("HST", "HST (satellite)"))
        assertTrue(!SatelliteFacts.isRelevantTitle("NOAA 19", "GOES-19"))
        assertTrue(!SatelliteFacts.isRelevantTitle("NOAA 19", "NOAA-20"))
        assertTrue(!SatelliteFacts.isRelevantTitle("COSMOS 2558", "Cosmos"))
        assertTrue(SatelliteFacts.skipsSpecificSearch("STARLINK-1007"))
        // NOAA / COSMOS satellites often have their own articles: search them first.
        assertTrue(!SatelliteFacts.skipsSpecificSearch("NOAA 19"))
        assertTrue(!SatelliteFacts.skipsSpecificSearch("COSMOS 2558"))
    }

    @Test
    fun `CHECKMATE 8 never resolves to the DC Comics article`() {
        // Real search results for "CHECKMATE satellite" (2026-10-17).
        val titles = SatelliteInfoClient.parseWikipediaSearch(OrekitTestData.fixture("wikipedia_search_checkmate.json"))
        assertEquals("Checkmate (comics)", titles.first())
        // Title check: a non-space disambiguator is rejected even though "checkmate" + "8" fits.
        assertTrue(titles.none { SatelliteFacts.isRelevantTitle("CHECKMATE 8", it) })
        // Content check: the article itself is not about a spacecraft.
        val dc = SatelliteInfoClient.parseSummary(OrekitTestData.fixture("wikipedia_summary_checkmate_dc.json"))!!
        assertTrue(!SatelliteFacts.isSpacecraftArticle(dc.description, dc.extract))
    }

    @Test
    fun `spacecraft articles pass the content check`() {
        // Short descriptions / intros as returned by Wikipedia (2026-10-17).
        assertTrue(SatelliteFacts.isSpacecraftArticle("American earth observation satellite", "Landsat 9 is an Earth observation satellite launched on 27 September 2021"))
        assertTrue(SatelliteFacts.isSpacecraftArticle("ESA's space program to study Earth's magnetic field", "Swarm is a European Space Agency (ESA) mission to study the Earth's magnetic field."))
        assertTrue(SatelliteFacts.isSpacecraftArticle("Series of European meteorological satellites", "MetOp is a series of three polar-orbiting meteorological satellites"))
        val iss = SatelliteInfoClient.parseSummary(OrekitTestData.fixture("wikipedia_summary_iss.json"))!!
        assertTrue(SatelliteFacts.isSpacecraftArticle(iss.description, iss.extract))
        // Whole-word rejection: "broadband" and "contractor" are fine.
        assertTrue(SatelliteFacts.isSpacecraftArticle("Broadband communications satellite", "Built by a defense contractor and launched in 2020."))
        // Not spacecraft.
        assertTrue(!SatelliteFacts.isSpacecraftArticle("2019 film directed by someone", "Aqua is a film about a satellite."))
        assertTrue(!SatelliteFacts.isSpacecraftArticle("Village in Poland", "Swarm is a village in the administrative district."))
    }

    @Test
    fun `allowed and rejected disambiguators`() {
        assertTrue(SatelliteFacts.isRelevantTitle("AQUA", "Aqua (satellite)"))
        assertTrue(SatelliteFacts.isRelevantTitle("SWARM A", "Swarm (spacecraft)"))
        assertTrue(!SatelliteFacts.isRelevantTitle("CHECKMATE", "Checkmate (comics)"))
        assertTrue(!SatelliteFacts.isRelevantTitle("TERRA", "Terra (band)"))
    }

    @Test
    fun `launch year cross-check separates same-named spacecraft`() {
        val exact = SatelliteFacts.TitleMatch.EXACT
        val program = SatelliteFacts.TitleMatch.PROGRAM
        assertEquals(exact, SatelliteFacts.titleMatch("LANDSAT 9", "Landsat 9"))
        assertEquals(program, SatelliteFacts.titleMatch("METOP-B", "MetOp"))
        // Landsat 9 article mentions its 2021 launch.
        assertTrue(SatelliteFacts.matchesLaunchYear(exact, "American earth observation satellite", "Landsat 9 is an Earth observation satellite launched on 27 September 2021", 2021))
        // A LEO object named JUNO launched in 2019 must not get NASA's 2011 Jupiter probe.
        assertTrue(!SatelliteFacts.matchesLaunchYear(exact, "Second NASA orbiter mission to Jupiter (2011–Present)", "Juno is a NASA space probe orbiting Jupiter. It was launched in 2011.", 2019))
        // Series intro with no years: acceptable as program info, never as an exact article.
        assertTrue(SatelliteFacts.matchesLaunchYear(program, "Series of European meteorological satellites", "MetOp is a series of three polar-orbiting meteorological satellites", 2012))
        assertTrue(!SatelliteFacts.matchesLaunchYear(exact, "Series of European meteorological satellites", "MetOp is a series of three polar-orbiting meteorological satellites", 2012))
        // No catalog year to verify against: reject.
        assertTrue(!SatelliteFacts.matchesLaunchYear(exact, "satellite", "launched in 2021", null))
    }

    @Test
    fun `NOAA satellites get no blanket fallback article`() {
        // NOAA-20/21 are JPSS, not POES: a series-wide fallback would be wrong for some.
        assertNull(SatelliteFacts.fallbackArticle("NOAA 20", "PAY"))
    }

    @Test
    fun `malformed responses parse to null instead of throwing`() {
        assertNull(SatelliteInfoClient.parseSatcat("<html>rate limited</html>"))
        assertNull(SatelliteInfoClient.parseSummary("{}"))
        assertNull(SatelliteInfoClient.parseWikidataSearch("not json"))
    }

    @Test
    fun `constellation and object-class fallbacks`() {
        assertEquals("Starlink", SatelliteFacts.fallbackArticle("STARLINK-31415", "PAY"))
        assertEquals("OneWeb satellite constellation", SatelliteFacts.fallbackArticle("ONEWEB-0123", "PAY"))
        assertEquals("Multistage rocket", SatelliteFacts.fallbackArticle("CZ-4C R/B", "R/B"))
        assertEquals("Space debris", SatelliteFacts.fallbackArticle("FENGYUN 1C DEB", "DEB"))
        assertNull(SatelliteFacts.fallbackArticle("SOME CUBESAT", "PAY"))
    }

    @Test
    fun `code labels`() {
        assertEquals("Baikonur Cosmodrome, Kazakhstan (TYMSC)", SatelliteFacts.launchSite("TYMSC"))
        assertEquals("China (PRC)", SatelliteFacts.owner("PRC"))
        assertEquals("XYZ", SatelliteFacts.owner("XYZ"))
        assertEquals("Rocket body", SatelliteFacts.objectType("R/B"))
        assertEquals("Operational", SatelliteFacts.status("+"))
        assertTrue(SatelliteFacts.sizeClass(399.0)!!.startsWith("Large"))
        assertTrue(SatelliteFacts.sizeClass(0.05)!!.startsWith("Small"))
    }
}

package com.jakedonnini.sattracker.data

import java.util.Locale

/** Human-readable labels for SATCAT codes, and Wikipedia fallbacks by constellation. Pure. */
object SatelliteFacts {

    /**
     * Wikipedia article about the constellation or class of object, for satellites that have no
     * article of their own (e.g. individual Starlinks).
     */
    fun fallbackArticle(name: String, objectType: String?): String? {
        val n = name.uppercase(Locale.ROOT)
        CONSTELLATIONS.firstOrNull { (prefix, _) -> n.startsWith(prefix) }?.let { return it.second }
        return when {
            objectType == "R/B" || n.contains("R/B") -> "Multistage rocket"
            objectType == "DEB" || n.contains(" DEB") -> "Space debris"
            else -> null
        }
    }

    /**
     * True for members of mega-constellations whose satellites have no individual articles, so
     * a Wikipedia search would only waste requests; they go straight to the group article.
     */
    fun skipsSpecificSearch(name: String): Boolean {
        val n = name.uppercase(Locale.ROOT)
        return MEGA_CONSTELLATIONS.any { n.startsWith(it) }
    }

    /**
     * Names worth a Wikipedia full-text search, most specific first: each given name plus its
     * parenthesised part split out ("ISS (ZARYA)" → "ISS (ZARYA)", "ISS", "ZARYA"), deduplicated,
     * skipping debris / rocket-body designations and names too short to be meaningful.
     */
    fun searchCandidates(names: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in names) {
            val name = raw.trim()
            if (name.isEmpty() || name.contains("DEB", ignoreCase = false) || name.contains("R/B")) continue
            out += name
            PAREN_REGEX.matchEntire(name)?.let { m ->
                out += m.groupValues[1].trim()
                out += m.groupValues[2].trim()
            }
        }
        return out.filter { normalize(it).length >= 3 }
    }

    /**
     * Whether a search result *title* can be about [name]: equal once punctuation and case are
     * dropped ("NOAA 19" ~ "NOAA-19"), or the title is the program and the name adds only a short
     * unit suffix ("SENTINEL-2A" ~ "Sentinel-2"). A parenthesised disambiguator is only allowed
     * when it's space-related ("Aqua (satellite)"), never e.g. "Checkmate (comics)".
     * Rejects loose hits like "GOES-19" for "NOAA 19". Always pair with [isSpacecraftArticle].
     */
    fun isRelevantTitle(name: String, title: String): Boolean = titleMatch(name, title) != TitleMatch.NONE

    /** How a title matches a satellite name: exactly, as its program/series, or not at all. */
    enum class TitleMatch { NONE, EXACT, PROGRAM }

    fun titleMatch(name: String, title: String): TitleMatch {
        val disambiguator = Regex("\\(([^)]*)\\)\\s*$").find(title)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        if (disambiguator != null && SPACE_DISAMBIGUATORS.none { disambiguator.contains(it) }) return TitleMatch.NONE
        val n = normalize(name)
        val t = normalize(title.substringBefore(" ("))
        if (n.length < 3 || t.length < 3) return TitleMatch.NONE
        return when {
            n == t -> TitleMatch.EXACT
            n.startsWith(t) && n.length - t.length <= 2 && t.length >= 4 -> TitleMatch.PROGRAM
            else -> TitleMatch.NONE
        }
    }

    /**
     * Cross-check against the authoritative catalog: a name can collide with a *different*
     * spacecraft (a LEO "JUNO" vs NASA's Jupiter probe). An article about this exact satellite
     * must mention its SATCAT launch year; a program/series article must mention it or no years
     * at all (series intros often have none). Without a launch year to check, nothing passes.
     */
    fun matchesLaunchYear(match: TitleMatch, description: String?, extract: String, launchYear: Int?): Boolean {
        if (launchYear == null || match == TitleMatch.NONE) return false
        val text = "${description.orEmpty()} $extract"
        val years = YEAR_REGEX.findAll(text).map { it.value.toInt() }.toSet()
        return launchYear in years || (match == TitleMatch.PROGRAM && years.isEmpty())
    }

    private val YEAR_REGEX = Regex("\\b(19[5-9]\\d|20[0-9]\\d)\\b")

    /**
     * Whether a Wikipedia article (its short description and intro) is about a spacecraft or a
     * space program. Correct data matters more than any data, so this is strict:
     *  - rejected if the description or first sentence says it's fiction, media, a person, etc.;
     *  - otherwise accepted only if the description or intro uses spacecraft vocabulary.
     */
    fun isSpacecraftArticle(description: String?, extract: String): Boolean {
        val desc = description.orEmpty().lowercase(Locale.ROOT)
        val firstSentence = extract.substringBefore(". ").lowercase(Locale.ROOT)
        if (NOT_SPACECRAFT.containsMatchIn(desc) || NOT_SPACECRAFT.containsMatchIn(firstSentence)) return false
        val text = "$desc ${extract.lowercase(Locale.ROOT)}"
        return SPACECRAFT_WORDS.any { text.contains(it) }
    }

    private fun normalize(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun objectType(code: String?): String? = when (code) {
        "PAY" -> "Payload (satellite)"
        "R/B" -> "Rocket body"
        "DEB" -> "Debris"
        "UNK" -> "Unknown"
        null, "" -> null
        else -> code
    }

    fun owner(code: String?): String? = when (code) {
        null, "" -> null
        else -> OWNERS[code]?.let { "$it ($code)" } ?: code
    }

    fun launchSite(code: String?): String? = when (code) {
        null, "" -> null
        else -> LAUNCH_SITES[code]?.let { "$it ($code)" } ?: code
    }

    fun status(code: String?): String? = when (code) {
        "+" -> "Operational"
        "-" -> "Non-operational"
        "P" -> "Partially operational"
        "B" -> "Backup"
        "S" -> "Spare"
        "X" -> "Extended mission"
        "D" -> "Decayed"
        "?" -> "Unknown"
        else -> null
    }

    /** Rough size from radar cross-section. */
    fun sizeClass(rcsM2: Double?): String? = when {
        rcsM2 == null -> null
        rcsM2 < 0.1 -> "Small (RCS %.2f m²)".format(rcsM2)
        rcsM2 < 1.0 -> "Medium (RCS %.2f m²)".format(rcsM2)
        else -> "Large (RCS %.1f m²)".format(rcsM2)
    }

    /** Disambiguators that can follow a spacecraft article's title. */
    private val SPACE_DISAMBIGUATORS = listOf(
        "satellite", "spacecraft", "space", "telescope", "observatory", "mission", "probe",
        "cubesat", "rocket", "launch", "constellation",
    )

    private val SPACECRAFT_WORDS = listOf(
        "satellite", "spacecraft", "space station", "space telescope", "space observatory",
        "cubesat", "nanosatellite", "microsatellite", "space probe", "space program", "space agency",
        "orbit", "launched", "launch vehicle", "upper stage", "rocket stage", "space debris",
    )

    /**
     * Words that mark an article as about something other than a spacecraft. Whole words only,
     * so "broadband satellite" or "defense contractor" don't trip "band" / "actor".
     */
    private val NOT_SPACECRAFT = Regex(
        "\\b(fictional|comics?|films?|novels?|albums?|songs?|bands?|video games?|television|tv series|" +
            "characters?|board games?|chess|footballers?|wrestlers?|racehorses?|musicians?|singers?|" +
            "actors?|actress(es)?|politicians?|restaurants?|brand of)\\b"
    )

    /** "NAME (ALIAS)" → name, alias. */
    private val PAREN_REGEX = Regex("^(.*?)\\s*\\((.+)\\)\\s*$")

    private val MEGA_CONSTELLATIONS = listOf(
        "STARLINK", "ONEWEB", "KUIPER", "QIANFAN", "FLOCK", "LEMUR", "IRIDIUM", "GLOBALSTAR", "ORBCOMM",
    )

    private val CONSTELLATIONS = listOf(
        "STARLINK" to "Starlink",
        "ONEWEB" to "OneWeb satellite constellation",
        "IRIDIUM" to "Iridium satellite constellation",
        "GLOBALSTAR" to "Globalstar",
        "ORBCOMM" to "Orbcomm",
        "FLOCK" to "Planet Labs",
        "LEMUR" to "Spire Global",
        "KUIPER" to "Project Kuiper",
        "QIANFAN" to "Qianfan",
        "YAOGAN" to "Yaogan",
        "COSMOS" to "Kosmos (satellite)",
    )

    private val OWNERS = mapOf(
        "US" to "United States",
        "PRC" to "China",
        "CIS" to "Russia / former USSR",
        "ISS" to "International Space Station partners",
        "ESA" to "European Space Agency",
        "JPN" to "Japan",
        "IND" to "India",
        "FR" to "France",
        "UK" to "United Kingdom",
        "GER" to "Germany",
        "IT" to "Italy",
        "CA" to "Canada",
        "SKOR" to "South Korea",
        "ISRA" to "Israel",
        "O3B" to "O3b Networks",
        "SES" to "SES",
        "GLOB" to "Globalstar",
        "ORB" to "Orbcomm",
        "IRID" to "Iridium",
        "EUTE" to "Eutelsat",
    )

    private val LAUNCH_SITES = mapOf(
        "AFETR" to "Cape Canaveral, USA",
        "AFWTR" to "Vandenberg, USA",
        "TYMSC" to "Baikonur Cosmodrome, Kazakhstan",
        "PLMSC" to "Plesetsk Cosmodrome, Russia",
        "VOSTO" to "Vostochny Cosmodrome, Russia",
        "KYMSC" to "Kapustin Yar, Russia",
        "JSC" to "Jiuquan, China",
        "TSC" to "Taiyuan, China",
        "XSC" to "Xichang, China",
        "WSC" to "Wenchang, China",
        "SRILR" to "Satish Dhawan, India",
        "TNSTA" to "Tanegashima, Japan",
        "KSCUT" to "Uchinoura, Japan",
        "FRGUI" to "Guiana Space Centre, French Guiana",
        "RLLB" to "Rocket Lab, Mahia, New Zealand",
        "WRAS" to "Wallops Island, USA",
        "KWAJ" to "Kwajalein, Marshall Islands",
    )
}

package com.jakedonnini.sattracker.orbit

import com.jakedonnini.sattracker.data.remote.OmmRecord
import org.orekit.propagation.analytical.tle.TLE
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import kotlin.math.PI

data class Satellite(
    val noradId: Int,
    val name: String,
    /** International designator, e.g. "1998-067A". */
    val objectId: String,
    val tle: TLE,
)

/**
 * Builds an Orekit [TLE] from OMM mean elements using the field-wise constructor.
 *
 * This deliberately avoids the TLE text format: Orekit only formats lines lazily, so catalog
 * numbers ≥ 100000 (issued since the 5-digit space ran out in 2026) still propagate fine.
 * Unit conversions mirror Orekit's own TLE line parser exactly.
 */
fun OmmRecord.toSatellite(): Satellite {
    val utc = TimeScalesFactory.getUTC()
    val (launchYear, launchNumber, launchPiece) = parseIntlDesignator(objectId)
    val tle = TLE(
        noradCatId,
        classificationType.firstOrNull() ?: 'U',
        launchYear,
        launchNumber,
        launchPiece,
        ephemerisType,
        elementSetNo,
        AbsoluteDate(epoch, utc),
        meanMotion * 2 * PI / SECONDS_PER_DAY,          // rev/day → rad/s
        meanMotionDot * PI / 1.86624e9,                 // ṅ/2 rev/day² → ṅ rad/s²
        meanMotionDdot * PI / 5.3747712e13,             // n̈/6 rev/day³ → n̈ rad/s³
        eccentricity,
        Math.toRadians(inclination),
        Math.toRadians(argOfPericenter),
        Math.toRadians(raOfAscNode),
        Math.toRadians(meanAnomaly),
        revAtEpoch,
        bstar,
        utc,
    )
    return Satellite(noradCatId, objectName.trim(), objectId, tle)
}

private const val SECONDS_PER_DAY = 86400.0

/** "1998-067A" → (1998, 67, "A"); anything unparseable → zeros (only used for display). */
private fun parseIntlDesignator(id: String): Triple<Int, Int, String> {
    val match = Regex("""^(\d{4})-(\d{3})([A-Z]*)$""").matchEntire(id.trim())
        ?: return Triple(0, 0, "")
    val (year, number, piece) = match.destructured
    return Triple(year.toInt(), number.toInt(), piece)
}

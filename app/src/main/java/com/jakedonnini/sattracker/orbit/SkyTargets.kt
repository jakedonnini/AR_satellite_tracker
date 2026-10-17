package com.jakedonnini.sattracker.orbit

import org.hipparchus.geometry.euclidean.threed.Vector3D
import org.orekit.bodies.CelestialBody
import org.orekit.bodies.CelestialBodyFactory
import org.orekit.bodies.GeodeticPoint
import org.orekit.bodies.OneAxisEllipsoid
import org.orekit.frames.FramesFactory
import org.orekit.frames.TopocentricFrame
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import org.orekit.utils.Constants
import org.orekit.utils.IERSConventions
import java.util.Date

enum class TargetKind { MOON, PLANET, STAR, SUN }

/** Something bright and precisely known that the user can centre to align the heading. */
data class AlignTarget(
    val name: String,
    val kind: TargetKind,
    val azimuthDeg: Double,
    val elevationDeg: Double,
    /** Typical visual magnitude (planets vary; used for ordering and display only). */
    val magnitude: Double,
)

/** A star from SIMBAD (ICRS / J2000 right ascension and declination, degrees; V magnitude). */
data class BrightStar(val name: String, val raDeg: Double, val decDeg: Double, val vMag: Double)

/**
 * Where the alignment targets are in the observer's sky (design doc §6.2a): the Moon, Sun and
 * planets from Orekit's DE-440 ephemeris (topocentric, so the Moon's ~1° parallax is included),
 * and the brightest stars converted from J2000 through Orekit's frames, which apply precession
 * and nutation (about 0.35° since J2000). Aberration (≤ 0.006°) and refraction (≤ 0.1° above
 * 10° elevation, and it only shifts elevation) are ignored.
 */
class SkyTargets {
    private val utc = TimeScalesFactory.getUTC()
    private val eme2000 = FramesFactory.getEME2000()
    private val earth = OneAxisEllipsoid(
        Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
        Constants.WGS84_EARTH_FLATTENING,
        FramesFactory.getITRF(IERSConventions.IERS_2010, true),
    )

    private val bodies: List<Triple<String, TargetKind, Pair<CelestialBody, Double>>> = listOf(
        Triple("Moon", TargetKind.MOON, CelestialBodyFactory.getMoon() to -12.0),
        Triple("Venus", TargetKind.PLANET, CelestialBodyFactory.getVenus() to -4.2),
        Triple("Jupiter", TargetKind.PLANET, CelestialBodyFactory.getJupiter() to -2.4),
        Triple("Mars", TargetKind.PLANET, CelestialBodyFactory.getMars() to 0.5),
        Triple("Saturn", TargetKind.PLANET, CelestialBodyFactory.getSaturn() to 0.7),
        Triple("Mercury", TargetKind.PLANET, CelestialBodyFactory.getMercury() to 0.0),
        Triple("Sun", TargetKind.SUN, CelestialBodyFactory.getSun() to -26.7),
    )

    /**
     * Targets between [minElevationDeg] and [maxElevationDeg], Moon and planets first, then stars by
     * brightness, Sun last. Near the zenith azimuth is ill-defined (a small pointing error is a big
     * azimuth error), so high targets are left out by default.
     */
    fun visible(
        observer: Observer,
        epochMillis: Long,
        minElevationDeg: Double = 10.0,
        maxElevationDeg: Double = 75.0,
    ): List<AlignTarget> {
        val date = AbsoluteDate(Date(epochMillis), utc)
        val topo = topocentric(observer)
        val all = bodies.map { (name, kind, body) -> target(name, kind, body.second, body.first.getPosition(date, eme2000), topo, date) } +
            STARS.map { s -> target(s.name, TargetKind.STAR, s.vMag, starDirection(s), topo, date) }
        val order = listOf(TargetKind.MOON, TargetKind.PLANET, TargetKind.STAR, TargetKind.SUN)
        return all.filter { it.elevationDeg in minElevationDeg..maxElevationDeg }
            .sortedWith(compareBy<AlignTarget> { order.indexOf(it.kind) }.thenBy { it.magnitude })
    }

    /** Position of one named target right now (for the moment the user taps Set). */
    fun position(name: String, observer: Observer, epochMillis: Long): AlignTarget? {
        val date = AbsoluteDate(Date(epochMillis), utc)
        val topo = topocentric(observer)
        bodies.firstOrNull { it.first == name }?.let { (_, kind, body) ->
            return target(name, kind, body.second, body.first.getPosition(date, eme2000), topo, date)
        }
        return STARS.firstOrNull { it.name == name }?.let { target(it.name, TargetKind.STAR, it.vMag, starDirection(it), topo, date) }
    }

    private fun target(name: String, kind: TargetKind, mag: Double, posEme2000: Vector3D, topo: TopocentricFrame, date: AbsoluteDate) =
        AlignTarget(
            name = name,
            kind = kind,
            azimuthDeg = (Math.toDegrees(topo.getAzimuth(posEme2000, eme2000, date)) + 360.0) % 360.0,
            elevationDeg = Math.toDegrees(topo.getElevation(posEme2000, eme2000, date)),
            magnitude = mag,
        )

    /** A star's J2000 direction as a very distant point (parallax negligible). */
    private fun starDirection(s: BrightStar): Vector3D {
        val ra = Math.toRadians(s.raDeg)
        val dec = Math.toRadians(s.decDeg)
        return Vector3D(Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)).scalarMultiply(STAR_DISTANCE_M)
    }

    private fun topocentric(observer: Observer) = TopocentricFrame(
        earth,
        GeodeticPoint(Math.toRadians(observer.latDeg), Math.toRadians(observer.lonDeg), observer.altMeters),
        "observer",
    )

    companion object {
        private const val STAR_DISTANCE_M = 1e18

        /**
         * Stars brighter than V = 1.9 plus Polaris, positions from SIMBAD (ICRS, epoch J2000),
         * fetched 2026-10-17. Proper motion since J2000 is under 0.02° for all of them.
         */
        val STARS = listOf(
        BrightStar("Sirius", 101.28716, -16.71612, -1.46),  // alf CMa
        BrightStar("Canopus", 95.98796, -52.69566, -0.74),  // alf Car
        BrightStar("Arcturus", 213.91530, 19.18241, -0.05),  // alf Boo
        BrightStar("Alpha Centauri", 219.90206, -60.83399, 0.01),  // alf Cen A
        BrightStar("Vega", 279.23473, 38.78369, 0.03),  // alf Lyr
        BrightStar("Capella", 79.17233, 45.99799, 0.08),  // alf Aur
        BrightStar("Rigel", 78.63447, -8.20164, 0.13),  // bet Ori
        BrightStar("Procyon", 114.82550, 5.22499, 0.37),  // alf CMi
        BrightStar("Betelgeuse", 88.79294, 7.40706, 0.42),  // alf Ori
        BrightStar("Achernar", 24.42852, -57.23675, 0.46),  // alf Eri
        BrightStar("Hadar", 210.95586, -60.37304, 0.58),  // bet Cen
        BrightStar("Altair", 297.69583, 8.86832, 0.76),  // alf Aql
        BrightStar("Aldebaran", 68.98016, 16.50930, 0.86),  // alf Tau
        BrightStar("Antares", 247.35192, -26.43200, 0.91),  // alf Sco
        BrightStar("Spica", 201.29825, -11.16132, 0.97),  // alf Vir
        BrightStar("Pollux", 116.32896, 28.02620, 1.14),  // bet Gem
        BrightStar("Fomalhaut", 344.41269, -29.62224, 1.16),  // alf PsA
        BrightStar("Deneb", 310.35798, 45.28034, 1.25),  // alf Cyg
        BrightStar("Mimosa", 191.93029, -59.68877, 1.25),  // bet Cru
        BrightStar("Regulus", 152.09296, 11.96721, 1.40),  // alf Leo
        BrightStar("Adhara", 104.65645, -28.97209, 1.50),  // eps CMa
        BrightStar("Castor", 113.64947, 31.88828, 1.58),  // alf Gem
        BrightStar("Shaula", 263.40217, -37.10382, 1.63),  // lam Sco
        BrightStar("Bellatrix", 81.28276, 6.34970, 1.64),  // gam Ori
        BrightStar("Gacrux", 187.79150, -57.11321, 1.64),  // gam Cru
        BrightStar("Elnath", 81.57297, 28.60745, 1.65),  // bet Tau
        BrightStar("Alnilam", 84.05339, -1.20192, 1.69),  // eps Ori
        BrightStar("Miaplacidus", 138.29991, -69.71721, 1.69),  // bet Car
        BrightStar("Alnair", 332.05827, -46.96097, 1.71),  // alf Gru
        BrightStar("Alioth", 193.50729, 55.95982, 1.77),  // eps UMa
        BrightStar("Alnitak", 85.18969, -1.94257, 1.77),  // zet Ori
        BrightStar("Mirfak", 51.08071, 49.86118, 1.79),  // alf Per
        BrightStar("Dubhe", 165.93196, 61.75103, 1.79),  // alf UMa
        BrightStar("Kaus Australis", 276.04299, -34.38462, 1.81),  // eps Sgr
        BrightStar("Regor", 122.38313, -47.33659, 1.83),  // gam02 Vel
        BrightStar("Wezen", 107.09785, -26.39320, 1.84),  // del CMa
        BrightStar("Sargas", 264.32971, -42.99783, 1.85),  // tet Sco
        BrightStar("Alkaid", 206.88516, 49.31327, 1.86),  // eta UMa
        BrightStar("Avior", 125.62848, -59.50948, 1.86),  // eps Car
        BrightStar("Atria", 252.16623, -69.02771, 1.88),  // alf TrA
        BrightStar("Menkalinan", 89.88218, 44.94743, 1.90),  // bet Aur
        BrightStar("Polaris", 37.95456, 89.26411, 2.02),  // alf UMi
        )
    }
}

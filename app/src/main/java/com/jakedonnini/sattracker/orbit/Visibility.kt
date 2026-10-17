package com.jakedonnini.sattracker.orbit

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Sky brightness at the observer, from the Sun's elevation. */
enum class SkyCondition(val label: String) {
    DAYLIGHT("daylight"),
    CIVIL_TWILIGHT("civil twilight"),
    NAUTICAL_TWILIGHT("nautical twilight"),
    ASTRONOMICAL_TWILIGHT("astronomical twilight"),
    NIGHT("night"),
}

/** Why a satellite is or isn't a naked-eye candidate for the observer right now. */
enum class NakedEye {
    LIKELY_VISIBLE,
    TOO_FAINT,
    IN_SHADOW,
    SKY_TOO_BRIGHT,
    TOO_LOW,
}

data class VisibilityEstimate(
    val status: NakedEye,
    /** Estimated apparent magnitude; null when the satellite is in Earth's shadow. */
    val magnitude: Double?,
    val sunlit: Boolean,
    val observerSunElevationDeg: Double,
)

/**
 * Naked-eye visibility rules (design doc §12): the satellite is sunlit, the observer's Sun is
 * at least 6° below the horizon, the satellite is at least 10° up, and its estimated magnitude
 * is under the naked-eye limit. Pure math on plain doubles so it can be unit-tested.
 */
object Visibility {
    const val EARTH_RADIUS_KM = 6371.0
    const val DARK_SKY_SUN_ELEVATION_DEG = -6.0
    const val MIN_ELEVATION_DEG = 10.0
    const val NAKED_EYE_LIMIT_MAG = 5.0

    fun skyCondition(sunElevationDeg: Double): SkyCondition = when {
        sunElevationDeg > 0 -> SkyCondition.DAYLIGHT
        sunElevationDeg > -6 -> SkyCondition.CIVIL_TWILIGHT
        sunElevationDeg > -12 -> SkyCondition.NAUTICAL_TWILIGHT
        sunElevationDeg > -18 -> SkyCondition.ASTRONOMICAL_TWILIGHT
        else -> SkyCondition.NIGHT
    }

    /**
     * Cylindrical Earth-shadow test. [sat] is the satellite position (km) and [sunDir] a unit
     * vector toward the Sun, both Earth-centered in the same frame. The penumbra is ignored;
     * for LEO it's only a few seconds of each eclipse.
     */
    fun isSunlit(sat: DoubleArray, sunDir: DoubleArray): Boolean {
        val along = dot(sat, sunDir)
        if (along >= 0) return true // on the day side of the terminator plane
        val perp2 = dot(sat, sat) - along * along
        return perp2 > EARTH_RADIUS_KM * EARTH_RADIUS_KM
    }

    /** Sun–satellite–observer angle in radians, from the satellite's two unit sightlines. */
    fun phaseAngle(satToSun: DoubleArray, satToObserver: DoubleArray): Double =
        acos((dot(satToSun, satToObserver) / sqrt(dot(satToSun, satToSun) * dot(satToObserver, satToObserver)))
            .coerceIn(-1.0, 1.0))

    /**
     * Apparent magnitude from the 1000-km magnitude, range and phase angle, using the diffuse
     * sphere phase law normalised to 0 at 90°: m = M + 5·log10(d/1000) − 2.5·log10(sin φ + (π−φ)cos φ).
     */
    fun apparentMagnitude(m1000: Double, rangeKm: Double, phaseRad: Double): Double {
        val phase = (sin(phaseRad) + (PI - phaseRad) * cos(phaseRad)).coerceAtLeast(1e-6)
        return m1000 + 5 * log10(rangeKm / 1000.0) - 2.5 * log10(phase)
    }

    /**
     * Rough 1000-km magnitude by satellite name; the catalog carries no brightness data.
     * Values from published averages where known (see design doc §12.2).
     */
    fun intrinsicMagnitude(name: String, noradId: Int): Double {
        val n = name.uppercase()
        return when {
            noradId == 25544 || n.startsWith("ISS") -> -1.3
            n.startsWith("CSS") || n.contains("TIANHE") -> 0.0
            n == "HST" -> 2.2
            n.contains(" DEB") || n.endsWith("DEB") -> 8.0
            n.contains("R/B") -> 4.0
            n.startsWith("STARLINK") -> 5.5
            n.startsWith("ONEWEB") -> 7.5
            else -> 6.0
        }
    }

    /**
     * Full classification for one satellite as seen by the observer.
     * Vectors are Earth-centered (km), any common inertial or Earth-fixed frame.
     */
    fun estimate(
        /** 1000-km magnitude, e.g. from [intrinsicMagnitude]. */
        m1000: Double,
        satPos: DoubleArray,
        observerPos: DoubleArray,
        sunDir: DoubleArray,
        elevationDeg: Double,
        observerSunElevationDeg: Double,
    ): VisibilityEstimate {
        val sunlit = isSunlit(satPos, sunDir)
        val toObserver = DoubleArray(3) { observerPos[it] - satPos[it] }
        val rangeKm = sqrt(dot(toObserver, toObserver))
        val magnitude = if (sunlit) {
            apparentMagnitude(
                m1000,
                rangeKm,
                phaseAngle(sunDir, toObserver), // Sun is effectively at infinity
            )
        } else null
        val status = when {
            !sunlit -> NakedEye.IN_SHADOW
            observerSunElevationDeg > DARK_SKY_SUN_ELEVATION_DEG -> NakedEye.SKY_TOO_BRIGHT
            elevationDeg < MIN_ELEVATION_DEG -> NakedEye.TOO_LOW
            magnitude!! > NAKED_EYE_LIMIT_MAG -> NakedEye.TOO_FAINT
            else -> NakedEye.LIKELY_VISIBLE
        }
        return VisibilityEstimate(status, magnitude, sunlit, observerSunElevationDeg)
    }

    /**
     * 0..1 "how easy to see" for a likely-visible satellite, for brightness-graded markers:
     * its estimated magnitude (at the naked-eye limit → 0, three magnitudes brighter → 1),
     * weighted by how dark the sky is (Sun at −6° → half weight, −18° or lower → full).
     * Same idea as the globe's gradient (design doc §11.1a). Pure and allocation-free.
     */
    fun visibilityScore(magnitude: Double, sunElevationDeg: Double): Double {
        val brightness = ((NAKED_EYE_LIMIT_MAG - magnitude) / 3.0).coerceIn(0.0, 1.0)
        val darkness = ((-sunElevationDeg - 6.0) / 12.0).coerceIn(0.0, 1.0)
        return brightness * (0.5 + 0.5 * darkness)
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

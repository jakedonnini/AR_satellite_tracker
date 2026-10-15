package com.jakedonnini.sattracker.orbit

import android.util.Log
import org.orekit.bodies.GeodeticPoint
import org.orekit.bodies.OneAxisEllipsoid
import org.orekit.frames.FramesFactory
import org.orekit.frames.TopocentricFrame
import org.orekit.propagation.analytical.tle.TLEPropagator
import org.orekit.time.AbsoluteDate
import org.orekit.utils.Constants
import org.orekit.utils.IERSConventions
import kotlin.math.asin
import kotlin.math.atan2

/** Observer position; altitude is height above the WGS-84 ellipsoid (what Android reports). */
data class Observer(val latDeg: Double, val lonDeg: Double, val altMeters: Double)

/** A satellite's direction from the observer. Azimuth is from true north, clockwise. */
data class SkyPosition(
    val satellite: Satellite,
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val rangeMeters: Double,
)

/**
 * Propagates a catalog with SGP4 and reports where each satellite is in the observer's sky.
 *
 * Propagators are built once per catalog. Per call, the TEME→topocentric transform is
 * computed once and shared by every satellite, instead of once per satellite.
 */
class SkyModel(satellites: List<Satellite>) {
    private val teme = FramesFactory.getTEME()
    private val earth = OneAxisEllipsoid(
        Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
        Constants.WGS84_EARTH_FLATTENING,
        FramesFactory.getITRF(IERSConventions.IERS_2010, true),
    )

    private val tracked: List<Pair<Satellite, TLEPropagator>> = satellites.mapNotNull { sat ->
        try {
            sat to TLEPropagator.selectExtrapolator(sat.tle)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot build propagator for ${sat.noradId}", e)
            null
        }
    }

    val size: Int get() = tracked.size

    fun positions(observer: Observer, date: AbsoluteDate, minElevationDeg: Double): List<SkyPosition> {
        val topo = TopocentricFrame(
            earth,
            GeodeticPoint(Math.toRadians(observer.latDeg), Math.toRadians(observer.lonDeg), observer.altMeters),
            "observer",
        )
        val temeToTopo = teme.getStaticTransformTo(topo, date)

        return tracked.mapNotNull { (sat, propagator) ->
            try {
                // Topocentric axes: X = east, Y = north, Z = zenith.
                val p = temeToTopo.transformPosition(propagator.getPVCoordinates(date, teme).position)
                val range = p.norm
                val elevation = Math.toDegrees(asin(p.z / range))
                if (elevation < minElevationDeg) return@mapNotNull null
                val azimuth = (Math.toDegrees(atan2(p.x, p.y)) + 360.0) % 360.0
                SkyPosition(sat, azimuth, elevation, range)
            } catch (e: Exception) {
                // SGP4 throws for decayed / badly stale element sets.
                null
            }
        }
    }

    private companion object {
        const val TAG = "SkyModel"
    }
}

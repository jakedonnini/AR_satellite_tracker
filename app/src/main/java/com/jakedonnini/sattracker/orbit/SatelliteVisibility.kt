package com.jakedonnini.sattracker.orbit

import android.util.Log
import org.orekit.bodies.GeodeticPoint
import org.orekit.bodies.OneAxisEllipsoid
import org.orekit.frames.FramesFactory
import org.orekit.frames.TopocentricFrame
import org.orekit.propagation.analytical.tle.TLE
import org.orekit.propagation.analytical.tle.TLEPropagator
import org.orekit.time.AbsoluteDate
import org.orekit.utils.Constants
import org.orekit.utils.IERSConventions

data class VisibleSatellite(val tle: TLE, val elevationDeg: Double)

/** Propagates TLEs with SGP4 and filters to satellites above an elevation mask. */
object SatelliteVisibility {
    private const val TAG = "SatelliteVisibility"

    fun aboveHorizon(
        tles: List<TLE>,
        latDeg: Double,
        lonDeg: Double,
        altMeters: Double,
        date: AbsoluteDate,
        minElevationDeg: Double = 10.0,
    ): List<VisibleSatellite> {
        val earth = OneAxisEllipsoid(
            Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
            Constants.WGS84_EARTH_FLATTENING,
            FramesFactory.getITRF(IERSConventions.IERS_2010, true)
        )
        val observer = GeodeticPoint(Math.toRadians(latDeg), Math.toRadians(lonDeg), altMeters)
        val topo = TopocentricFrame(earth, observer, "Observer")
        val teme = FramesFactory.getTEME()

        return tles.mapNotNull { tle ->
            try {
                val pv = TLEPropagator.selectExtrapolator(tle).getPVCoordinates(date, teme)
                val elevation = Math.toDegrees(topo.getElevation(pv.position, teme, date))
                Log.v(TAG, "Sat ${tle.satelliteNumber} at $elevation°")
                if (elevation > minElevationDeg) VisibleSatellite(tle, elevation) else null
            } catch (e: Exception) {
                Log.w(TAG, "Could not propagate satellite ${tle.satelliteNumber}", e)
                null
            }
        }
    }
}

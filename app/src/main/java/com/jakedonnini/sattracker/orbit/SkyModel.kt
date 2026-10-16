package com.jakedonnini.sattracker.orbit

import android.util.Log
import com.jakedonnini.sattracker.geometry.Enu
import org.orekit.bodies.GeodeticPoint
import org.orekit.bodies.OneAxisEllipsoid
import org.orekit.frames.FramesFactory
import org.orekit.frames.TopocentricFrame
import org.orekit.propagation.analytical.tle.TLEPropagator
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import org.orekit.utils.Constants
import org.orekit.utils.IERSConventions
import java.util.Date
import kotlin.math.asin
import kotlin.math.atan2

/** Observer position; altitude is height above the WGS-84 ellipsoid (what Android reports). */
data class Observer(val latDeg: Double, val lonDeg: Double, val altMeters: Double)

/**
 * A satellite's position and velocity in the observer's topocentric frame
 * (East, North, Up; metres and m/s) at the snapshot epoch.
 */
class SkyObject(val satellite: Satellite, val position: Enu, val velocity: Enu) {
    /** Linear extrapolation; over a few seconds the error is metres at ~1000 km range. */
    fun positionAfter(seconds: Double) = Enu(
        position.e + velocity.e * seconds,
        position.n + velocity.n * seconds,
        position.u + velocity.u * seconds,
    )
}

/** ECEF state of every satellite at [epochMillis]; see [SkyModel.globeSnapshot]. */
class GlobeSnapshot(val epochMillis: Long, val data: FloatArray)

/** Every satellite near or above the horizon at [epochMillis]. */
class SkySnapshot(val epochMillis: Long, val objects: List<SkyObject>)

/** One point on a satellite's sky track; [epochMillis] is absolute time. */
data class TrackPoint(val epochMillis: Long, val direction: Enu)

data class SatelliteDetails(
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val rangeKm: Double,
    val altitudeKm: Double,
    /** Inertial orbital speed. */
    val speedKmS: Double,
    /** True while elevation is increasing. */
    val rising: Boolean,
)

/**
 * Propagates a catalog with SGP4 and reports where each satellite is in the observer's sky.
 *
 * [snapshot] is the bulk path: propagators are built once per catalog, and the
 * TEME→topocentric transform is computed once per call and shared by every satellite.
 * It must only be called from one thread at a time (propagators are stateful).
 * [track] and [details] build their own propagator, so they're safe to call concurrently.
 */
class SkyModel(
    satellites: List<Satellite>,
    /** 0..1 while propagators are built (the slow part of construction). */
    onProgress: (Float) -> Unit = {},
) {
    private val utc = TimeScalesFactory.getUTC()
    private val teme = FramesFactory.getTEME()
    private val earth = OneAxisEllipsoid(
        Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
        Constants.WGS84_EARTH_FLATTENING,
        FramesFactory.getITRF(IERSConventions.IERS_2010, true),
    )

    private val tracked: List<Pair<Satellite, TLEPropagator>> = satellites.mapIndexedNotNull { i, sat ->
        if (i % 500 == 0) onProgress(i.toFloat() / satellites.size)
        try {
            sat to TLEPropagator.selectExtrapolator(sat.tle)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot build propagator for ${sat.noradId}", e)
            null
        }
    }.also { onProgress(1f) }

    val size: Int get() = tracked.size

    /** Every satellite with a working propagator, in the order [globeSnapshot] reports them. */
    val satellites: List<Satellite> = tracked.map { it.first }

    /**
     * Earth-fixed (ITRF) position and velocity of **every** satellite, for the globe view.
     * Data is [x, y, z, vx, vy, vz] per satellite in km and km/s, in [satellites] order;
     * satellites SGP4 can't propagate (decayed / stale elements) are NaN.
     * Same threading rule as [snapshot].
     */
    fun globeSnapshot(epochMillis: Long): GlobeSnapshot {
        val date = dateOf(epochMillis)
        val temeToItrf = teme.getTransformTo(earth.bodyFrame, date)
        val data = FloatArray(tracked.size * 6) { Float.NaN }
        tracked.forEachIndexed { i, (_, propagator) ->
            try {
                val pv = temeToItrf.transformPVCoordinates(propagator.getPVCoordinates(date, teme))
                val o = i * 6
                data[o] = (pv.position.x / 1000).toFloat()
                data[o + 1] = (pv.position.y / 1000).toFloat()
                data[o + 2] = (pv.position.z / 1000).toFloat()
                data[o + 3] = (pv.velocity.x / 1000).toFloat()
                data[o + 4] = (pv.velocity.y / 1000).toFloat()
                data[o + 5] = (pv.velocity.z / 1000).toFloat()
            } catch (e: Exception) {
                // Leave NaN: the globe skips it.
            }
        }
        return GlobeSnapshot(epochMillis, data)
    }

    /**
     * One orbital period of [satellite]'s path in Earth-fixed coordinates (km, flattened xyz),
     * centered on [centerMillis]. Because the Earth turns underneath, this is the path relative
     * to the ground, which is what the globe shows.
     */
    fun orbitPathEcef(satellite: Satellite, centerMillis: Long, stepSeconds: Int = 60): FloatArray {
        val propagator = TLEPropagator.selectExtrapolator(satellite.tle)
        val periodSeconds = 2 * Math.PI / satellite.tle.meanMotion
        // Step outward from now so the current position is exactly the middle vertex.
        val stepsEachSide = kotlin.math.ceil(periodSeconds / 2 / stepSeconds).toInt()
        val times = (-stepsEachSide..stepsEachSide).map { centerMillis + it * stepSeconds * 1000L }
        val out = FloatArray(times.size * 3)
        times.forEachIndexed { i, t ->
            val date = dateOf(t)
            val p = teme.getStaticTransformTo(earth.bodyFrame, date)
                .transformPosition(propagator.getPVCoordinates(date, teme).position)
            out[i * 3] = (p.x / 1000).toFloat()
            out[i * 3 + 1] = (p.y / 1000).toFloat()
            out[i * 3 + 2] = (p.z / 1000).toFloat()
        }
        return out
    }

    /**
     * @param onlyIds if non-null, propagate just these satellites. Callers use a cheap
     *   frequent pass over a candidate set found by an occasional full pass with a lower mask.
     */
    fun snapshot(
        observer: Observer,
        epochMillis: Long,
        minElevationDeg: Double,
        onlyIds: Set<Int>? = null,
    ): SkySnapshot {
        val date = dateOf(epochMillis)
        val temeToTopo = teme.getTransformTo(topocentric(observer), date)
        val minSinEl = kotlin.math.sin(Math.toRadians(minElevationDeg))
        val source = if (onlyIds == null) tracked else tracked.filter { it.first.noradId in onlyIds }

        val objects = source.mapNotNull { (sat, propagator) ->
            try {
                // Topocentric axes: X = east, Y = north, Z = zenith.
                val pv = temeToTopo.transformPVCoordinates(propagator.getPVCoordinates(date, teme))
                val p = pv.position
                if (p.z / p.norm < minSinEl) return@mapNotNull null
                val v = pv.velocity
                SkyObject(sat, Enu(p.x, p.y, p.z), Enu(v.x, v.y, v.z))
            } catch (e: Exception) {
                // SGP4 throws for decayed / badly stale element sets.
                null
            }
        }
        return SkySnapshot(epochMillis, objects)
    }

    /** Sky track (unit directions) from [startMillis] to [endMillis] at [stepSeconds]. */
    fun track(
        satellite: Satellite,
        observer: Observer,
        startMillis: Long,
        endMillis: Long,
        stepSeconds: Int,
    ): List<TrackPoint> {
        val propagator = TLEPropagator.selectExtrapolator(satellite.tle)
        val topo = topocentric(observer)
        return (startMillis..endMillis step stepSeconds * 1000L).map { t ->
            val date = dateOf(t)
            val p = teme.getStaticTransformTo(topo, date)
                .transformPosition(propagator.getPVCoordinates(date, teme).position)
            TrackPoint(t, Enu(p.x, p.y, p.z).unit())
        }
    }

    fun details(satellite: Satellite, observer: Observer, epochMillis: Long): SatelliteDetails {
        val date = dateOf(epochMillis)
        val propagator = TLEPropagator.selectExtrapolator(satellite.tle)
        val pvTeme = propagator.getPVCoordinates(date, teme)
        val pv = teme.getTransformTo(topocentric(observer), date).transformPVCoordinates(pvTeme)
        val p = pv.position
        val range = p.norm
        val subPoint = earth.transform(pvTeme.position, teme, date)
        // d(sin el)/dt sign: radial-up component of velocity relative to the line of sight.
        val sinElRate = (pv.velocity.z * range - p.z * (p.dotProduct(pv.velocity) / range)) / (range * range)
        return SatelliteDetails(
            azimuthDeg = (Math.toDegrees(atan2(p.x, p.y)) + 360.0) % 360.0,
            elevationDeg = Math.toDegrees(asin(p.z / range)),
            rangeKm = range / 1000,
            altitudeKm = subPoint.altitude / 1000,
            speedKmS = pvTeme.velocity.norm / 1000,
            rising = sinElRate > 0,
        )
    }

    private fun dateOf(epochMillis: Long) = AbsoluteDate(Date(epochMillis), utc)

    private fun topocentric(observer: Observer) = TopocentricFrame(
        earth,
        GeodeticPoint(Math.toRadians(observer.latDeg), Math.toRadians(observer.lonDeg), observer.altMeters),
        "observer",
    )

    private companion object {
        const val TAG = "SkyModel"
    }
}

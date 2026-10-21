package com.jakedonnini.sattracker.air

import com.jakedonnini.sattracker.data.remote.AircraftDetails
import com.jakedonnini.sattracker.data.remote.Airline
import com.jakedonnini.sattracker.data.remote.FlightRoute
import com.jakedonnini.sattracker.geometry.Geodesy
import com.jakedonnini.sattracker.orbit.Observer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** One aircraft as reported over ADS-B (via OpenSky), filtered to airborne with a fresh position. */
data class Aircraft(
    /** ICAO 24-bit transponder address, lowercase hex. */
    val icao24: String,
    /** Flight number / callsign, or the ICAO address when the aircraft didn't send one. */
    val callsign: String,
    val originCountry: String,
    val latDeg: Double,
    val lonDeg: Double,
    val altitudeM: Double,
    /** True for GNSS (geometric) altitude; false when only barometric altitude was available. */
    val altitudeIsGeometric: Boolean,
    val speedMs: Double,
    val trackDeg: Double,
    val verticalRateMs: Double,
    /** Unix time of the position report, seconds. */
    val positionTimeSec: Long,
)

/**
 * An aircraft in the observer's sky: position (km, local East-North-Up from the observer) at
 * [epochMillis] and velocity (km/s), so it can be dead-reckoned between polls just like
 * satellites. Fields are flat doubles so the overlay's per-frame loop allocates nothing.
 */
class SkyAircraft(
    val aircraft: Aircraft,
    val e: Double,
    val n: Double,
    val u: Double,
    val ve: Double,
    val vn: Double,
    val vu: Double,
    val epochMillis: Long,
    /** Route that passed [RouteCheck] against this aircraft's position; null if unknown or rejected. */
    val route: FlightRoute? = null,
    /** True when the database had a route but it doesn't fit where the aircraft is. */
    val routeRejected: Boolean = false,
    /** From the callsign's ICAO prefix, so reliable even when the route isn't. */
    val airline: Airline? = null,
    val details: AircraftDetails? = null,
) {
    /** Built once here so drawing doesn't format strings every frame. */
    val label: String = buildString {
        append(aircraft.callsign)
        route?.let { append(" → ").append(it.destination.code) }
        append("  %.1f km".format(aircraft.altitudeM / 1000))
    }
    val groundDistanceKm: Double get() = hypot(e, n)
    val rangeKm: Double get() = sqrt(e * e + n * n + u * u)
    val elevationDeg: Double get() = Math.toDegrees(atan2(u, hypot(e, n)))
}

/** The latest nearby-aircraft poll. */
class AirTraffic(
    val fetchedAtMillis: Long,
    val aircraft: List<SkyAircraft>,
    /** OpenSky credits left today, when the server said. */
    val creditsRemaining: Int?,
)

object AirGeometry {
    /**
     * Places [a] in [observer]'s East-North-Up frame. Velocity uses the observer's axes too;
     * within the ~50 km radius those differ from the aircraft's by under half a degree.
     */
    fun toSky(a: Aircraft, observer: Observer, info: FlightInfoRepository? = null): SkyAircraft {
        val p = Geodesy.geodeticToEcefKm(a.latDeg, a.lonDeg, a.altitudeM)
        val o = Geodesy.geodeticToEcefKm(observer.latDeg, observer.lonDeg, observer.altMeters)
        val enu = Geodesy.ecefToEnu(p.x - o.x, p.y - o.y, p.z - o.z, observer.latDeg, observer.lonDeg)
        val track = Math.toRadians(a.trackDeg)
        val dbRoute = info?.route(a.callsign)
        val routeOk = dbRoute != null &&
            RouteCheck.plausible(dbRoute, a.latDeg, a.lonDeg, a.trackDeg.takeIf { a.speedMs > MIN_TRACK_SPEED_MS })
        return SkyAircraft(
            aircraft = a,
            e = enu.e,
            n = enu.n,
            u = enu.u,
            ve = a.speedMs * sin(track) / 1000,
            vn = a.speedMs * cos(track) / 1000,
            vu = a.verticalRateMs / 1000,
            epochMillis = a.positionTimeSec * 1000,
            route = dbRoute?.takeIf { routeOk },
            routeRejected = dbRoute != null && !routeOk,
            airline = dbRoute?.airline,
            details = info?.details(a.icao24),
        )
    }

    /** Below this ground speed the reported track isn't meaningful. */
    private const val MIN_TRACK_SPEED_MS = 30.0
}

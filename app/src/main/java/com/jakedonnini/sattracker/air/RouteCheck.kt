package com.jakedonnini.sattracker.air

import com.jakedonnini.sattracker.data.remote.FlightRoute
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Route databases match on callsign only, so the route can be stale or for another flight with
 * the same number (adsbdb listed DAL2412 as LAX→SFO while it was over Philadelphia). A route is
 * shown only if the aircraft is plausibly on it: near the great circle between the airports,
 * between them, and (away from the airports) heading roughly toward the destination.
 */
object RouteCheck {
    private const val EARTH_KM = 6371.0
    /** Within this of either airport, departures and approaches can point anywhere. */
    const val AIRPORT_ZONE_KM = 150.0
    const val MIN_CROSS_TRACK_KM = 100.0
    /** Airways wander off the great circle by a fraction of the route length. */
    const val CROSS_TRACK_FRACTION = 0.15
    const val MAX_HEADING_OFF_DEG = 90.0

    /** [trackDeg] null (unknown) skips the heading test. */
    fun plausible(route: FlightRoute, latDeg: Double, lonDeg: Double, trackDeg: Double?): Boolean {
        val o = route.origin
        val d = route.destination
        val routeKm = distanceKm(o.latitude, o.longitude, d.latitude, d.longitude)
        val fromOrigin = distanceKm(o.latitude, o.longitude, latDeg, lonDeg)
        val toDest = distanceKm(latDeg, lonDeg, d.latitude, d.longitude)
        if (fromOrigin <= AIRPORT_ZONE_KM || toDest <= AIRPORT_ZONE_KM) return true

        val d13 = fromOrigin / EARTH_KM
        val crossRad = asin(sin(d13) * sin(Math.toRadians(
            bearingDeg(o.latitude, o.longitude, latDeg, lonDeg) - bearingDeg(o.latitude, o.longitude, d.latitude, d.longitude)
        )))
        val crossKm = abs(crossRad) * EARTH_KM
        if (crossKm > max(MIN_CROSS_TRACK_KM, CROSS_TRACK_FRACTION * routeKm)) return false
        // Along-track distance from the origin; must lie between the airports.
        val alongKm = acos((cos(d13) / cos(crossRad)).coerceIn(-1.0, 1.0)) * EARTH_KM
        if (alongKm > routeKm + AIRPORT_ZONE_KM || fromOrigin > routeKm + AIRPORT_ZONE_KM) return false

        if (trackDeg == null) return true
        val toward = bearingDeg(latDeg, lonDeg, d.latitude, d.longitude)
        val off = abs(((trackDeg - toward + 540) % 360) - 180)
        return off <= MAX_HEADING_OFF_DEG
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_KM * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial great-circle bearing, degrees clockwise from north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}

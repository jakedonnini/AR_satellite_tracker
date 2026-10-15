package com.jakedonnini.sattracker.sensors

import android.Manifest
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Streams high-accuracy fixes from the fused location provider.
 *
 * [Location.getAltitude] is height above the WGS-84 ellipsoid, which is what Orekit's
 * GeodeticPoint expects, so no geoid correction is needed.
 */
class LocationTracker(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    private val _location = MutableStateFlow<Location?>(null)
    /** Most recent fix, or null until the first one arrives. */
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
        .setMinUpdateIntervalMillis(1000L)
        .build()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { publish(it) }
        }
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun start(looper: Looper) {
        // Seed with the cached fix so the sky can render before GPS warms up.
        client.lastLocation.addOnSuccessListener { cached ->
            if (cached != null && _location.value == null) publish(cached)
        }
        client.requestLocationUpdates(request, callback, looper)
    }

    fun stop() {
        client.removeLocationUpdates(callback)
    }

    private fun publish(location: Location) {
        _location.value = location
        Log.d(TAG, "Lat: ${location.latitude}, Lon: ${location.longitude}, Alt: ${location.altitude}, ±${location.accuracy} m")
    }

    private companion object {
        const val TAG = "LocationTracker"
    }
}

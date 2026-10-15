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

/** Streams high-accuracy fixes from the fused location provider. */
class LocationTracker(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    /** Most recent fix, or null until the first one arrives. */
    var lastLocation: Location? = null
        private set

    private val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
        .setMinUpdateIntervalMillis(1000L)
        .build()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                lastLocation = location
                Log.d(TAG, "Lat: ${location.latitude}, Lon: ${location.longitude}, Alt: ${location.altitude}")
            }
        }
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun start(looper: Looper) {
        client.requestLocationUpdates(request, callback, looper)
    }

    fun stop() {
        client.removeLocationUpdates(callback)
    }

    private companion object {
        const val TAG = "GPS"
    }
}

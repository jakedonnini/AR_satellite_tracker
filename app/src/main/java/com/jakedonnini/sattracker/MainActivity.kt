package com.jakedonnini.sattracker

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jakedonnini.sattracker.data.TleFetcher
import com.jakedonnini.sattracker.orbit.OrekitInitializer
import com.jakedonnini.sattracker.orbit.SatelliteVisibility
import com.jakedonnini.sattracker.sensors.LocationTracker
import com.jakedonnini.sattracker.sensors.OrientationTracker
import io.github.sceneview.ar.ARSceneView
import kotlinx.coroutines.launch
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var arSceneView: ARSceneView
    private lateinit var locationTracker: LocationTracker
    private lateinit var orientationTracker: OrientationTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arSceneView = findViewById(R.id.sceneView)
        locationTracker = LocationTracker(this)
        orientationTracker = OrientationTracker(getSystemService(SENSOR_SERVICE) as SensorManager)

        OrekitInitializer.init(this)
        Log.d("OREKIT", "Current UTC time: ${AbsoluteDate(Date(), TimeScalesFactory.getUTC())}")

        requestLocationPermission()

        lifecycleScope.launch {
            val tleList = TleFetcher.fetchFromCelestrak()
            Log.d("OREKIT", "Fetched ${tleList.size} TLEs")

            // TODO: this runs before the first GPS fix arrives, so the observer is at 0,0.
            val location = locationTracker.lastLocation
            val visible = SatelliteVisibility.aboveHorizon(
                tles = tleList,
                latDeg = location?.latitude ?: 0.0,
                lonDeg = location?.longitude ?: 0.0,
                altMeters = location?.altitude ?: 0.0,
                date = AbsoluteDate(Date(), TimeScalesFactory.getUTC()),
            )
            for (sat in visible) {
                Log.d("VISIBLE", "Satellite ${sat.tle.satelliteNumber} is visible at ${sat.elevationDeg}°")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientationTracker.start()
    }

    override fun onPause() {
        super.onPause()
        locationTracker.stop()
        orientationTracker.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSceneView.destroy()
    }

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_CODE
            )
        } else {
            locationTracker.start(mainLooper)
        }
    }

    private companion object {
        const val LOCATION_PERMISSION_CODE = 1001
    }
}

package com.example.sat_tracker

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import io.github.sceneview.ar.ARSceneView
import org.orekit.data.*
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import org.orekit.data.DataContext
import java.io.File
import java.io.FileOutputStream
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.orekit.propagation.analytical.tle.TLEPropagator
import org.orekit.frames.FramesFactory
import org.orekit.bodies.OneAxisEllipsoid
import org.orekit.utils.IERSConventions
//import org.orekit.models.earth.GeoMagneticModelFactory
import org.orekit.bodies.GeodeticPoint
import org.orekit.frames.TopocentricFrame
import org.orekit.utils.PVCoordinatesProvider

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private var currentLat= 0.0
    private var currentLon = 0.0
    private var currentAltMeters = 0.0


    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var arSceneView: ARSceneView
    private lateinit var locationCallback: LocationCallback

    private val LOCATION_PERMISSION_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        // Initialize AR SceneView
        arSceneView = findViewById(R.id.sceneView)

        // Initialize sensors
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // Initialize location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Load Orekit in Java utility class
        OrekitInitializer.initOrekit(this)

        // Now you can safely call Orekit APIs
        val utc = TimeScalesFactory.getUTC()
        val now = AbsoluteDate(java.util.Date(), utc)
        Log.d("OREKIT", "Current UTC time: $now")

        requestLocationPermission()

        lifecycleScope.launch {
            val tleList = TLEFetcher.fetchFromCelestrak()
            Log.d("OREKIT", "Fetched ${tleList.size} TLEs")

            val utc = TimeScalesFactory.getUTC()
            val now = AbsoluteDate(java.util.Date(), utc)

            // Earth shape
            val earth = OneAxisEllipsoid(
                6378137.0, 1.0 / 298.257223563,
                FramesFactory.getITRF(IERSConventions.IERS_2010, true)
            )

            // Sample location (can use real GPS)
            val observer = GeodeticPoint(
                Math.toRadians(currentLat),
                Math.toRadians(currentLon),
                currentAltMeters
            )
            Log.d("POS", "Observer altitude: ${observer.altitude} meters")

            val topo = TopocentricFrame(earth, observer, "Observer")

            for (tle in tleList) {
                try {
                    val propagator = TLEPropagator.selectExtrapolator(tle)
                    val pv = propagator.getPVCoordinates(now, FramesFactory.getTEME()) // or use propagator.frame
                    val elevation = Math.toDegrees(topo.getElevation(pv.position, FramesFactory.getTEME(), now))



                    Log.d("ELEVATION", "Sat ${tle.satelliteNumber} at $elevation°")
                    Log.d("DEBUG_SAT", "Pos: ${tle.satelliteNumber}, Frame: ${propagator.frame.name}, Time: $now")

                    // only show sats that are above 10 degrees
                    if (elevation > 10) {
                        Log.d("VISIBLE", "Satellite ${tle.satelliteNumber} is visible at $elevation°")
                    }
                } catch (e: Exception) {
                    Log.w("TLE_ERROR", "Could not propagate satellite ${tle.satelliteNumber}", e)
                }
            }
        }
    }


    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(
            this,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_GAME
        )
        sensorManager.registerListener(
            this,
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(this)
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
            startLocationUpdates()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 2000L                    // Every 2 seconds
            fastestInterval = 1000L            // At most every 1 second
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val lat = location.latitude
                    val lon = location.longitude
                    val alt = location.altitude
                    currentLat = lat
                    currentLon = lon
                    currentAltMeters = alt
                    Log.d("GPS", "Lat: $lat, Lon: $lon, Alt: $alt")
                }
            }
        }


        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        }
    }


    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> gravity = it.values.clone()
                Sensor.TYPE_MAGNETIC_FIELD -> geomagnetic = it.values.clone()
            }

            if (gravity != null && geomagnetic != null) {
                val R = FloatArray(9)
                val I = FloatArray(9)
                if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(R, orientation)
                    val azimuth = Math.toDegrees(orientation[0].toDouble())
                    val pitch = Math.toDegrees(orientation[1].toDouble())
                    val roll = Math.toDegrees(orientation[2].toDouble())
                    Log.d("ORIENTATION", "Azimuth: $azimuth, Pitch: $pitch, Roll: $roll")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No action needed
    }
}

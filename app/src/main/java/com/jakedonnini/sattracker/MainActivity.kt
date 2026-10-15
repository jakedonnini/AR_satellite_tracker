package com.jakedonnini.sattracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jakedonnini.sattracker.camera.CameraController
import com.jakedonnini.sattracker.geometry.AzEl
import com.jakedonnini.sattracker.geometry.SkyMath
import com.jakedonnini.sattracker.orbit.Observer
import com.jakedonnini.sattracker.orbit.OrekitInitializer
import com.jakedonnini.sattracker.orbit.SkyModel
import com.jakedonnini.sattracker.orbit.SkyPosition
import com.jakedonnini.sattracker.sensors.AttitudeTracker
import com.jakedonnini.sattracker.sensors.LocationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var hud: TextView
    private lateinit var locationTracker: LocationTracker
    private lateinit var attitudeTracker: AttitudeTracker
    private lateinit var cameraController: CameraController

    @Volatile private var skyModel: SkyModel? = null
    @Volatile private var skyPositions: List<SkyPosition> = emptyList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        hud = findViewById(R.id.debugHud)

        locationTracker = LocationTracker(this)
        attitudeTracker = AttitudeTracker(getSystemService(SENSOR_SERVICE) as SensorManager)
        cameraController = CameraController(this)

        loadCatalog()
        observeSensors()

        if (hasPermission(Manifest.permission.CAMERA) && hasLocationPermission()) {
            onPermissionsResult()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    override fun onStart() {
        super.onStart()
        startLocationIfPermitted()
    }

    override fun onResume() {
        super.onResume()
        attitudeTracker.start()
    }

    override fun onPause() {
        super.onPause()
        attitudeTracker.stop()
    }

    override fun onStop() {
        super.onStop()
        locationTracker.stop()
    }

    private fun onPermissionsResult() {
        if (hasPermission(Manifest.permission.CAMERA)) {
            cameraController.start(this, previewView)
        } else {
            Log.w(TAG, "Camera permission denied")
        }
        startLocationIfPermitted()
    }

    @SuppressLint("MissingPermission") // checked by hasLocationPermission()
    private fun startLocationIfPermitted() {
        if (hasLocationPermission()) locationTracker.start(mainLooper)
    }

    private fun loadCatalog() {
        val app = application as SatTrackerApp
        lifecycleScope.launch {
            skyModel = withContext(Dispatchers.IO) {
                OrekitInitializer.init(app)
                SkyModel(app.repository.getLeoSatellites())
            }
            Log.i(TAG, "Sky model ready with ${skyModel?.size} LEO satellites")
        }
    }

    private fun observeSensors() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Keep the true-north correction in sync with where we are.
                launch {
                    locationTracker.location.filterNotNull().collect {
                        attitudeTracker.updateLocation(it.latitude, it.longitude, it.altitude)
                    }
                }
                // Coarse sky pass: who's above the horizon right now.
                launch {
                    while (true) {
                        val sky = skyModel
                        val loc = locationTracker.location.value
                        if (sky != null && loc != null) {
                            skyPositions = withContext(Dispatchers.Default) {
                                sky.positions(
                                    Observer(loc.latitude, loc.longitude, loc.altitude),
                                    AbsoluteDate(Date(), TimeScalesFactory.getUTC()),
                                    minElevationDeg = 0.0,
                                )
                            }
                        }
                        delay(SKY_REFRESH_MS)
                    }
                }
                launch {
                    while (true) {
                        hud.text = buildHud()
                        delay(HUD_REFRESH_MS)
                    }
                }
            }
        }
    }

    private fun buildHud(): String = buildString {
        val loc = locationTracker.location.value
        appendLine(
            if (loc == null) "GPS     waiting for fix…"
            else "GPS     %.5f, %.5f  alt %.0f m  ±%.0f m".format(loc.latitude, loc.longitude, loc.altitude, loc.accuracy)
        )

        val att = attitudeTracker.attitude.value
        when {
            !attitudeTracker.isSupported -> appendLine("POINT   no rotation-vector sensor")
            att == null -> appendLine("POINT   waiting for sensors…")
            else -> {
                appendLine("POINT   az %.1f°  el %.1f°".format(att.boresight.azimuthDeg, att.boresight.elevationDeg))
                appendLine(
                    "        decl %+.1f°  heading ±%s  mag %s".format(
                        att.declinationDeg,
                        att.headingAccuracyDeg?.let { "%.0f°".format(it) } ?: "?",
                        accuracyLabel(att.magnetometerAccuracy),
                    )
                )
            }
        }

        val fov = cameraController.fov.value
        appendLine(
            if (fov == null) "CAMERA  no FOV yet"
            else "CAMERA  FOV %.1f° × %.1f°  f=%.2f mm".format(fov.horizontalDeg, fov.verticalDeg, fov.focalLengthMm)
        )

        val sky = skyModel
        val positions = skyPositions
        appendLine(
            if (sky == null) "CATALOG loading…"
            else "CATALOG ${sky.size} LEO  |  ${positions.size} above horizon"
        )

        if (att != null && positions.isNotEmpty()) {
            val nearest = positions.minBy { SkyMath.separationDeg(att.boresight, AzEl(it.azimuthDeg, it.elevationDeg)) }
            val sep = SkyMath.separationDeg(att.boresight, AzEl(nearest.azimuthDeg, nearest.elevationDeg))
            append(
                "NEAREST %s (#%d)  %.1f° off\n        az %.1f°  el %.1f°  %.0f km".format(
                    nearest.satellite.name, nearest.satellite.noradId, sep,
                    nearest.azimuthDeg, nearest.elevationDeg, nearest.rangeMeters / 1000,
                )
            )
        }
    }

    private fun accuracyLabel(status: Int) = when (status) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW – calibrate"
        else -> "UNRELIABLE – calibrate"
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private companion object {
        const val TAG = "MainActivity"
        const val SKY_REFRESH_MS = 2000L
        const val HUD_REFRESH_MS = 200L
    }
}

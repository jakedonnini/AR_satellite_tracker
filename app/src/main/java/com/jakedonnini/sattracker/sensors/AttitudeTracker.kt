package com.jakedonnini.sattracker.sensors

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.jakedonnini.sattracker.geometry.AzEl
import com.jakedonnini.sattracker.geometry.SkyMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot of where the phone is pointing.
 *
 * @property rotationMatrix device→world (East, magnetic North, Up), row-major 3x3.
 * @property boresight true-north azimuth/elevation of the back camera's optical axis.
 * @property headingAccuracyDeg the sensor's own heading-error estimate, if it reports one.
 * @property magnetometerAccuracy one of SensorManager.SENSOR_STATUS_*; LOW/UNRELIABLE → ask
 *   the user to calibrate (figure-8).
 * @property jitterDeg RMS wobble of the boresight over the last ~2 s; at rest this is noise.
 */
data class Attitude(
    val rotationMatrix: FloatArray,
    val declinationDeg: Double,
    val boresight: AzEl,
    val headingAccuracyDeg: Double?,
    val magnetometerAccuracy: Int,
    val mode: AttitudeTracker.Mode,
    val jitterDeg: Double?,
)

/**
 * Tracks device attitude for the AR overlay.
 *
 * [Mode.FUSED] (default): orientation comes from TYPE_GAME_ROTATION_VECTOR (gyro +
 * accelerometer: smooth, exact tilt, no north), and its heading is pinned to the compass by a
 * slow [HeadingFusion] filter fed from TYPE_ROTATION_VECTOR. This removes the jitter and
 * "popping" that magnetometer noise causes, while keeping the long-run compass accuracy.
 *
 * [Mode.RAW]: TYPE_ROTATION_VECTOR as-is. Kept for A/B comparison and as a fallback on devices
 * without a game rotation vector.
 *
 * The magnetic heading is corrected to true north using the WMM model built into Android
 * ([GeomagneticField]) at the last known location.
 */
class AttitudeTracker(private val sensorManager: SensorManager) : SensorEventListener {

    enum class Mode { FUSED, RAW }

    private val absoluteSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    // Registered only for its accuracy callbacks, which drive the calibration prompt.
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    /** False on devices without a rotation-vector sensor (usually: no gyroscope). */
    val isSupported: Boolean get() = absoluteSensor != null

    var mode: Mode = if (gameSensor != null) Mode.FUSED else Mode.RAW
        set(value) {
            field = if (value == Mode.FUSED && gameSensor == null) Mode.RAW else value
            jitter.reset()
        }

    private val _attitude = MutableStateFlow<Attitude?>(null)
    val attitude: StateFlow<Attitude?> = _attitude.asStateFlow()

    private val fusion = HeadingFusion()
    private val jitter = JitterMeter()
    private var latestGame: FloatArray? = null
    private var latestHeadingAccuracyDeg: Double? = null

    @Volatile private var declinationDeg = 0.0
    @Volatile private var magnetometerAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    fun start() {
        val absolute = absoluteSensor ?: run {
            Log.e(TAG, "No TYPE_ROTATION_VECTOR sensor on this device")
            return
        }
        sensorManager.registerListener(this, absolute, SensorManager.SENSOR_DELAY_GAME)
        gameSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Recompute magnetic declination for a new location. Cheap; call on each GPS fix. */
    fun updateLocation(latDeg: Double, lonDeg: Double, altMeters: Double) {
        declinationDeg = GeomagneticField(
            latDeg.toFloat(), lonDeg.toFloat(), altMeters.toFloat(), System.currentTimeMillis()
        ).declination.toDouble()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rAbs = rotationFrom(event)
                // values[4], when present, is the estimated heading accuracy in radians (-1 = unknown).
                latestHeadingAccuracyDeg = event.values.getOrNull(4)
                    ?.takeIf { it >= 0f }
                    ?.let { Math.toDegrees(it.toDouble()) }
                latestGame?.let { fusion.onAbsolute(rAbs, it, event.timestamp) }
                if (mode == Mode.RAW) publish(rAbs)
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val rGame = rotationFrom(event)
                latestGame = rGame
                if (mode == Mode.FUSED) fusion.fuse(rGame)?.let { publish(it) }
            }
        }
    }

    private fun publish(r: FloatArray) {
        val declination = declinationDeg
        val boresightEnu = SkyMath.cameraBoresight(r)
        _attitude.value = Attitude(
            rotationMatrix = r,
            declinationDeg = declination,
            boresight = SkyMath.toAzEl(boresightEnu, declination),
            headingAccuracyDeg = latestHeadingAccuracyDeg,
            magnetometerAccuracy = magnetometerAccuracy,
            mode = mode,
            jitterDeg = jitter.add(boresightEnu.unit()),
        )
    }

    private fun rotationFrom(event: SensorEvent) =
        FloatArray(9).also { SensorManager.getRotationMatrixFromVector(it, event.values) }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
        // Compass just got (re)calibrated: let the fused heading snap to it quickly.
        if (accuracy > magnetometerAccuracy) fusion.rewarm(SystemClock.elapsedRealtimeNanos())
        magnetometerAccuracy = accuracy
    }

    private companion object {
        const val TAG = "AttitudeTracker"
    }
}

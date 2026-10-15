package com.jakedonnini.sattracker.sensors

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
 */
data class Attitude(
    val rotationMatrix: FloatArray,
    val declinationDeg: Double,
    val boresight: AzEl,
    val headingAccuracyDeg: Double?,
    val magnetometerAccuracy: Int,
    val timestampNanos: Long,
)

/**
 * Tracks device attitude from TYPE_ROTATION_VECTOR: the OS's fusion of gyroscope (smooth,
 * fast), accelerometer (gravity / tilt) and magnetometer (absolute heading). On most phones
 * this fusion runs in the sensor hub, which beats doing our own complementary/Kalman filter.
 *
 * The magnetic heading is corrected to true north using the WMM model built into Android
 * ([GeomagneticField]) at the last known location.
 */
class AttitudeTracker(private val sensorManager: SensorManager) : SensorEventListener {

    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Registered only for its accuracy callbacks, which drive the calibration prompt.
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    /** False on devices without a rotation-vector sensor (usually: no gyroscope). */
    val isSupported: Boolean get() = rotationSensor != null

    private val _attitude = MutableStateFlow<Attitude?>(null)
    val attitude: StateFlow<Attitude?> = _attitude.asStateFlow()

    @Volatile private var declinationDeg = 0.0
    @Volatile private var magnetometerAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    fun start() {
        val sensor = rotationSensor ?: run {
            Log.e(TAG, "No TYPE_ROTATION_VECTOR sensor on this device")
            return
        }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
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
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // values[4], when present, is the estimated heading accuracy in radians (-1 = unknown).
        val headingAccuracy = event.values.getOrNull(4)
            ?.takeIf { it >= 0f }
            ?.let { Math.toDegrees(it.toDouble()) }

        val declination = declinationDeg
        _attitude.value = Attitude(
            rotationMatrix = r,
            declinationDeg = declination,
            boresight = SkyMath.toAzEl(SkyMath.cameraBoresight(r), declination),
            headingAccuracyDeg = headingAccuracy,
            magnetometerAccuracy = magnetometerAccuracy,
            timestampNanos = event.timestamp,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magnetometerAccuracy = accuracy
    }

    private companion object {
        const val TAG = "AttitudeTracker"
    }
}

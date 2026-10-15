package com.jakedonnini.sattracker.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/** Euler angles from [SensorManager.getOrientation], in degrees. */
data class DeviceOrientation(val azimuthDeg: Double, val pitchDeg: Double, val rollDeg: Double)

/**
 * Derives device orientation from the accelerometer + magnetometer.
 *
 * Note: no gyroscope is used, angles are relative to magnetic (not true) north, and the
 * axes are the device's screen frame rather than the back-camera look direction.
 */
class OrientationTracker(private val sensorManager: SensorManager) : SensorEventListener {
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null

    /** Most recent orientation, or null until both sensors have reported. */
    var latest: DeviceOrientation? = null
        private set

    fun start() {
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

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> gravity = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> geomagnetic = event.values.clone()
        }

        val g = gravity ?: return
        val m = geomagnetic ?: return
        val rotation = FloatArray(9)
        if (SensorManager.getRotationMatrix(rotation, null, g, m)) {
            val angles = FloatArray(3)
            SensorManager.getOrientation(rotation, angles)
            latest = DeviceOrientation(
                azimuthDeg = Math.toDegrees(angles[0].toDouble()),
                pitchDeg = Math.toDegrees(angles[1].toDouble()),
                rollDeg = Math.toDegrees(angles[2].toDouble()),
            ).also { Log.d(TAG, "Azimuth: ${it.azimuthDeg}, Pitch: ${it.pitchDeg}, Roll: ${it.rollDeg}") }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "ORIENTATION"
    }
}

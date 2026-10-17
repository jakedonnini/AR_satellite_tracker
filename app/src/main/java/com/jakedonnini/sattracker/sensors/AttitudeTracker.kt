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

/** Where the heading is coming from right now, for the UI (design doc §6.2a). */
data class HeadingStatus(
    val state: HeadingFusion.State,
    val field: FieldQuality,
    /** Measured field strength relative to the World Magnetic Model, percent (NaN if unknown). */
    val fieldStrengthDeviationPct: Double,
    /** Measured dip angle minus the model's, degrees (NaN if unknown). */
    val dipDeviationDeg: Double,
    /** Name of the object the user aligned on, or null. */
    val alignedTo: String?,
    val alignedAtMillis: Long?,
    /** True when the alignment was carried across a sensor restart and is only approximate. */
    val alignmentApproximate: Boolean,
)

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
    val heading: HeadingStatus,
)

/**
 * Tracks device attitude for the AR overlay.
 *
 * [Mode.FUSED] (default): gyro-first. Orientation comes from TYPE_GAME_ROTATION_VECTOR (gyro +
 * accelerometer: smooth, exact tilt, no north). North comes from a sky alignment if the user
 * made one, otherwise from the compass (TYPE_ROTATION_VECTOR) but only while the measured
 * magnetic field matches the World Magnetic Model; when it doesn't, heading is held on the gyro.
 * See [HeadingFusion] and [MagneticCheck].
 *
 * [Mode.RAW]: TYPE_ROTATION_VECTOR as-is, for A/B comparison and devices without a gyro.
 *
 * The magnetic heading is corrected to true north using the WMM model built into Android
 * ([GeomagneticField]) at the last known location.
 */
class AttitudeTracker(private val sensorManager: SensorManager) : SensorEventListener {

    enum class Mode { FUSED, RAW }

    private val absoluteSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    // Raw magnetometer: its readings feed the interference check, its accuracy the calibration prompt.
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
    private val fieldFilter = FieldQualityFilter()
    private val jitter = JitterMeter()
    private var latestGame: FloatArray? = null
    private var latestHeadingAccuracyDeg: Double? = null

    @Volatile private var declinationDeg = 0.0
    /** World Magnetic Model expectations at the last location, for interference checks. */
    @Volatile private var expectedFieldUt = Double.NaN
    @Volatile private var expectedDipDeg = Double.NaN
    private val magneticField = FloatArray(3)
    private var fieldStrengthUt = Double.NaN
    private var fieldDipDeg = Double.NaN
    @Volatile private var magnetometerAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    @Volatile private var alignedTo: String? = null
    @Volatile private var alignedAtMillis: Long? = null
    @Volatile private var alignmentApproximate = false
    private var started = false

    private var recorder: HeadingRecorder? = null

    /** Starts writing heading diagnostics to [file] (overwritten), for field drift tests. */
    fun recordDiagnostics(file: java.io.File) {
        recorder?.close()
        recorder = HeadingRecorder(file)
    }

    fun start() {
        val absolute = absoluteSensor ?: run {
            Log.e(TAG, "No TYPE_ROTATION_VECTOR sensor on this device")
            return
        }
        // Re-registering may restart the gyro frame with a new yaw reference.
        if (started) {
            fusion.onFrameRestart()
            if (alignedTo != null) alignmentApproximate = true
        }
        started = true
        sensorManager.registerListener(this, absolute, SensorManager.SENSOR_DELAY_GAME)
        gameSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        recorder?.flush()
    }

    /** Recompute magnetic declination for a new location. Cheap; call on each GPS fix. */
    fun updateLocation(latDeg: Double, lonDeg: Double, altMeters: Double) {
        val field = GeomagneticField(
            latDeg.toFloat(), lonDeg.toFloat(), altMeters.toFloat(), System.currentTimeMillis()
        )
        declinationDeg = field.declination.toDouble()
        expectedFieldUt = field.fieldStrength / 1000.0   // nT → µT
        expectedDipDeg = field.inclination.toDouble()
    }

    /**
     * Sky alignment: the user centred [targetName] in the reticle and the camera is pointing
     * [azimuthErrorDeg] away from where that object really is. Called on the main thread; the
     * sensor thread is the main looper too, so no extra locking is needed.
     */
    fun align(targetName: String, azimuthErrorDeg: Double) {
        fusion.align(azimuthErrorDeg)
        alignedTo = targetName
        alignedAtMillis = System.currentTimeMillis()
        alignmentApproximate = false
    }

    fun clearAlignment() {
        fusion.clearAlignment(SystemClock.elapsedRealtimeNanos())
        alignedTo = null
        alignedAtMillis = null
        alignmentApproximate = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> event.values.copyInto(magneticField, endIndex = 3)
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rAbs = rotationFrom(event)
                // values[4], when present, is the estimated heading accuracy in radians (-1 = unknown).
                latestHeadingAccuracyDeg = event.values.getOrNull(4)
                    ?.takeIf { it >= 0f }
                    ?.let { Math.toDegrees(it.toDouble()) }
                fieldStrengthUt = MagneticCheck.strengthUt(magneticField)
                fieldDipDeg = MagneticCheck.dipDeg(rAbs, magneticField)
                val quality = fieldFilter.update(
                    MagneticCheck.classify(fieldStrengthUt, fieldDipDeg, expectedFieldUt, expectedDipDeg),
                    event.timestamp,
                )
                latestGame?.let { fusion.onAbsolute(rAbs, it, event.timestamp, quality) }
                if (mode == Mode.RAW) publish(rAbs)
                recorder?.let { rec -> latestGame?.let { record(rec, rAbs, it, event.timestamp) } }
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val rGame = rotationFrom(event)
                latestGame = rGame
                if (mode == Mode.FUSED) fusion.fuse(rGame)?.let { publish(it) }
            }
        }
    }

    private fun headingStatus() = HeadingStatus(
        state = fusion.state,
        field = fieldFilter.current,
        fieldStrengthDeviationPct = 100 * (fieldStrengthUt / expectedFieldUt - 1),
        dipDeviationDeg = fieldDipDeg - expectedDipDeg,
        alignedTo = alignedTo,
        alignedAtMillis = alignedAtMillis,
        alignmentApproximate = alignmentApproximate,
    )

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
            heading = headingStatus(),
        )
    }

    private fun record(rec: HeadingRecorder, rAbs: FloatArray, rGame: FloatArray, timestampNanos: Long) {
        val fused = fusion.fuse(rGame) ?: return
        val abs = SkyMath.toAzEl(SkyMath.cameraBoresight(rAbs), declinationDeg)
        val game = SkyMath.toAzEl(SkyMath.cameraBoresight(rGame))
        val fusedDir = SkyMath.toAzEl(SkyMath.cameraBoresight(fused), declinationDeg)
        rec.write(
            timestampNanos, abs.azimuthDeg, game.azimuthDeg, fusedDir.azimuthDeg, fusedDir.elevationDeg,
            fusion.offsetDeg ?: Double.NaN, fusion.lastSampleDeg, fusion.lastInnovationDeg, fusion.state.name,
            magnetometerAccuracy, latestHeadingAccuracyDeg ?: Double.NaN,
            fieldStrengthUt, expectedFieldUt, fieldDipDeg, expectedDipDeg,
        )
    }

    private fun rotationFrom(event: SensorEvent) =
        FloatArray(9).also { SensorManager.getRotationMatrixFromVector(it, event.values) }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
        // Compass just got (re)calibrated: let the fused heading snap to it quickly (if clean).
        if (accuracy > magnetometerAccuracy) fusion.rewarm(SystemClock.elapsedRealtimeNanos())
        magnetometerAccuracy = accuracy
    }

    private companion object {
        const val TAG = "AttitudeTracker"
    }
}

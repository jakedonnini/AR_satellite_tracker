package com.jakedonnini.sattracker.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan

/**
 * Field of view of the back camera's full sensor, in the sensor's native landscape
 * orientation. In portrait, the screen's vertical FOV corresponds to [horizontalDeg].
 */
data class CameraFov(val horizontalDeg: Double, val verticalDeg: Double)

/**
 * Shows the back camera in a [PreviewView] via CameraX and reports the lens field of view,
 * which the overlay needs to project sky directions to pixels.
 */
class CameraController(private val context: Context) {

    // Read straight from Camera2 so the globe's view pyramid is right before the camera is ever
    // opened; refined from the bound CameraX camera in start().
    private val _fov = MutableStateFlow(backCameraFov())
    val fov: StateFlow<CameraFov?> = _fov.asStateFlow()

    private var provider: ProcessCameraProvider? = null

    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get().also { this.provider = it }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                readFov(camera)?.let { _fov.value = it }
                Log.d(TAG, "Camera bound, FOV: ${_fov.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Releases the camera (e.g. while the globe screen is showing). */
    fun stop() {
        provider?.unbindAll()
    }

    private fun backCameraFov(): CameraFov? = try {
        val manager = context.getSystemService(CameraManager::class.java)
        manager.cameraIdList
            .map { manager.getCameraCharacteristics(it) }
            .firstOrNull { it.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?.let { fovFrom(it.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE), it.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()) }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read camera characteristics", e)
        null
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun readFov(camera: Camera): CameraFov? {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val sensorSize = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
        return fovFrom(sensorSize, focal)
    }

    private fun fovFrom(sensorSize: android.util.SizeF?, focal: Float?): CameraFov? {
        if (sensorSize == null || focal == null) return null
        fun fovDeg(size: Float) = Math.toDegrees(2 * atan(size / (2.0 * focal)))
        return CameraFov(fovDeg(sensorSize.width), fovDeg(sensorSize.height))
    }

    private companion object {
        const val TAG = "CameraController"
    }
}

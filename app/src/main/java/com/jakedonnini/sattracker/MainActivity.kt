package com.jakedonnini.sattracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.text.inSpans
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jakedonnini.sattracker.camera.CameraController
import com.jakedonnini.sattracker.sensors.AttitudeTracker
import com.jakedonnini.sattracker.ui.TrackedSatellite
import com.jakedonnini.sattracker.ui.SkyOverlayView
import com.jakedonnini.sattracker.ui.SkyViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: SkyViewModel by viewModels()

    private lateinit var previewView: PreviewView
    private lateinit var overlay: SkyOverlayView
    private lateinit var hud: TextView
    private lateinit var infoCard: TextView
    private lateinit var clearButton: Button
    private lateinit var cameraController: CameraController
    private var hudExpanded = true

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.skyOverlay)
        hud = findViewById(R.id.debugHud)
        infoCard = findViewById(R.id.infoCard)
        clearButton = findViewById(R.id.clearSelection)
        cameraController = CameraController(this)

        overlay.frameSource = {
            viewModel.attitudeTracker.attitude.value?.let { attitude ->
                SkyOverlayView.Frame(
                    attitude = attitude,
                    snapshot = viewModel.snapshot.value,
                    fov = cameraController.fov.value,
                    focus = viewModel.focus.value,
                    locked = viewModel.locked.value,
                )
            }
        }
        overlay.onFocusChanged = viewModel::setFocus
        overlay.onSatelliteTapped = viewModel::toggleLock
        clearButton.setOnClickListener { viewModel.clearLocks() }
        hud.setOnClickListener { hudExpanded = !hudExpanded }
        // Debug: long-press the HUD to A/B the raw compass-driven attitude against the fused one.
        hud.setOnLongClickListener {
            val tracker = viewModel.attitudeTracker
            tracker.mode = if (tracker.mode == AttitudeTracker.Mode.FUSED) AttitudeTracker.Mode.RAW else AttitudeTracker.Mode.FUSED
            Log.i(TAG, "Attitude mode → ${tracker.mode}")
            true
        }

        observeState()

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
        viewModel.setActive(true)
        startLocationIfPermitted()
    }

    override fun onStop() {
        super.onStop()
        viewModel.setActive(false)
        viewModel.locationTracker.stop()
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
        if (hasLocationPermission()) viewModel.locationTracker.start(mainLooper)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.focus, viewModel.locked) { focus, locked -> focus to locked }
                        .collect { (focus, locked) -> renderSelection(focus, locked) }
                }
                launch {
                    var ticks = 0
                    while (true) {
                        hud.text = buildHud()
                        if (++ticks % 10 == 0) {
                            viewModel.attitudeTracker.attitude.value?.let {
                                Log.d(TAG, "jitter ${it.mode} ${it.jitterDeg} az ${it.boresight.azimuthDeg} el ${it.boresight.elevationDeg}")
                            }
                        }
                        delay(HUD_REFRESH_MS)
                    }
                }
            }
        }
    }

    /**
     * The info card shows the focused satellite, or the most recently locked one when nothing
     * is under the reticle. The clear button shows whenever anything is locked.
     */
    private fun renderSelection(focus: TrackedSatellite?, locked: List<TrackedSatellite>) {
        clearButton.visibility = if (locked.isEmpty()) View.GONE else View.VISIBLE
        clearButton.text = getString(R.string.clear_selection, locked.size)

        // Prefer the locked copy of the focused satellite: it carries the color slot.
        val shown = focus?.let { f -> locked.firstOrNull { it.satellite.noradId == f.satellite.noradId } ?: f }
            ?: locked.lastOrNull()
        if (shown == null) {
            infoCard.visibility = View.GONE
            return
        }
        val sat = shown.satellite
        val d = shown.details
        val color = shown.colorSlot?.let { SkyOverlayView.LOCK_COLORS[it % SkyOverlayView.LOCK_COLORS.size] }
        infoCard.text = SpannableStringBuilder().apply {
            if (color != null) inSpans(ForegroundColorSpan(color), RelativeSizeSpan(1.3f)) { append("● ") }
            inSpans(StyleSpan(Typeface.BOLD), RelativeSizeSpan(1.3f)) { append(sat.name) }
            append("\n")
            inSpans(ForegroundColorSpan(0xFFB0B0B0.toInt())) {
                append("NORAD ${sat.noradId}")
                if (sat.objectId.isNotBlank()) append("  ·  ${sat.objectId}")
            }
            if (d != null) {
                append("\n\n")
                append("Altitude  %,.0f km     Range  %,.0f km\n".format(d.altitudeKm, d.rangeKm))
                append("Speed     %.2f km/s   %s\n".format(d.speedKmS, if (d.rising) "↑ rising" else "↓ setting"))
                append("Az %.1f°   El %.1f°".format(d.azimuthDeg, d.elevationDeg))
            }
            append("\n")
            inSpans(ForegroundColorSpan(0xFF909090.toInt()), RelativeSizeSpan(0.85f)) {
                append(if (color != null) "Locked · tap it to release" else "Tap to lock and track")
            }
        }
        infoCard.visibility = View.VISIBLE
    }

    private fun buildHud(): String = buildString {
        val loc = viewModel.locationTracker.location.value
        val att = viewModel.attitudeTracker.attitude.value
        val sky = viewModel.skyModel.value
        val snapshot = viewModel.snapshot.value

        if (!hudExpanded) {
            append("ⓘ ")
            append(if (sky == null) "loading…" else "${snapshot?.objects?.count { it.position.u > 0 } ?: 0} sats up")
            return@buildString
        }

        appendLine(
            if (loc == null) "GPS     waiting for fix…"
            else "GPS     ±%.0f m  alt %.0f m".format(loc.accuracy, loc.altitude)
        )
        when {
            !viewModel.attitudeTracker.isSupported -> appendLine("POINT   no rotation-vector sensor")
            att == null -> appendLine("POINT   waiting for sensors…")
            else -> appendLine(
                "POINT   az %.1f°  el %.1f°  decl %+.1f°\n        heading ±%s  mag %s\n        %s  jitter %s".format(
                    att.boresight.azimuthDeg, att.boresight.elevationDeg, att.declinationDeg,
                    att.headingAccuracyDeg?.let { "%.0f°".format(it) } ?: "?",
                    accuracyLabel(att.magnetometerAccuracy),
                    att.mode,
                    att.jitterDeg?.let { "%.3f°".format(it) } ?: "…",
                )
            )
        }
        val fov = cameraController.fov.value
        appendLine(
            if (fov == null) "CAMERA  no FOV yet"
            else "CAMERA  FOV %.1f° × %.1f°".format(fov.horizontalDeg, fov.verticalDeg)
        )
        append(
            if (sky == null) "CATALOG loading…"
            else "CATALOG ${sky.size} LEO  |  ${snapshot?.objects?.count { it.position.u > 0 } ?: 0} above horizon"
        )
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
        const val HUD_REFRESH_MS = 200L
    }
}

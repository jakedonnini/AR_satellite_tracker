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
import android.webkit.WebView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.text.inSpans
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jakedonnini.sattracker.ar.Projector
import com.jakedonnini.sattracker.camera.CameraController
import com.jakedonnini.sattracker.sensors.AttitudeTracker
import com.jakedonnini.sattracker.ui.GlobeController
import com.jakedonnini.sattracker.ui.LoadingState
import com.jakedonnini.sattracker.ui.Screen
import com.jakedonnini.sattracker.ui.SkyOverlayView
import com.jakedonnini.sattracker.ui.SkyViewModel
import com.jakedonnini.sattracker.ui.TrackedSatellite
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

    private lateinit var arLayer: View
    private lateinit var globeLayer: View
    private lateinit var globe: GlobeController
    private lateinit var globeTitle: TextView
    private lateinit var loadingPanel: View
    private lateinit var loadingLabel: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var openArButton: Button

    /** On the AR screen, Back returns to the globe instead of leaving the app. */
    private val backToGlobe = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = viewModel.showScreen(Screen.GLOBE)
    }

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
        arLayer = findViewById(R.id.arLayer)
        globeLayer = findViewById(R.id.globeLayer)
        globeTitle = findViewById(R.id.globeTitle)
        loadingPanel = findViewById(R.id.loadingPanel)
        loadingLabel = findViewById(R.id.loadingLabel)
        loadingBar = findViewById(R.id.loadingBar)
        openArButton = findViewById(R.id.openAr)

        globe = GlobeController(
            findViewById<WebView>(R.id.globeWebView),
            catalogJson = { viewModel.globeCatalogJson },
            positions = { viewModel.globePositions },
        ).also { it.start() }
        openArButton.setOnClickListener { viewModel.showScreen(Screen.AR) }
        findViewById<Button>(R.id.openGlobe).setOnClickListener { viewModel.showScreen(Screen.GLOBE) }
        findViewById<Button>(R.id.centerOnMe).setOnClickListener { globe.flyToUser() }
        onBackPressedDispatcher.addCallback(this, backToGlobe)

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
        if (!hasPermission(Manifest.permission.CAMERA)) Log.w(TAG, "Camera permission denied")
        if (viewModel.screen.value == Screen.AR) startCameraIfPermitted()
        startLocationIfPermitted()
    }

    private fun startCameraIfPermitted() {
        if (hasPermission(Manifest.permission.CAMERA)) cameraController.start(this, previewView)
    }

    /** Shows one screen; the other is hidden and its expensive work (camera, WebGL) paused. */
    private fun applyScreen(screen: Screen) {
        when (screen) {
            Screen.AR -> {
                globeLayer.visibility = View.GONE
                globe.onHidden()
                arLayer.visibility = View.VISIBLE
                startCameraIfPermitted()
                overlay.invalidate() // restart the overlay's per-frame redraw loop
            }
            Screen.GLOBE -> {
                arLayer.visibility = View.GONE
                cameraController.stop()
                viewModel.setFocus(null)
                globeLayer.visibility = View.VISIBLE
                globe.onShown()
            }
        }
        backToGlobe.isEnabled = screen == Screen.AR
    }

    private fun renderLoading(state: LoadingState) {
        loadingBar.progress = (state.progress * loadingBar.max).toInt()
        loadingLabel.text = state.error ?: state.stage
        val ready = state.done && state.error == null
        loadingPanel.visibility = if (ready) View.GONE else View.VISIBLE
        openArButton.visibility = if (state.done) View.VISIBLE else View.GONE
        globeTitle.visibility = if (ready) View.VISIBLE else View.GONE
        viewModel.skyModel.value?.let { globeTitle.text = getString(R.string.globe_title, it.size) }
    }

    /** Sends the user's position and live camera view to the globe (~10 Hz while it's showing). */
    private fun pushGlobePose() {
        val loc = viewModel.locationTracker.location.value ?: return
        val att = viewModel.attitudeTracker.attitude.value ?: return
        val w = globeLayer.width.toFloat()
        val h = globeLayer.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val fov = cameraController.fov.value?.horizontalDeg ?: DEFAULT_LONG_FOV_DEG
        val dirs = Projector.viewDirections(att.rotationMatrix, att.declinationDeg, w, h, fov)
        globe.pushPose(loc.latitude, loc.longitude, loc.altitude, dirs)
    }

    private fun pushGlobeLocked(locked: List<TrackedSatellite>, orbits: Map<Int, FloatArray>) {
        globe.pushLocked(locked.map {
            val color = SkyOverlayView.LOCK_COLORS[(it.colorSlot ?: 0) % SkyOverlayView.LOCK_COLORS.size]
            GlobeController.LockedSatellite(
                id = it.satellite.noradId,
                name = it.satellite.name,
                color = String.format("#%06X", color and 0xFFFFFF),
                path = orbits[it.satellite.noradId],
            )
        })
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
                launch { viewModel.screen.collect(::applyScreen) }
                launch { viewModel.loading.collect(::renderLoading) }
                launch {
                    combine(viewModel.locked, viewModel.lockedOrbits) { locked, orbits -> locked to orbits }
                        .collect { (locked, orbits) -> pushGlobeLocked(locked, orbits) }
                }
                launch {
                    while (true) {
                        if (viewModel.screen.value == Screen.GLOBE) pushGlobePose()
                        delay(GLOBE_POSE_MS)
                    }
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
        const val GLOBE_POSE_MS = 100L
        /** Used until the camera's real FOV is known. */
        const val DEFAULT_LONG_FOV_DEG = 70.0
    }
}

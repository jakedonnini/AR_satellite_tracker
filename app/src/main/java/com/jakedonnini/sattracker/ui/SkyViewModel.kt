package com.jakedonnini.sattracker.ui

import android.app.Application
import android.hardware.SensorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jakedonnini.sattracker.SatTrackerApp
import com.jakedonnini.sattracker.orbit.Observer
import com.jakedonnini.sattracker.orbit.OrekitInitializer
import com.jakedonnini.sattracker.orbit.Satellite
import com.jakedonnini.sattracker.orbit.SatelliteDetails
import com.jakedonnini.sattracker.orbit.SkyModel
import com.jakedonnini.sattracker.orbit.SkySnapshot
import com.jakedonnini.sattracker.orbit.TrackPoint
import com.jakedonnini.sattracker.sensors.AttitudeTracker
import com.jakedonnini.sattracker.sensors.LocationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything shown about the satellite under the reticle. */
data class FocusState(
    val satellite: Satellite,
    val details: SatelliteDetails? = null,
    /** Sky track around now, for drawing the orbit path. */
    val track: List<TrackPoint> = emptyList(),
)

/**
 * Owns the sky state: sensors, the catalog, the periodic sky snapshot and the focused
 * satellite. The Activity forwards lifecycle (via [setActive]) and permissions; the overlay
 * reads the latest values every frame.
 */
class SkyViewModel(app: Application) : AndroidViewModel(app) {

    val locationTracker = LocationTracker(app)
    val attitudeTracker = AttitudeTracker(app.getSystemService(SensorManager::class.java))

    private val _skyModel = MutableStateFlow<SkyModel?>(null)
    val skyModel: StateFlow<SkyModel?> = _skyModel.asStateFlow()

    private val _snapshot = MutableStateFlow<SkySnapshot?>(null)
    val snapshot: StateFlow<SkySnapshot?> = _snapshot.asStateFlow()

    private val _focus = MutableStateFlow<FocusState?>(null)
    val focus: StateFlow<FocusState?> = _focus.asStateFlow()

    @Volatile private var active = false
    private var focusJob: Job? = null

    init {
        loadCatalog(app as SatTrackerApp)

        // Keep the true-north correction in sync with where we are.
        viewModelScope.launch {
            locationTracker.location.filterNotNull().collect {
                attitudeTracker.updateLocation(it.latitude, it.longitude, it.altitude)
            }
        }

        // Two-tier sky pass. A full pass over the whole catalog (~1.8 s on a Pixel 6 Pro) runs
        // rarely and finds candidates well below the horizon; the frequent pass propagates only
        // those. The overlay extrapolates between passes using velocities.
        viewModelScope.launch {
            var candidates: Set<Int>? = null
            var candidatesMillis = 0L
            var candidatesSky: SkyModel? = null
            while (isActive) {
                val sky = _skyModel.value
                val observer = observer()
                if (active && sky != null && observer != null) {
                    _snapshot.value = withContext(Dispatchers.Default) {
                        val start = System.currentTimeMillis()
                        if (candidates == null || sky !== candidatesSky || start - candidatesMillis > CANDIDATE_REFRESH_MS) {
                            candidates = sky.snapshot(observer, start, CANDIDATE_MIN_ELEVATION_DEG)
                                .objects.mapTo(HashSet()) { it.satellite.noradId }
                            candidatesMillis = start
                            candidatesSky = sky
                            Log.v(TAG, "Full pass: ${candidates?.size} candidates in ${System.currentTimeMillis() - start} ms")
                        }
                        val t = System.currentTimeMillis()
                        sky.snapshot(observer, t, SNAPSHOT_MIN_ELEVATION_DEG, onlyIds = candidates).also {
                            Log.v(TAG, "Snapshot: ${it.objects.size} objects in ${System.currentTimeMillis() - t} ms")
                        }
                    }
                }
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }
    }

    /** Called from onStart/onStop so sensors and propagation pause in the background. */
    fun setActive(isActive: Boolean) {
        active = isActive
        if (isActive) attitudeTracker.start() else attitudeTracker.stop()
    }

    /** Called by the overlay when the reticle picks up or loses a satellite. */
    fun setFocus(satellite: Satellite?) {
        if (satellite?.noradId == _focus.value?.satellite?.noradId) return
        focusJob?.cancel()
        _focus.value = satellite?.let { FocusState(it) }
        if (satellite == null) return

        focusJob = viewModelScope.launch {
            var lastTrackMillis = 0L
            while (isActive) {
                val sky = _skyModel.value
                val observer = observer()
                if (sky != null && observer != null) {
                    val now = System.currentTimeMillis()
                    val refreshTrack = now - lastTrackMillis > TRACK_REFRESH_MS
                    val (details, track) = withContext(Dispatchers.Default) {
                        val details = sky.details(satellite, observer, now)
                        val track = if (refreshTrack) {
                            sky.track(satellite, observer, now - TRACK_PAST_MS, now + TRACK_FUTURE_MS, TRACK_STEP_S)
                        } else null
                        details to track
                    }
                    if (track != null) lastTrackMillis = now
                    _focus.value = _focus.value
                        ?.takeIf { it.satellite.noradId == satellite.noradId }
                        ?.let { it.copy(details = details, track = track ?: it.track) }
                }
                delay(DETAILS_INTERVAL_MS)
            }
        }
    }

    private fun observer(): Observer? =
        locationTracker.location.value?.let { Observer(it.latitude, it.longitude, it.altitude) }

    private fun loadCatalog(app: SatTrackerApp) {
        viewModelScope.launch {
            _skyModel.value = withContext(Dispatchers.IO) {
                OrekitInitializer.init(app)
                SkyModel(app.repository.getLeoSatellites())
            }
            Log.i(TAG, "Sky model ready with ${_skyModel.value?.size} LEO satellites")
        }
    }

    override fun onCleared() {
        attitudeTracker.stop()
        locationTracker.stop()
    }

    private companion object {
        const val TAG = "SkyViewModel"
        const val SNAPSHOT_INTERVAL_MS = 2000L
        /** Slightly below the horizon so rising satellites are ready when they clear it. */
        const val SNAPSHOT_MIN_ELEVATION_DEG = -2.0
        /**
         * LEO satellites near the horizon move well under 0.5°/s across the sky, so in 20 s
         * nothing below −10° can climb above the −2° snapshot mask.
         */
        const val CANDIDATE_MIN_ELEVATION_DEG = -10.0
        const val CANDIDATE_REFRESH_MS = 20_000L
        const val DETAILS_INTERVAL_MS = 1000L
        const val TRACK_REFRESH_MS = 30_000L
        const val TRACK_PAST_MS = 5 * 60_000L
        const val TRACK_FUTURE_MS = 15 * 60_000L
        const val TRACK_STEP_S = 15
    }
}

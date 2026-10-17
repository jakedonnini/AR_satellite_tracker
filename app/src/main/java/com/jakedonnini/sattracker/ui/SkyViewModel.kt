package com.jakedonnini.sattracker.ui

import android.app.Application
import android.hardware.SensorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jakedonnini.sattracker.SatTrackerApp
import com.jakedonnini.sattracker.data.SatelliteInfo
import com.jakedonnini.sattracker.data.SatelliteInfoRepository
import com.jakedonnini.sattracker.ar.OverlayGeometry
import com.jakedonnini.sattracker.orbit.AlignTarget
import com.jakedonnini.sattracker.orbit.Observer
import com.jakedonnini.sattracker.orbit.OrekitInitializer
import com.jakedonnini.sattracker.orbit.Satellite
import com.jakedonnini.sattracker.orbit.SatelliteDetails
import com.jakedonnini.sattracker.orbit.SkyModel
import com.jakedonnini.sattracker.orbit.SkySnapshot
import com.jakedonnini.sattracker.orbit.SkyTargets
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Everything shown about a satellite that is focused (under the reticle) or locked. */
data class TrackedSatellite(
    val satellite: Satellite,
    val details: SatelliteDetails? = null,
    /** Sky track around now, for drawing the orbit path. */
    val track: List<TrackPoint> = emptyList(),
    /** Palette slot for a locked satellite; null for one that's only focused. */
    val colorSlot: Int? = null,
)

/** The Analyze panel: the satellite, whether its online info is still loading, and the info. */
data class AnalysisState(
    val satellite: Satellite,
    val loading: Boolean,
    val info: SatelliteInfo? = null,
)

/** Which full-screen view is showing. */
enum class Screen { GLOBE, AR }

/** Startup pipeline progress for the globe screen's loading bar (§11.4 of the design doc). */
data class LoadingState(
    val stage: String,
    /** 0..1 across all stages. */
    val progress: Float,
    val done: Boolean = false,
    /** Set when loading finished without any satellites (e.g. offline with an empty cache). */
    val error: String? = null,
)

@Serializable
private class GlobeCatalog(val ids: List<Int>, val names: List<String>)

/**
 * Owns the sky state: sensors, the catalog, the periodic sky snapshot, the focused satellite
 * and the user's locked satellites. The Activity forwards lifecycle (via [setActive]) and
 * permissions; the overlay reads the latest values every frame.
 */
class SkyViewModel(app: Application) : AndroidViewModel(app) {

    val locationTracker = LocationTracker(app)
    val attitudeTracker = AttitudeTracker(app.getSystemService(SensorManager::class.java))

    private val _skyModel = MutableStateFlow<SkyModel?>(null)
    val skyModel: StateFlow<SkyModel?> = _skyModel.asStateFlow()

    private val _snapshot = MutableStateFlow<SkySnapshot?>(null)
    val snapshot: StateFlow<SkySnapshot?> = _snapshot.asStateFlow()

    private val _focus = MutableStateFlow<TrackedSatellite?>(null)
    val focus: StateFlow<TrackedSatellite?> = _focus.asStateFlow()

    private val _locked = MutableStateFlow<List<TrackedSatellite>>(emptyList())
    /** Satellites the user tapped to hold, in lock order. */
    val locked: StateFlow<List<TrackedSatellite>> = _locked.asStateFlow()

    private val _screen = MutableStateFlow(Screen.GLOBE)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _loading = MutableStateFlow(LoadingState("Starting…", 0f))
    val loading: StateFlow<LoadingState> = _loading.asStateFlow()

    private val _lockedOrbits = MutableStateFlow<Map<Int, FloatArray>>(emptyMap())
    /** One-period Earth-fixed orbit path (km, flattened xyz) per locked satellite, for the globe. */
    val lockedOrbits: StateFlow<Map<Int, FloatArray>> = _lockedOrbits.asStateFlow()

    /** Latest `/api/catalog` body for the globe page; null until the catalog is loaded. */
    @Volatile var globeCatalogJson: String? = null
        private set

    /** Latest `/api/positions` body: Float64 epoch millis + Sun direction, then Float32 ECEF state per satellite. */
    @Volatile var globePositions: ByteArray? = null
        private set

    private val infoRepository = SatelliteInfoRepository()
    private val _analysis = MutableStateFlow<AnalysisState?>(null)
    /** Non-null while the Analyze panel is open. */
    val analysis: StateFlow<AnalysisState?> = _analysis.asStateFlow()

    @Volatile private var active = false
    private var focusJob: Job? = null
    private val lockJobs = HashMap<Int, Job>()
    private val orbitComputedAt = HashMap<Int, Long>()

    /** SkyModel's bulk passes share stateful propagators, so only one may run at a time. */
    private val passLock = Mutex()

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
                if (active && _screen.value == Screen.AR && sky != null && observer != null) {
                    _snapshot.value = withContext(Dispatchers.Default) { passLock.withLock {
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
                    } }
                }
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }

        // Globe pass: every satellite, Earth-fixed, while the globe is showing.
        viewModelScope.launch {
            while (isActive) {
                val sky = _skyModel.value
                if (active && _screen.value == Screen.GLOBE && sky != null) {
                    updateGlobePositions(sky)
                    refreshLockedOrbits(sky, maxAgeMillis = ORBIT_REFRESH_MS)
                }
                delay(GLOBE_INTERVAL_MS)
            }
        }
    }

    fun showScreen(screen: Screen) {
        _screen.value = screen
    }

    private suspend fun updateGlobePositions(sky: SkyModel) {
        globePositions = withContext(Dispatchers.Default) {
            passLock.withLock {
                val start = System.currentTimeMillis()
                val snap = sky.globeSnapshot(start)
                Log.v(TAG, "Globe pass: ${sky.size} sats in ${System.currentTimeMillis() - start} ms")
                // Header: Float64 epoch millis, Float64 Sun direction (unit, ECEF); then Float32 state.
                ByteBuffer.allocate(GLOBE_HEADER_BYTES + snap.data.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putDouble(snap.epochMillis.toDouble())
                    snap.sunDir.forEach { putDouble(it) }
                    asFloatBuffer().put(snap.data)
                }.array()
            }
        }
    }

    /** Computes orbit paths for locked satellites that have none or one older than [maxAgeMillis]. */
    private suspend fun refreshLockedOrbits(sky: SkyModel, maxAgeMillis: Long) {
        val now = System.currentTimeMillis()
        val lockedSats = _locked.value.map { it.satellite }
        val keep = lockedSats.map { it.noradId }.toSet()
        val stale = lockedSats.filter { now - (orbitComputedAt[it.noradId] ?: 0L) > maxAgeMillis }
        if (stale.isEmpty() && _lockedOrbits.value.keys == keep) return
        val fresh = withContext(Dispatchers.Default) {
            stale.associate { it.noradId to sky.orbitPathEcef(it, now) }
        }
        fresh.keys.forEach { orbitComputedAt[it] = now }
        orbitComputedAt.keys.retainAll(keep)
        _lockedOrbits.value = (_lockedOrbits.value + fresh).filterKeys { it in keep }
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
        _focus.value = satellite?.let { TrackedSatellite(it) }
        if (satellite == null) return

        focusJob = launchTracking(satellite) { details, track ->
            _focus.value = _focus.value
                ?.takeIf { it.satellite.noradId == satellite.noradId }
                ?.let { it.copy(details = details, track = track ?: it.track) }
        }
    }

    fun isLocked(noradId: Int) = _locked.value.any { it.satellite.noradId == noradId }

    /** Tap handler: lock a satellite (giving it the next free color), or release it. */
    fun toggleLock(satellite: Satellite) {
        val id = satellite.noradId
        if (isLocked(id)) {
            lockJobs.remove(id)?.cancel()
            _locked.value = _locked.value.filterNot { it.satellite.noradId == id }
            _lockedOrbits.value -= id
            return
        }
        val slot = OverlayGeometry.nextColorSlot(_locked.value.mapNotNull { it.colorSlot }, LOCK_PALETTE_SIZE)
        // Seed with whatever focus already computed so the path shows immediately.
        val seed = _focus.value?.takeIf { it.satellite.noradId == id }
        _locked.value += TrackedSatellite(
            satellite,
            details = seed?.details,
            track = seed?.track.orEmpty(),
            colorSlot = slot,
        )
        _skyModel.value?.let { sky -> viewModelScope.launch { refreshLockedOrbits(sky, ORBIT_REFRESH_MS) } }
        lockJobs[id] = launchTracking(satellite) { details, track ->
            _locked.value = _locked.value.map {
                if (it.satellite.noradId == id) it.copy(details = details, track = track ?: it.track) else it
            }
        }
    }

    /** Opens the Analyze panel and fetches the satellite's online info (cached per session). */
    fun analyze(satellite: Satellite) {
        _analysis.value = AnalysisState(satellite, loading = true)
        viewModelScope.launch {
            val info = infoRepository.fetch(satellite)
            if (_analysis.value?.satellite?.noradId == satellite.noradId) {
                _analysis.value = AnalysisState(satellite, loading = false, info = info)
            }
        }
    }

    fun closeAnalysis() {
        _analysis.value = null
    }

    fun clearLocks() {
        lockJobs.values.forEach { it.cancel() }
        lockJobs.clear()
        _locked.value = emptyList()
        _lockedOrbits.value = emptyMap()
    }

    /**
     * Keeps one satellite's details fresh (every [DETAILS_INTERVAL_MS]) and its sky track
     * (every [TRACK_REFRESH_MS]); [update] receives a null track when it wasn't recomputed.
     */
    private fun launchTracking(
        satellite: Satellite,
        update: (SatelliteDetails, List<TrackPoint>?) -> Unit,
    ): Job = viewModelScope.launch {
        var lastTrackMillis = 0L
        while (isActive) {
            val sky = _skyModel.value
            val observer = observer()
            if (active && sky != null && observer != null) {
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
                update(details, track)
            }
            delay(DETAILS_INTERVAL_MS)
        }
    }

    /** Built on first use, after Orekit's data is loaded (it reads the planetary ephemeris). */
    private val skyTargets by lazy { SkyTargets() }

    /** The Moon's position right now, or null until GPS and orbit data are ready. */
    suspend fun moon(): AlignTarget? {
        val observer = observer() ?: return null
        if (_skyModel.value == null) return null
        return withContext(Dispatchers.Default) { skyTargets.position("Moon", observer, System.currentTimeMillis()) }
    }

    private fun observer(): Observer? =
        locationTracker.location.value?.let { Observer(it.latitude, it.longitude, it.altitude) }

    /**
     * Startup pipeline, reported as one weighted progress bar: Orekit data (10%), catalog
     * download / cache read (50%), building propagators (30%), first globe positions (10%).
     */
    private fun loadCatalog(app: SatTrackerApp) {
        viewModelScope.launch {
            fun report(stage: String, start: Float, weight: Float, fraction: Float) {
                _loading.value = LoadingState(stage, start + weight * fraction.coerceIn(0f, 1f))
            }
            val sky = withContext(Dispatchers.IO) {
                OrekitInitializer.init(app) { report("Loading orbit reference data", 0f, 0.1f, it) }
                val satellites = app.repository.getLeoSatellites { report("Getting satellite catalog", 0.1f, 0.5f, it) }
                val label = "Preparing %,d satellites".format(satellites.size)
                SkyModel(satellites) { report(label, 0.6f, 0.3f, it) }
            }
            _skyModel.value = sky
            Log.i(TAG, "Sky model ready with ${sky.size} LEO satellites")
            globeCatalogJson = Json.encodeToString(
                GlobeCatalog(sky.satellites.map { it.noradId }, sky.satellites.map { it.name })
            )

            report("Computing satellite positions", 0.9f, 0.1f, 0f)
            updateGlobePositions(sky)
            _loading.value = if (sky.size == 0) {
                LoadingState("No satellite data", 1f, done = true, error = "No satellite data. Check your connection and restart.")
            } else {
                LoadingState("%,d satellites ready".format(sky.size), 1f, done = true)
            }
        }
    }

    override fun onCleared() {
        attitudeTracker.stop()
        locationTracker.stop()
    }

    companion object {
        private const val TAG = "SkyViewModel"
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
        const val GLOBE_INTERVAL_MS = 5000L
        private const val GLOBE_HEADER_BYTES = 4 * 8
        const val ORBIT_REFRESH_MS = 60_000L

        /** Number of distinct lock colors; must match SkyOverlayView.LOCK_COLORS. */
        const val LOCK_PALETTE_SIZE = 6
    }
}

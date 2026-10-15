# Sat Tracker — Design Doc

**Status:** Draft for review · **Date:** 2026-10-15 · **Author:** Jake (drafted with Claude)

## 1. Summary

Sat Tracker is an Android app you point at the sky. It shows the satellites in view, overlaid on the live camera feed. Each satellite gets a marker at its current position and a line for its orbit track, and tapping a marker (or centering it in the view) opens an info card.

To do this, the app needs four things:

1. **Where am I?** GPS gives latitude, longitude and altitude.
2. **Where is each satellite?** Download orbital elements (TLE/OMM) for LEO satellites, then propagate them with SGP4 to get azimuth and elevation from my location.
3. **Where is the phone pointing?** Fuse the device sensors to get the camera's look direction, referenced to true north.
4. **Draw it.** Project each satellite's direction into camera pixels and render markers, tracks and labels over the preview.

## 2. Current state

### After the data + sensor pass (verified on a Pixel 6 Pro, 2026-10-15)

The app now launches into a live camera preview with a debug HUD that shows every data stream.

| Area | Status | Measured on device |
|---|---|---|
| Satellite catalog | ✅ | CelesTrak `active` group as OMM/JSON → Room cache. **16,747** downloaded in about 15 s, of which **15,937** are LEO. 761 were above the horizon at test time. |
| Orbit propagation | ✅ | OMM → Orekit `TLE` (field-wise constructor, so 6-digit IDs work) → SGP4 → az/el/range. Unit-tested against Orekit's own `TopocentricFrame`. |
| GPS | ✅ | Fused location provider. First fix came from cache in under 1 s and refined to ±7 m. Permission flow fixed. |
| Pointing | ✅ (needs outdoor validation) | Gyro-driven `GAME_ROTATION_VECTOR` with its heading pinned to the compass by a slow filter, plus WMM declination (§6.2). At rest indoors, jitter dropped from spikes of 0.42° (and 1.4° pops) in raw mode to **≤ 0.05°**, with elevation steady to ±0.01°. Azimuth still glides about ±0.5° over 30 s as the indoor magnetic field wanders. The sensor's own heading-uncertainty estimate indoors was ±30°, so outdoor validation is still needed. |
| Camera | ✅ | CameraX preview. FOV read from Camera2 characteristics: 71.4° × 56.9°, f = 6.81 mm. |
| AR overlay | ✅ | Dots for about 780 satellites above the horizon, a horizon line and compass points, a center reticle. Positions are extrapolated with velocity every display frame. |
| Focus + info | ✅ | The reticle picks the closest satellite within 3° (kept until 5°, with a 1° switch margin). Its dot grows and gets a name label. The info card shows altitude, range, speed, rising/setting and az/el. Checked on device: Starlink at 484 km, 7.63 km/s. |
| Orbit path | ✅ | The focused satellite's track from −5 to +15 min at 15 s steps: past dashed, future solid with one-minute ticks, clipped at the horizon. |

**Sky pass cost (Pixel 6 Pro):** a full SGP4 pass over 16k satellites takes about 1.0–1.8 s. It now runs every 20 s with a −10° mask to find candidates (about 1,600). The 2 s pass propagates only those candidates, in about 100 ms.

**Startup timing to address later:** about 3 s to load Orekit, about 15 s for the first catalog download (later launches use the cache), and about 7 s to build 16k propagators. All of this runs off the main thread, but the sky isn't ready for roughly 25 s on first launch.

### Original review (before this work)

| Area | Status | Notes |
|---|---|---|
| Location (GPS) | ⚠️ Partly works | Fused location updates every 2 s. **Bug:** there's no `onRequestPermissionsResult`, so on first launch updates never start after the user grants permission. They start only on the next launch. |
| Satellite data | ⚠️ Partly works | Fetches **Starlink only** from CelesTrak on every launch. **There is no database.** TLEs are held in memory only, with no cache, no offline mode and no refresh. |
| Orekit / propagation | ✅ Works | Orekit 13 is initialized from bundled `orekit-data` (37 MB, copied to internal storage on first run). SGP4 gives TEME position, which is converted to a topocentric elevation. Satellites with elevation > 10° are logged. |
| Visibility filter | ❌ Wrong result | The filter runs **before the first GPS fix arrives**, so the observer is at lat 0, lon 0, alt 0 (off the coast of Africa). It also runs only once, at startup. |
| Orientation | ⚠️ Raw data only | This is **accelerometer + magnetometer** (`getRotationMatrix` / `getOrientation`), **not the gyro**. Azimuth, pitch and roll are logged and never used. There's no gyro fusion, so it will be jittery. It reports **magnetic** north with no declination correction, and the angles describe the screen plane, not the camera's look direction. |
| "Which satellite am I looking at" | ❌ Not started | |
| Camera / AR overlay | ❌ Not started | `ARSceneView` (SceneView 2.3 / ARCore) is in the layout, but nothing is ever placed in the scene. The app never requests camera permission itself. |
| UI | ❌ Not started | The Compose theme files are template leftovers and unused. The Activity uses XML + AppCompat. |

**Previously a crash, now fixed by the restructure:** on first launch, the permission dialog triggered `onPause` → `removeLocationUpdates(locationCallback)` while `lateinit locationCallback` was still uninitialized.

### Restructure done in this pass (no other behavior changes)

- Package `com.example.sat_tracker` → `com.jakedonnini.sattracker`. The applicationId changed too, so the app installs fresh.
- Split the god-`MainActivity` into `data/TleFetcher`, `orbit/OrekitInitializer` (Java → Kotlin), `orbit/SatelliteVisibility`, `sensors/LocationTracker` and `sensors/OrientationTracker`.
- Moved all hard-coded dependencies into `gradle/libs.versions.toml`. Removed the unused `sceneform-ux` entry, the duplicate appcompat entries and the redundant `hipparchus-core:3.0` pin, which Orekit was already overriding to 4.0.1.
- Stopped tracking IDE state (`.idea/`, and a stray `app/src/.idea/`). Only `.idea/codeStyles` is kept.
- Added `README.md` and this doc.

## 3. Goals and non-goals

**Goals (v1)**
- Real-time AR overlay of LEO satellites above a configurable elevation mask, at a target of 30 fps or better.
- Pointing accuracy good enough to identify a satellite: **≤ 2–3° typical error** after calibration.
- Info card showing name, NORAD ID, altitude, range, speed, azimuth/elevation, sunlit or in shadow, and the time until it sets.
- Orbit track for the selected satellite, covering the rest of the current pass.
- Works offline once the catalog has been cached.

**Non-goals (v1)**
- MEO, GEO and deep-space objects (GPS, geostationary). LEO only: mean motion ≥ 11.25 rev/day, which is CelesTrak's LEO definition.
- Brightness / magnitude prediction (maybe in v2).
- iOS, and accounts or cloud sync.

## 4. Key decision: AR approach — **decided: A (sensor AR)**

Decided 2026-10-15. ARCore and SceneView have been removed, and the camera now runs on CameraX.

| | **A. Sensor AR (recommended)** | **B. ARCore / SceneView (current deps)** |
|---|---|---|
| How | CameraX preview, plus the `TYPE_ROTATION_VECTOR` sensor (gyro + accel + mag fused by the OS), plus our own projection math, drawn on a Compose `Canvas` overlay | ARCore session for camera and tracking, with satellites placed as 3D nodes |
| Sky at night | ✅ Unaffected | ❌ ARCore's visual tracking needs texture. A dark or featureless sky loses tracking. |
| North reference | Compass + declination (we have to handle it either way) | ARCore's yaw is arbitrary, so it **still** needs the compass to align to north. Geospatial API heading needs street-level imagery, not sky. |
| Depth | Not needed (satellites are effectively at infinity, so this is pure rotation) | Gives 6-DoF, which we don't need |
| Device support | Any phone with a camera and gyro | ARCore-certified devices only (the manifest currently has `camera.ar required=true`) |
| Complexity | Small, all math is unit-testable | Heavy dependency, 3D scene management |

**Recommendation: A.** Satellites are at effectively infinite distance, so only orientation matters. ARCore's main strength, positional tracking against visual features, adds nothing here and fails exactly when satellites are visible (dusk and night). Under A, we drop the ARCore and SceneView dependencies and the `camera.ar` feature requirement. If yaw drift turns out to be a problem, a later option is to use ARCore only as a smoother rotation source, still aligned with the compass.

## 5. Architecture

```
            ┌──────────────┐     ┌─────────────────┐
CelesTrak ─▶│ TleRemote    │────▶│ SatelliteRepo   │◀── Room cache (offline, 12–24 h TTL)
            └──────────────┘     └────────┬────────┘
                                          │ List<Satellite>
 GPS ──▶ LocationTracker ──observer──▶ ┌──▼──────────────────┐
                                       │ SkyModel (orbit/)   │  SGP4 → az/el/range per sat
                                       │  coarse 1 Hz: who's │  fine 30 Hz: candidates only
                                       │  above horizon      │
                                       └──────────┬──────────┘
 Rotation-vector ─▶ AttitudeTracker ──R──┐        │ List<SkyPosition>
 sensor + declination                    ▼        ▼
                                       ┌─────────────────────┐
 CameraX ─▶ preview + intrinsics ─────▶│ Projector (ar/)     │  ENU → device → pixel
                                       └──────────┬──────────┘
                                                  ▼
                                   SkyViewModel (StateFlow) ─▶ Compose UI (overlay, info card)
```

**Package layout** (single `:app` module; split modules later only if build times hurt):

```
com.jakedonnini.sattracker
├── data/      remote TLE source, Room DB, SatelliteRepository
├── orbit/     Orekit init, propagation, visibility, pass prediction
├── sensors/   LocationTracker, AttitudeTracker
├── geometry/  pure ENU / az-el math
├── camera/    CameraX preview + field of view
├── ar/        Projector (sky → pixels), FocusSelector
└── ui/        SkyViewModel, SkyOverlayView, theme
```

The pointing and projection math lives in plain Kotlin classes with no Android dependencies, so it can be unit-tested on the JVM.

## 6. Math

### 6.1 Satellite direction (Orekit)
SGP4 output (TEME) → ITRF → `TopocentricFrame` at the observer → azimuth (from true north, clockwise), elevation and range. The current code already does the elevation part correctly. Each satellite is turned into an ENU unit vector:
`s = (cos el · sin az, cos el · cos az, sin el)`.

### 6.2 Where the phone is pointing
- **Sensors.** `TYPE_GAME_ROTATION_VECTOR` (gyro + accelerometer) gives smooth orientation with exact tilt but an arbitrary yaw. `TYPE_ROTATION_VECTOR` adds the magnetometer, so it knows north, but it is noisy indoors and jumps whenever the fusion re-corrects toward the compass. On the device, raw mode showed **1.4° pops** while the phone sat still.
- **Fusion (`sensors/HeadingFusion`).** Both frames share "up", so `R_abs ≈ Rz(ψ)·R_game`. Each compass sample gives ψ from `M = R_abs·R_gameᵀ`: `ψ = atan2(M₁₀ − M₀₁, M₀₀ + M₁₁)`. A complementary filter tracks ψ with τ = 8 s, and the output is `R = Rz(ψ)·R_game`. Motion and tilt come from the gyro; north is the time-averaged compass. With white compass noise σ, the residual heading noise is about σ·√(Δt / 2τ), roughly 0.035σ at 50 Hz.
  - **Warm-up:** τ = 0.5 s for 3 s after start, or after the magnetometer's accuracy improves (i.e. the user calibrated), so the heading locks on quickly.
  - **Disturbances:** a compass sample more than 15° from the estimate (walking past a fridge or car) only nudges it, with τ = 60 s. If the disagreement lasts more than 3 s, the estimate is assumed wrong and warm-up re-runs.
  - **Tunable trade-off:** a larger τ gives more averaging of magnetic noise but corrects gyro yaw drift more slowly. Revisit after the outdoor test.
  - **Fallback:** devices without a game rotation vector use the raw sensor. A long-press on the debug HUD toggles FUSED/RAW for A/B testing, and the HUD shows live jitter (RMS boresight wobble over the last 2 s).
- **Frames.** `R` maps device coordinates into Android's world frame (X = East, Y = **magnetic** North, Z = Up). The back camera looks along device **−Z**, so the boresight is `b = −(R[2], R[5], R[8])`. This replaces `getOrientation()`, whose angles describe the screen plane.
- **True north:** `GeomagneticField(lat, lon, alt, now).declination` gives δ, and azimuth_true = azimuth_mag + δ.
- **GPS is not a jitter source:** a 50 m position error moves a satellite 500 km away by about 0.006°.
- When the magnetometer reports low accuracy, prompt for a figure-8 calibration *(not built yet)*. A manual yaw-offset nudge is also still an option.

### 6.3 Projection to screen
For each satellite ENU vector `s` (rotated into the magnetic world frame), convert to device coordinates: `d = Rᵀ s`.
If `d.z ≥ 0`, the satellite is behind the camera, so skip it. Otherwise, using pinhole intrinsics:
`u = cx + fx · (d.x / −d.z)`, `v = cy − fy · (d.y / −d.z)`.
`fx`, `fy`, `cx` and `cy` come from `CameraCharacteristics` (`LENS_INTRINSIC_CALIBRATION`, or focal length ÷ sensor size), scaled to the preview's crop and resolution. The app is locked to portrait to avoid display-rotation remapping in v1.

### 6.4 "Which satellite am I looking at?"
This isn't triangulation. It's the angular distance between the boresight and each satellite: `θ = acos(b · s)`. The selected satellite is the one with the smallest θ under a threshold (e.g. 5°), shown as a reticle lock. Tapping selects the satellite with the nearest projected marker.

### 6.5 Orbit track
Propagate the selected satellite from now until it drops below the horizon, plus a few minutes back, at 15–30 s steps. Convert each point to az/el, project it, and draw a polyline clipped where `d.z ≥ 0`. Recompute every few seconds, not every frame.

### 6.6 Naked-eye visible vs. merely above the horizon
A satellite is actually visible when it is **sunlit** (not in Earth's shadow, checked with a cylindrical or conical shadow test using Orekit's Sun position) **and** the observer's sky is dark (Sun elevation < −6°). The UI marks these differently (e.g. bright vs. dim markers).

## 7. Data and sensor stack (implemented)

### 7.1 Where satellite data comes from

| Source | Access | Fit for this app |
|---|---|---|
| **CelesTrak GP** ✅ chosen | Free, no account, HTTPS | The same 18 SDS elements as Space-Track, re-served with grouping. Updated about every 2 h. OMM in JSON/CSV/XML. |
| Space-Track.org | Free account; ≤ 30 req/min, 300/h | Authoritative source, but its terms forbid embedding or sharing credentials, so it would need our own backend. Keep as a fallback if CelesTrak goes away. |
| N2YO API | API key; computes positions server-side | Wrong model: we need elements to propagate locally at frame rate, not per-request positions. |
| SatNOGS DB | Free | Good for radio-transmitter metadata later. Not a primary element source. |
| CelesTrak SupGP | Free | Operator-supplied ephemerides (e.g. Starlink), more accurate than GP. A possible v2 add-on for Starlink. |

**Format: OMM JSON, not TLE.** CelesTrak ran out of 5-digit catalog numbers on 2026-07-11, and objects with 6-digit numbers **can't be expressed as TLEs at all**, so the old Starlink TLE fetch was already missing new satellites. OMM also keeps more precision (e.g. eccentricity 0.00067162 vs. 0.0006716 in TLE). Orekit has no CelesTrak-JSON parser, so `orbit/Satellite.kt` builds `TLE` objects with Orekit's field-wise constructor. It never formats a TLE line, so 6-digit numbers propagate fine (unit-tested).

**Query:** a single `GET gp.php?GROUP=active&FORMAT=JSON` (about 16.7k objects, streamed straight from the socket into kotlinx.serialization). `active` is a superset of `starlink`, `stations`, `visual` and so on, and CelesTrak asks clients not to fetch both. LEO filtering happens locally in SQL.

### 7.2 Cache and fast retrieval

- **Room (SQLite)** table `satellite`, with one column per OMM field and an index on `meanMotion`. LEO = `meanMotion >= 11.25 AND eccentricity < 0.25`, filtered in SQL.
- A `catalog_fetch` table records when the data was last downloaded. The repository refreshes only if the data is **more than 12 h old**, and **never more often than every 2 h**, to match CelesTrak's update cadence. Before that, repeat downloads get HTTP 403, and more than 50 errors in 2 h gets the IP firewalled. The client never retries and doesn't follow redirects. A failure falls back to the cache, so the app works offline.
- The catalog is swapped in a single transaction, so readers never see a half-written one.
- **The hot path never touches the DB.** On launch, the LEO rows are loaded once into `SkyModel`, which keeps one `TLEPropagator` per satellite in memory. Each 2 s sky pass computes the TEME→topocentric transform once and applies it to every satellite, instead of once per satellite.
- Later: a WorkManager job to refresh in the background (needs a network connection, every 12 h), so launches never wait on the network.

### 7.3 Sensor libraries

| Data | Library / API | Why it's the best choice |
|---|---|---|
| Location | Play Services **Fused Location Provider** (`play-services-location` 21.3) | Combines GNSS, Wi-Fi and cell data. A cached fix is shown immediately, then refined. `Location.altitude` is height above the WGS-84 ellipsoid, which is exactly what Orekit's `GeodeticPoint` expects. |
| Attitude | `TYPE_GAME_ROTATION_VECTOR` + `TYPE_ROTATION_VECTOR`, fused in `HeadingFusion` | The OS's gyro + accelerometer fusion (on the sensor hub) gives smooth motion and exact tilt. Its heading is pinned to the compass-referenced rotation vector through a slow filter, so magnetometer noise doesn't jitter the overlay (§6.2). |
| True north | `android.hardware.GeomagneticField` | The World Magnetic Model built into Android. No dependency and no network needed. |
| Calibration signal | `TYPE_MAGNETIC_FIELD` accuracy callbacks | Drives the "wave in a figure-8" prompt. |
| Camera | **CameraX** 1.4 (`camera-camera2`, `-lifecycle`, `-view`) | Lifecycle-aware and handles device quirks. `Camera2CameraInfo` gives the sensor size and focal length needed for the field of view and projection. |
| Network | OkHttp 4.12 + kotlinx.serialization | Streaming JSON decoding of a multi-MB response. Retrofit would be overkill for one endpoint. |

- **Extra metadata (optional, v2):** SATCAT gives owner, launch date and object type for the info card.
- **Orekit data:** the 37 MB bundle includes MSAFE, space weather, gravity potential and tide files that SGP4 doesn't need. Keep UTC-TAI, EOP and an ephemeris for the Sun, which cuts the bundle to about 16 MB. A further option is to replace DE-440 with an analytic Sun model.

## 8. Performance

The active LEO catalog is about 16k objects (mostly Starlink), and running SGP4 for all of them at 30 Hz is too much. What's implemented:
- **Full pass** every 20 s on `Dispatchers.Default`: the whole catalog with a −10° mask, which gives the candidate set.
- **Snapshot pass** every 2 s: candidates only, giving topocentric position **and velocity** (−2° mask). One `TLEPropagator` per satellite, created once; one frame transform per pass.
- **Per frame:** linear extrapolation `p + v·Δt`, which drifts less than 0.05° over 3 s (unit-tested). No SGP4 runs on the render path.
- If profiling shows Orekit's per-call overhead is too high, interpolate positions between coarse samples, or swap in a lightweight SGP4 implementation for the hot path.

## 9. Permissions and UX

- Request camera and fine location up front, with a rationale screen. Handle denial (show a static sky map without the camera?). Start location updates on grant (fixes the current bug).
- Show "Waiting for GPS fix…" until a fix arrives. Recompute when the observer moves more than about 1 km.
- Screen layout: full-screen camera with markers and labels, a center reticle, a bottom sheet with the info card, and a top bar with filters (groups, elevation mask, visible-only).

## 10. Testing

- **Unit (JVM):** boresight from known rotation matrices, ENU ↔ az/el, projection edge cases (behind the camera, at the edge of the field of view), and the declination rotation.
- **Orbit:** compare the predicted az/el of the ISS against Heavens-Above / N2YO for a fixed time and place (within 0.5°).
- **Field:** check the pointing against known references. Polaris sits at az ≈ 0°, el ≈ latitude; the Moon's az/el comes from Orekit. Then do a real ISS pass.

## 11. Milestones

0. ✅ **Fix the foundations.** Permission flow, wait for a GPS fix before filtering, periodic recompute.
1. 🟡 **Pointing.** `AttitudeTracker` with rotation vector + declination, plus a debug HUD showing boresight az/el. *Done; still to do: a night-sky check against Polaris and the Moon.*
2. ✅ **Catalog.** Room cache, LEO filter, refresh policy. *(Group tagging is deferred to the filters work.)*
3. ✅ **Overlay.** CameraX preview, FOV, ARCore/SceneView dropped, projected markers, horizon.
4. 🟡 **Interaction.** ✅ Reticle lock, info card, orbit track. ⬜ Tap to select.
5. **Polish.** Sunlit/visible classification, filters, calibration UX, trimmed Orekit data.

## 12. Open questions for review

1. ~~**AR approach**~~ — decided: sensor AR with CameraX.
2. **Satellite set:** we now cache all active LEO (about 16k). Should the default *view* be filtered to curated groups (stations, visual, Starlink)?
3. **What to show:** everything above the horizon, or only naked-eye-visible satellites by default?
4. **Orekit:** keep it, given its 37 MB of data and heavy API, or switch to a small SGP4 library plus an analytic Sun model?
5. **Package/app ID:** is `com.jakedonnini.sattracker` fine?
6. **Min SDK:** currently 24. Any target devices to prioritize?

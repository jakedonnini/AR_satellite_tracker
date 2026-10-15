# Sat Tracker — Design Doc

**Status:** Draft for review · **Date:** 2026-10-15 · **Author:** Jake (drafted with Claude)

## 1. Summary

Sat Tracker is an Android app you point at the sky. It shows the satellites in view, overlaid on the live camera feed. Each satellite gets a marker at its current position and a line for its orbit track, and tapping a marker (or centering it in the view) opens an info card.

To do this, the app needs four things:

1. **Where am I?** GPS gives latitude, longitude and altitude.
2. **Where is each satellite?** Download orbital elements (TLE/OMM) for LEO satellites, then propagate them with SGP4 to get azimuth and elevation from my location.
3. **Where is the phone pointing?** Fuse the device sensors to get the camera's look direction, referenced to true north.
4. **Draw it.** Project each satellite's direction into camera pixels and render markers, tracks and labels over the preview.

## 2. Current state (verified 2026-10-15)

I reviewed every source file and built the project (`assembleDebug` and unit tests pass). Nothing has been run on a device yet, so the runtime behavior described below comes from reading the code.

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

## 4. Key decision: AR approach

This is the main thing to agree on before building.

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
├── ar/        projection math, camera intrinsics, overlay rendering
└── ui/        Compose screens, SkyViewModel, theme
```

The pointing and projection math lives in plain Kotlin classes with no Android dependencies, so it can be unit-tested on the JVM.

## 6. Math

### 6.1 Satellite direction (Orekit)
SGP4 output (TEME) → ITRF → `TopocentricFrame` at the observer → azimuth (from true north, clockwise), elevation and range. The current code already does the elevation part correctly. Each satellite is turned into an ENU unit vector:
`s = (cos el · sin az, cos el · cos az, sin el)`.

### 6.2 Where the phone is pointing
- `TYPE_ROTATION_VECTOR` → `SensorManager.getRotationMatrixFromVector(R)`. `R` maps device coordinates into Android's world frame (X = East, Y = **magnetic** North, Z = Up).
- The back camera looks along device **−Z**, so the boresight in world coordinates is `b = −(R[2], R[5], R[8])`.
  This replaces `getOrientation()`, whose pitch and roll describe the screen plane and give wrong answers once the phone is raised toward the sky.
- **True north:** `GeomagneticField(lat, lon, alt, now).declination` gives the declination δ. Rotate the world frame about Up by δ, so azimuth_true = azimuth_mag + δ. This is 10–15° on the US East Coast, more than enough to point at the wrong satellite.
- Boresight azimuth/elevation: `az = atan2(b.E, b.N)`, `el = asin(b.U)`. These are shown in a debug HUD.
- When the magnetometer reports low accuracy (`onAccuracyChanged`), prompt the user to do a figure-8 calibration. There's also an optional manual yaw-offset nudge.

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

## 7. Data

- **Source:** the CelesTrak GP API. OMM (JSON/CSV) is preferred over TLE because 5-digit catalog numbers are running out, and Orekit can parse OMM. Default groups are `stations` + `visual` + `starlink`, with an optional "all active LEO" filtered by mean motion ≥ 11.25 rev/day and eccentricity < 0.25.
- **Cache:** Room table `satellite(norad_id PK, name, group, line1, line2 | omm_json, epoch, fetched_at)`. Refresh when the data is more than 12 h old, and don't hit CelesTrak more than once per update cycle (they rate-limit abusive clients).
- **Extra metadata (optional, v2):** SATCAT gives owner, launch date and object type for the info card.
- **Orekit data:** the 37 MB bundle includes MSAFE, space weather, gravity potential and tide files that SGP4 doesn't need. Keep UTC-TAI, EOP and an ephemeris for the Sun, which cuts the bundle to about 16 MB. A further option is to replace DE-440 with an analytic Sun model.

## 8. Performance

Starlink alone is about 9k objects, and running SGP4 for all of them at 30 Hz is too much. The plan:
- **Coarse pass**, about every 2 s on `Dispatchers.Default`: propagate every satellite and keep those above −5° elevation (a few hundred).
- **Fine pass** at frame rate, for candidates only. Reuse one `TLEPropagator` per satellite, created once.
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

0. **Fix the foundations.** Permission flow, wait for a GPS fix before filtering, periodic recompute.
1. **Pointing.** `AttitudeTracker` with rotation vector + declination, plus a debug HUD showing boresight az/el, validated against Polaris and the Moon.
2. **Catalog.** Room cache, groups, LEO filter, refresh policy.
3. **Overlay.** CameraX preview, intrinsics, projected markers. Drop ARCore and SceneView.
4. **Interaction.** Reticle lock, tap to select, info card, orbit track.
5. **Polish.** Sunlit/visible classification, filters, calibration UX, trimmed Orekit data.

## 12. Open questions for review

1. **AR approach:** OK to go with sensor AR (A) and drop ARCore/SceneView?
2. **Satellite set:** curated groups (stations, visual, Starlink) by default, or all active LEO?
3. **What to show:** everything above the horizon, or only naked-eye-visible satellites by default?
4. **Orekit:** keep it, given its 37 MB of data and heavy API, or switch to a small SGP4 library plus an analytic Sun model?
5. **Package/app ID:** is `com.jakedonnini.sattracker` fine?
6. **Min SDK:** currently 24. Any target devices to prioritize?

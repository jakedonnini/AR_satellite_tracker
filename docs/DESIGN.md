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
| Lock + track | ✅ | Tapping a marker (36 dp hit radius) locks it; tapping again releases it. Each locked satellite gets one of 6 colors that stay distinct from the amber focus color, and keeps its dot, label and orbit path in that color while you pan around. Off-screen or behind-camera locks show a colored edge arrow pointing the way to turn (dimmed with "below horizon" once it sets). A translucent "Clear selection (N)" button appears while anything is locked. The info card shows the focused satellite, or else the most recently locked one. Confirmed on device by locking two satellites and panning away. |
| Globe screen | ✅ | Opens on a globe.gl Earth with every LEO satellite, the user's marker, the live camera-view pyramid with in-view satellites highlighted, and locked satellites with labels and one-period orbit paths. A weighted loading bar becomes the **📷 AR camera** toggle, which sits bottom-center on both screens (🌐 Globe in AR), and Back also returns to the globe. Top-right holds the screen's action (⌖ Center on me / ✕ Clear). A rippling wall of light (standing on the ground circle of a 40° cone from Earth's center through the user) marks how far out LEO satellites can be seen, there are three satellite tiers (dim / above horizon / in camera view), and the camera pyramid fades out along its length. Checked in a desktop browser with synthetic orbits (`tools/globe/dev-server.mjs`): 60 fps with 16k points, no console errors, Earth texture aligned (marker at the right place). On the Pixel 6 Pro: confirmed working by the user, no JS errors or WebGL warnings, and a full Earth-fixed pass over 15,942 satellites takes about 0.5–0.8 s every 5 s. |

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
| How | CameraX preview, plus the `TYPE_ROTATION_VECTOR` sensor (gyro + accel + mag fused by the OS), plus our own projection math, drawn by a custom `View` on a `Canvas` | ARCore session for camera and tracking, with satellites placed as 3D nodes |
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
                                   SkyViewModel (StateFlow) ─▶ SkyOverlayView + info card (Views)
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
└── ui/        SkyViewModel, SkyOverlayView
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

## 11. Globe screen (loading + external view)

*Proposed and implemented 2026-10-16 on the `globe-loading-screen` branch.*

### 11.1 What the user sees

1. **App launch → Globe screen.** A 3D Earth on a star field. You can rotate it with one finger and zoom with a pinch. It opens looking at the user's location.
2. **The user** is a marker on the surface at their GPS position. Their **camera's view** is a translucent pyramid rising from that point. It has the same horizontal and vertical field of view as the camera preview, it follows the phone's live pointing direction, and it reaches out to 2,500 km (past the range of LEO satellites near the horizon). Strictly it's a rectangular frustum rather than a round cone, so it shows *exactly* which satellites the AR screen would show. Its faces and edges **fade out** along its length (per-vertex alpha across 5 rings), so it has no hard far end.
3. **The visibility wall** is a ring of light standing on the ground around the user.
   - **Where it stands:** take a cone from Earth's center through the user's GPS position with half-angle `acos(R / (R + h))`. The cone's base, projected onto the surface, is the circle beneath which a satellite at altitude *h* sits exactly on the user's horizon. With h = 2,000 km (the top of LEO) that's about 40°, a circle roughly 4,500 km across. No LEO satellite outside the wall can be seen from the user. Satellites inside it may still be below the horizon if they're low; the cyan tier shows exactly which are up.
   - **How it looks:** the wall rises radially from that circle with a bright foot line on the ground, fades toward space, and its top edge ripples with waves travelling around the circumference (a time-animated fragment shader with additive blending).
   - **Tuning:** `WALL_ALT_KM` in `globe.js` sets *h*; 550 km (the main Starlink shell) would give about a 22° circle.
4. **Loading bar** at the bottom, with a stage label, tracking the startup pipeline (§11.4).
5. **When loading finishes:**
   - **Every satellite appears.** That's the whole LEO catalog (about 16k), not just the ones in view, drawn in three tiers: dim (not visible from the user), cyan (above the user's horizon), yellow (inside the camera's view).
   - **Locked satellites** (from the AR screen) show in their lock color, larger, with a name label and their orbit path.
   - **The loading bar becomes the "📷 AR camera" toggle**, and **"⌖ Center on me"** appears in the top right.
6. **Shared layout, no overlaps.** Both screens use the same scheme:
   - **Top row:** an info panel on the left takes the remaining width (the satellite count on the globe, the debug HUD in AR), and the screen's action is on the right ("⌖ Center on me" / "✕ Clear (N)").
   - **Bottom stack:** details or loading, then the **screen toggle**, in the same spot, size and style on both screens ("📷 AR camera" / "🌐 Globe").
   - Locks are shared both ways because both screens read the same `SkyViewModel`.

### 11.2 Rendering: globe.gl in a WebView

| Option | Verdict |
|---|---|
| **globe.gl + three.js in a WebView** ✅ | Open source (MIT). Gives orbit/zoom controls, a textured Earth, atmosphere glow, and path/label layers out of the box, and its own examples include a real-time satellite tracker. We bundle it offline, so it needs no network at runtime. |
| SceneView / Filament | Native and fast, but has no globe primitives. Drawing 16k points needs a custom compiled material, and labels and paths would be hand-built. |
| Raw OpenGL ES | The most control, but every feature (camera controls, atmosphere, labels) would be written from scratch. |

- `tools/globe/` pins exact versions (globe.gl 2.46.2, three 0.186.1, three-globe 2.45.3) and uses esbuild to produce **one** `assets/globe/vendor.js` with a single shared three.js instance. The textures are NASA Blue Marble imagery and a star field from the three-globe package.
- The page (`assets/globe/index.html` + `globe.js`) is served via AndroidX **`WebViewAssetLoader`** at `https://appassets.androidplatform.net/`. That gives it a proper HTTPS origin for `fetch()` and avoids `file://` access.

### 11.3 Data flow and frames

```
SkyModel.globeSnapshot()  ──(every 5 s, all ~16k sats)──▶  /api/positions   (binary Float32: ECEF x,y,z,vx,vy,vz in km, km/s)
SatelliteRepository       ──(once)───────────────────────▶  /api/catalog     (JSON: ids + names, same order)
SkyViewModel (10 Hz)      ──evaluateJavascript──────────▶  SatGlobe.update({observer, boresight, fov, locked[], focusId})
locked satellites         ──(on lock / every 60 s)───────▶  orbit paths: one orbital period, ECEF, 60 s steps
```

- **Frames:** positions are Earth-fixed (ITRF), so they line up with the texture. One TEME→ITRF transform is computed per pass and shared by all satellites, as in the AR snapshot.
- **Mapping to the globe:** three-globe uses a sphere of radius 100, with +y pointing north and +z through (lat 0, lon 0). So `three = (ECEF.y, ECEF.z, ECEF.x) · 100 / 6371 km`. The observer and the pyramid use the same mapping.
- **Smooth motion:** positions arrive every 5 s and the page extrapolates `p + v·Δt` every frame. Over 5 s the error from the orbit's curvature is about 0.4 km, which is invisible at globe scale.
- **The camera pyramid** is built in the observer's local East-North-Up frame from the fused boresight (true north). Its four edges are offset by ±hFOV/2 and ±vFOV/2 and rolled by the phone's roll, then converted to ECEF.
- **In-view test:** per frame and per satellite in JS, a satellite is in view if it's within 2,500 km, its elevation is above 0°, and it's inside the four side planes. That's 16k dot products, which is trivial.

### 11.4 Loading pipeline and progress

| Stage | Weight | Progress source |
|---|---|---|
| Orekit reference data | 10% | Files copied from assets (first launch only, instant afterwards) |
| Satellite catalog | 50% | Bytes downloaded vs. an estimate of about 9 MB if CelesTrak sends no `Content-Length` (it streams). Instant when the cache is fresh. |
| Building propagators | 30% | Satellites processed / total |
| First globe positions | 10% | Completion of the first full ECEF pass |

`SkyViewModel.loading: StateFlow<LoadingState(stage, fraction, done)>` drives a native `ProgressBar` and label. When `done`, they're replaced by the **📷 AR camera** toggle, and **⌖ Center on me** appears top-right.

### 11.5 Navigation and lifecycle

- **One activity, two layers.** The globe (WebView plus its controls) sits on top of the AR layer (PreviewView, overlay, HUD, info card). Switching screens toggles visibility. The ViewModel, sensors and locks are shared.
- **On the globe screen:**
  - The CameraX preview is **unbound** to save power.
  - The AR candidate/snapshot passes are **paused**.
  - The globe position pass runs every 5 s.
- **On the AR screen:** the reverse, and the WebView is paused (`onPause`) so its render loop stops.
- **Back button:** on the AR screen it returns to the globe; on the globe screen it exits.

### 11.6 Out of scope for this branch
- Day/night shading on the globe.
- Tapping satellites on the globe to lock them. The bridge could support it later; for now, locking happens on the AR screen.
- MEO/GEO objects. The catalog is still filtered to LEO (§3).

## 12. Milestones

0. ✅ **Fix the foundations.** Permission flow, wait for a GPS fix before filtering, periodic recompute.
1. 🟡 **Pointing.** `AttitudeTracker` with rotation vector + declination, plus a debug HUD showing boresight az/el. *Done; still to do: a night-sky check against Polaris and the Moon.*
2. ✅ **Catalog.** Room cache, LEO filter, refresh policy. *(Group tagging is deferred to the filters work.)*
3. ✅ **Overlay.** CameraX preview, FOV, ARCore/SceneView dropped, projected markers, horizon.
4. ✅ **Interaction.** Reticle focus, info card, orbit track, tap to lock multiple satellites with per-satellite colors, edge arrows, clear button.
5. **Polish.** Sunlit/visible classification, filters, calibration UX, trimmed Orekit data.
6. ✅ **Globe screen.** Loading progress, 3D Earth with all LEO satellites, camera-view pyramid and locked satellites (§11).

## 13. Open questions for review

1. ~~**AR approach**~~ — decided: sensor AR with CameraX.
2. **Satellite set:** we now cache all active LEO (about 16k). Should the default *view* be filtered to curated groups (stations, visual, Starlink)?
3. **What to show:** everything above the horizon, or only naked-eye-visible satellites by default?
4. **Orekit:** keep it, given its 37 MB of data and heavy API, or switch to a small SGP4 library plus an analytic Sun model?
5. **Package/app ID:** is `com.jakedonnini.sattracker` fine?
6. **Min SDK:** currently 24. Any target devices to prioritize?

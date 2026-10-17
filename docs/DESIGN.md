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
| Satellite catalog | Done | CelesTrak `active` group as OMM/JSON → Room cache. **16,747** downloaded in about 15 s, of which **15,937** are LEO. 761 were above the horizon at test time. |
| Orbit propagation | Done | OMM → Orekit `TLE` (field-wise constructor, so 6-digit IDs work) → SGP4 → az/el/range. Unit-tested against Orekit's own `TopocentricFrame`. |
| GPS | Done | Fused location provider. First fix came from cache in under 1 s and refined to ±7 m. Permission flow fixed. |
| Pointing | Done (needs outdoor validation) | Gyro-driven `GAME_ROTATION_VECTOR` with its heading pinned to the compass by a slow filter, plus WMM declination (§6.2). At rest indoors, jitter dropped from spikes of 0.42° (and 1.4° pops) in raw mode to **≤ 0.05°**, with elevation steady to ±0.01°. Azimuth still glides about ±0.5° over 30 s as the indoor magnetic field wanders. The sensor's own heading-uncertainty estimate indoors was ±30°, so outdoor validation is still needed. |
| Camera | Done | CameraX preview. FOV read from Camera2 characteristics: 71.4° × 56.9°, f = 6.81 mm. |
| AR overlay | Done | Dots for about 780 satellites above the horizon, a horizon line and compass points, a center reticle. Positions are extrapolated with velocity every display frame. |
| Focus + info | Done | The reticle picks the closest satellite within 3° (kept until 5°, with a 1° switch margin). Its dot grows and gets a name label. The info card shows altitude, range, speed, rising/setting and az/el. Checked on device: Starlink at 484 km, 7.63 km/s. |
| Orbit path | Done | The focused satellite's track from −5 to +15 min at 15 s steps: past dashed, future solid with one-minute ticks, clipped at the horizon. |
| Lock + track | Done | Tapping a marker (36 dp hit radius) locks it; tapping again releases it. Each locked satellite gets one of 6 colors that stay distinct from the amber focus color, and keeps its dot, label and orbit path in that color while you pan around. Off-screen or behind-camera locks show a colored edge arrow pointing the way to turn (dimmed with "below horizon" once it sets). A translucent "Clear selection (N)" button appears while anything is locked. The info card shows the focused satellite, or else the most recently locked one. Confirmed on device by locking two satellites and panning away. |
| Globe screen | Done | Opens on a globe.gl Earth with every LEO satellite, the user's marker, the live camera-view pyramid with in-view satellites highlighted, and locked satellites with labels and one-period orbit paths. A weighted loading bar becomes the **AR camera** toggle, which sits bottom-center on both screens (Globe in AR), and Back also returns to the globe. Top-right holds the screen's action (Center on me / Clear). A rippling wall of light (standing on the ground circle of a 40° cone from Earth's center through the user) marks how far out LEO satellites can be seen, there are three satellite tiers (dim / above horizon / in camera view), and the camera pyramid fades out along its length. Checked in a desktop browser with synthetic orbits (`tools/globe/dev-server.mjs`): 60 fps with 16k points, no console errors, Earth texture aligned (marker at the right place). On the Pixel 6 Pro: confirmed working by the user, no JS errors or WebGL warnings, and a full Earth-fixed pass over 15,942 satellites takes about 0.5–0.8 s every 5 s. |
| Naked-eye visibility | Built, awaiting field test | Sun from Orekit (DE-440), checked against the June 2026 solstice declination. Each satellite gets a cylindrical-shadow test and a magnitude estimate. AR: green dots for satellites passing all four §12 criteria, plus a visibility line on the info card and the Sun's elevation and sky state in the HUD. Globe: day/night texture blend by the real Sun and a faint green twilight wash. Golden-zone satellites (sunlit over ground past civil twilight) are green, or orange when also in the camera view, with a legend top-left. Next: compare against Heavens-Above on a clear evening. |
| Lock-on hole + Analyze + zoom | Hole confirmed on device; zoom lens awaiting device test | A locked satellite in the reticle gets a clear window onto the camera feed, with a 1–8× zoom lens fed by a full-resolution camera stream. **Analyze** shows a Wikipedia photo and intro, CelesTrak SATCAT facts and live tracker data (§13). Parsing is unit-tested against saved real responses. |

**Sky pass cost (Pixel 6 Pro):** a full SGP4 pass over 16k satellites takes about 1.0–1.8 s. It now runs every 20 s with a −10° mask to find candidates (about 1,600). The 2 s pass propagates only those candidates, in about 100 ms.

**Startup timing to address later:** about 3 s to load Orekit, about 15 s for the first catalog download (later launches use the cache), and about 7 s to build 16k propagators. All of this runs off the main thread, but the sky isn't ready for roughly 25 s on first launch.

### Original review (before this work)

| Area | Status | Notes |
|---|---|---|
| Location (GPS) | Partly works | Fused location updates every 2 s. **Bug:** there's no `onRequestPermissionsResult`, so on first launch updates never start after the user grants permission. They start only on the next launch. |
| Satellite data | Partly works | Fetches **Starlink only** from CelesTrak on every launch. **There is no database.** TLEs are held in memory only, with no cache, no offline mode and no refresh. |
| Orekit / propagation | Works | Orekit 13 is initialized from bundled `orekit-data` (37 MB, copied to internal storage on first run). SGP4 gives TEME position, which is converted to a topocentric elevation. Satellites with elevation > 10° are logged. |
| Visibility filter | Wrong result | The filter runs **before the first GPS fix arrives**, so the observer is at lat 0, lon 0, alt 0 (off the coast of Africa). It also runs only once, at startup. |
| Orientation | Raw data only | This is **accelerometer + magnetometer** (`getRotationMatrix` / `getOrientation`), **not the gyro**. Azimuth, pitch and roll are logged and never used. There's no gyro fusion, so it will be jittery. It reports **magnetic** north with no declination correction, and the angles describe the screen plane, not the camera's look direction. |
| "Which satellite am I looking at" | Not started | |
| Camera / AR overlay | Not started | `ARSceneView` (SceneView 2.3 / ARCore) is in the layout, but nothing is ever placed in the scene. The app never requests camera permission itself. |
| UI | Not started | The Compose theme files are template leftovers and unused. The Activity uses XML + AppCompat. |

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
| Sky at night | Unaffected | Fails: ARCore's visual tracking needs texture. A dark or featureless sky loses tracking. |
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

### 6.2a Keeping heading locked: sources of truth (2026-10-17)

**Problem.** With the phone held vertical, the AR heading slowly drifts (reported: to the right). Every overlay depends on heading, so this must be bounded. The fused heading has only two inputs: the gyro (smooth but drifts) and the magnetometer (absolute but easily disturbed). Possible causes, to be told apart by the on-device log below:

1. **Gyro yaw drift that the compass correction never catches up with.** Indoors the sensor's own heading uncertainty is ±30°, so compass samples often land more than 15° from the estimate. `HeadingFusion` then blends them with the slow 60 s time constant. Its 3 s "persistent disagreement" reset needs *uninterrupted* outliers, so a compass that flickers in and out of the 15° band keeps the filter mostly in slow mode, and gyro drift shows through.
2. **The compass itself is being pulled.** Nearby steel, electronics, a magnetic case, or the phone's own speaker or charging coil when held vertical. Then even a perfect filter follows a wrong north.
3. **A frame mismatch when vertical** (gyro-only "game" frame vs. absolute frame). Checked analytically; it's not expected, but the log separates it out.

**Diagnostics (built in).** `HeadingRecorder` writes `files/heading_log.csv` at 4 Hz with these columns:
- **Headings:** raw compass, gyro-only and fused azimuth.
- **Filter internals:** offset, last sample, innovation and state (WARMUP / TRACKING / OUTLIER).
- **Accuracy:** magnetometer accuracy and the sensor's heading-uncertainty estimate.
- **Magnetic field:** measured field strength and dip angle **versus the World Magnetic Model's values for this location**.

The last one is the decisive interference test: the Earth's field is fixed for a given place, so a measured strength or dip that differs from the model by more than about 10–15 % or 5° means the compass is being disturbed right now. Pull the log with `adb shell run-as com.jakedonnini.sattracker cat files/heading_log.csv`.

**Measured 2026-10-17 (Pixel 6 Pro, propped vertical and still indoors, 250 s, 4 Hz):**

| Signal | Change over 250 s | Range | Verdict |
|---|---|---|---|
| Gyro-only heading (game rotation vector) | **+0.02°** | 0.04° | Rock steady; the gyro is **not** the drift source |
| Compass heading (rotation vector) | −1.6° (drifted ~1.4° right over 2 min, then ~3° back) | **3.3°** | Wanders |
| Fused heading | −1.6° | 3.0° | **Follows the compass**, exactly as designed |
| Filter state | 99 % TRACKING, median innovation 0.09° | | The filter was never stuck in slow mode, so cause 1 is ruled out |
| Field strength vs WMM | 49–55 µT vs 46.8 µT (**+13 %**) | | **Magnetic interference** (cause 2) |
| Dip angle vs WMM | 63–68° vs 56.8° (**+8°**) | | Confirms it |

*The sensor still reported "high" magnetometer accuracy throughout, so Android's own flag doesn't catch this.*

**Conclusion:** the drift is the **compass wandering in a disturbed local field**. The fusion design (trust the compass long-term) passes that wander straight through. The gyro alone held heading to within 0.04° for four minutes, so the fix is to trust the gyro for the short term and get "north" from something better than an indoor compass:

1. **Gyro-first heading.** Hold heading on the gyro and only let the compass correct it when the field is *clean*: strength within about 8 % of the World Magnetic Model and dip within about 3°. Even then, correct slowly (a time constant of minutes, not 8 s). Otherwise freeze the correction and show **"Compass disturbed: heading held by gyro"**.
2. **Manual sky Align (option C below)** as the absolute reference. With gyro drift measured near 0°/min, one alignment on the Moon, a planet or a bright star should hold for a whole session.
3. **Automatic bright-object re-alignment (D)** next; a full star tracker (E) only if needed.

**Options for an extra source of truth, assessed:**

| Option | Accuracy | Availability | Effort | Verdict |
|---|---|---|---|---|
| **A. Fix the fusion filter** (robust windowed median of compass offsets instead of the 15° / 60 s outlier switch) | Bounded by compass accuracy (about 2–5° outdoors, worse indoors) | Always | Small | **Do first.** Removes drift caused by the filter itself. |
| **B. Magnetic-interference detection** (field strength and dip vs. WMM) | Doesn't add accuracy, but **knows when the compass is lying**: freeze on the gyro and warn instead of following a bad north | Always | Small | **Do.** Turns silent errors into a visible "Compass disturbed" state. |
| **C. Manual sky alignment**: center the Moon, a planet, a bright star (or the Sun with a filter) in the reticle and tap Align; the app knows exactly where it should be | **About 0.2–0.5°**, limited by how well it's centered | Any time a bright, known object is visible (the Moon is up about half of all nights, often in daytime too; Venus and Jupiter most evenings) | Small/medium (Sun/Moon/planets are already in Orekit's DE-440 data; a ~50-star bright-star list is tiny) | **Do. Highest value per effort**, and it fixes compass bias as well as drift. The offset is stored and the gyro holds it. |
| **D. Automatic bright-object tracker**: in the full-resolution analysis stream, look near the *predicted* positions of the Moon, bright planets and the brightest stars for a matching blob, and nudge heading when one is found | About 0.1–0.3° when locked | Clear twilight/night skies with the camera pointed near such an object. Phone sensors only reach magnitude ~1–2 in live frames, so mostly the Moon, planets and a dozen stars | Medium | **Good second step after C.** Because the predicted positions are already known, it's a small *verify* search, not a hard lost-in-space star-ID problem. |
| **E. Full star tracker (plate solving)**: capture a long exposure, detect dozens of stars, match patterns against a catalog, solve the attitude (as in tetra3 or astrometry.net) | About 0.05–0.1° | Clear dark skies, phone held still for 1–2 s exposures; light pollution and clouds stop it. Live preview frames are too short to show faint stars | High (Camera2 manual exposure, star detection, pattern matching, attitude solve) | **Viable but not first.** It's the most accurate option and the inconsistency is real: it's useless under clouds, in cities, at dusk or in daytime. Worth it later as a "precision align" mode, building on D. |
| F. ARCore Geospatial heading (VPS) | About 2–5° | Only where Street View coverage exists and with buildings in view; doesn't work pointed at the sky | Medium (would bring ARCore back) | **No.** Wrong place to look, and adds a large dependency. |
| G. GPS course while walking | Coarse | Only while moving in a straight line, and gives the walking direction, not the phone's | Small | **No** for pointing. |
| H. Sun/Moon auto-detection in daytime / twilight | About 0.2° | Moon or Sun in frame | Part of D | Folds into D. |

**Recommended plan:**
1. ~~Use the log to confirm the cause~~: done, it's compass interference (above).
2. **A + B:** gyro-first fusion plus interference detection, showing "Compass disturbed: heading held by gyro".
3. **C:** an Align button in the AR view (target picker: Moon, planets, brightest stars, Sun with a warning). The correction is saved and decays only when the compass is trustworthy again.
4. **D:** automatic re-alignment on bright objects.
5. **E** as an optional precision mode once D's detection pipeline exists.

**Implemented (steps 2 and 3):**
- `sensors/MagneticCheck.kt` compares the raw magnetometer field with the World Magnetic Model at the GPS fix (`GeomagneticField`). The compass counts as disturbed when the strength is more than 8% off or the dip is more than 3° off. A debounce applies: 0.5 s to call it disturbed, 3 s of clean readings to trust it again.
- `sensors/HeadingFusion.kt` is gyro-first:
  - **Clean compass:** a 3 s warm-up, then a time constant that grows with elapsed time up to 120 s. This works as a running mean of the compass.
  - **Disturbed compass:** ψ is held, so only the gyro moves the heading.
  - **Compass clean again:** the filter re-seeds from it.
  - **Aligned:** the compass is ignored entirely.
  - A gyro frame restart (sensors re-registered) re-applies the alignment relative to the compass. It is marked approximate and the HUD asks for a re-align.
  - **Change from step 3:** the alignment does not decay back to the compass. It stays until the user clears it, because the gyro held yaw within 0.04° over four minutes in the measured test.
- `orbit/SkyTargets.kt` lists Align targets between 10° and 75° elevation:
  - Moon, Sun and planets come from Orekit DE-440.
  - The 41 stars brighter than V 1.9 plus Polaris use SIMBAD J2000 positions, run through Orekit's frames so precession is included.
  - Above 75° the azimuth is too sensitive to pointing error to be useful.
  - The UI uses only the Moon for now (after field testing, a short list of stars and planets was more confusing than useful). The other targets stay in `SkyTargets` for automatic alignment (D).
- The AR screen has an **Align** button. It reads "Align (compass off)" while the field is disturbed and "Aligned: Moon" once aligned. It opens an inline panel with no dialogs, toasts or confirmations:
  - **Text:** the current heading source, then live guidance and the az/el error to the Moon.
  - **Two buttons:** "Align with Moon" applies Δaz = Moon − boresight. "Use magnetometer" clears the alignment.
  - **Guard instead of a confirmation popup:** "Align with Moon" is enabled only when the Moon is at least 5° up and centred within 3° in elevation. Elevation comes from gravity and can be trusted.
  - Tapping Align again, or either button, closes the panel.
- **Moon marker:** a blue diamond sits where the current heading puts the Moon, whenever it's above the horizon. It's dim and unlabelled in normal use. While aligning it's bright, labelled, and an edge arrow points to it when off screen.
- **HUD:** a HEADING line shows the source (compass, gyro hold, or aligned on the Moon), the field verdict and its deviation from the model.

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
| **CelesTrak GP** (chosen) | Free, no account, HTTPS | The same 18 SDS elements as Space-Track, re-served with grouping. Updated about every 2 h. OMM in JSON/CSV/XML. |
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
   - **The loading bar becomes the "AR camera" toggle**, and **"Center on me"** appears in the top right.
6. **Shared layout, no overlaps.** Both screens use the same scheme:
   - **Top row:** an info panel on the left takes the remaining width (the satellite count on the globe, the debug HUD in AR), and the screen's action is on the right ("Center on me" / "Clear (N)").
   - **Bottom stack:** details or loading, then the **screen toggle**, in the same spot, size and style on both screens ("AR camera" / "Globe").
   - Locks are shared both ways because both screens read the same `SkyViewModel`.

### 11.1a Visibility gradient and layers (2026-10-17)
- **Green gradient:** golden-zone ("likely visible") satellites are shaded from dull to bright green by how easy they are to see for someone standing beneath them. The score is
  `brightness × (0.5 + 0.5 × darkness)`, where:
  - **brightness** maps the estimated overhead magnitude (§12.2: 1000-km magnitude by name, altitude range, diffuse-sphere phase) from magnitude 6 to 1 down to magnitude 2;
  - **darkness** maps the Sun's elevation at that ground point from −6° (just dark enough) to −18° (fully dark).
  Orange (likely visible and in the camera view) uses the same gradient.
- **Layers menu:** a **Layers** button under Center on me opens a dropdown of checkboxes, all on by default:
  - **Likely visible (green):** when off, those satellites fall back to their normal tier and the green twilight band on the ground is hidden too.
  - **Camera view (FOV cone).**
  - **Horizon wall** (the visibility wall).
  - **Satellites in view:** above the horizon, cyan and yellow.
  - **Satellites out of view:** dim.
  Green satellites stay visible even when their normal tier is hidden, so turning everything else off leaves just the likely-visible sky. Choices reset to all-on at launch.

### 11.2 Rendering: globe.gl in a WebView

| Option | Verdict |
|---|---|
| **globe.gl + three.js in a WebView** (chosen) | Open source (MIT). Gives orbit/zoom controls, a textured Earth, atmosphere glow, and path/label layers out of the box, and its own examples include a real-time satellite tracker. We bundle it offline, so it needs no network at runtime. |
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

`SkyViewModel.loading: StateFlow<LoadingState(stage, fraction, done)>` drives a native `ProgressBar` and label. When `done`, they're replaced by the **AR camera** toggle, and **Center on me** appears top-right.

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

## 12. Naked-eye visibility

*Proposed and implemented 2026-10-16 on the `satellite-visibility` branch.*

### 12.1 Criteria

A satellite can be seen with the naked eye when **all** of these hold:

| # | Condition | Why | Threshold used |
|---|---|---|---|
| 1 | **The satellite is sunlit** | It only shines by reflected sunlight. In the middle of the night most LEO satellites are in Earth's shadow. | Outside Earth's shadow (cylindrical model, R = 6,371 km) |
| 2 | **The observer's sky is dark** | The sky must be dark enough to see a faint moving point. | Sun at least **6° below** the observer's horizon (end of civil twilight). Visibility gets better through nautical (−12°) and astronomical (−18°) twilight. |
| 3 | **The satellite is high enough** | Near the horizon there's too much extinction, haze and obstruction. | Elevation **≥ 10°** |
| 4 | **It is bright enough** | The naked-eye limit for a moving point is about magnitude 4–5 in suburbs and about 6 under dark skies. | Estimated magnitude **≤ 5.0** counts as "likely visible" |

Conditions 1 and 2 together are why satellites are seen in the **hours after sunset and before sunrise**. The ground is dark, but a satellite a few hundred kilometres up is still in sunlight. Later in the night the satellites enter Earth's shadow too.

### 12.2 Brightness estimate

`m = M₁₀₀₀ + 5·log₁₀(range / 1000 km) − 2.5·log₁₀(sin φ + (π − φ)·cos φ)`

- **M₁₀₀₀** is the satellite's "1000-km magnitude": its brightness at 1,000 km range and a 90° phase angle.
- **φ** is the phase angle (Sun–satellite–observer). The last term is the diffuse-sphere phase law, normalised to zero at 90°. A satellite with the Sun behind the observer (φ → 0) is up to 1.2 magnitudes brighter.
- **The catalog has no per-satellite brightness**, so M₁₀₀₀ is a heuristic by name (published averages where they exist). These are rough estimates; real satellites flare and vary with attitude.

| Object | M₁₀₀₀ |
|---|---|
| ISS (ZARYA) | −1.3 |
| CSS (TIANHE) | 0.0 |
| HST | 2.2 |
| Rocket bodies (`R/B`) | 4.0 |
| STARLINK | 5.5 (Mallama et al.: about 5.5 for post-VisorSat / Mini at 1000 km) |
| ONEWEB | 7.5 |
| Debris (`DEB`) | 8.0 |
| Everything else | 6.0 |

### 12.3 The golden zone (globe)

On the globe, a satellite is in the **golden zone** when it is sunlit **and** the ground directly beneath it is past civil twilight (the Sun is more than 6° below the horizon there). Anyone under it, given a clear sky, has it in their good-viewing window.

The golden zone always hugs the night side of the **terminator** (the sunset/sunrise line). It's bounded on one side by the darkening sky and on the other by Earth's shadow reaching the satellite's altitude, about 20–30° past the terminator for LEO. These satellites are drawn **green wherever they are on the globe**, overriding the other tiers, or **orange** (green + yellow) when they're also inside the user's camera view. A legend in the globe's top-left panel explains both colours.

### 12.4 Day / night globe

- The globe shader blends the **Blue Marble day texture** and the **Earth-at-night city-lights texture** by the Sun direction, with a soft transition. There's no terminator glow; it made the day side too busy.
- The twilight band (Sun 6–18° below the horizon) gets a faint green wash, so the ground zone under the green satellites is visible.
- The Sun position comes from Orekit (`CelestialBodyFactory.getSun()`, using the bundled DE-440 ephemeris). It's computed once per globe pass and sent in the `/api/positions` header, now `Float64 epoch, Float64 sunX/Y/Z (unit, ECEF)`, then the per-satellite Float32 state.

### 12.5 AR screen

- **Dots:** satellites that pass all four criteria for **you** are drawn **green** (the same green as the globe). The rest stay white.
- **Info card:** a visibility line, e.g. `Likely visible · mag 3.8`, or the reason it isn't (`In Earth's shadow`, `Sky too bright (Sun −2°)`, `Too low`, `Too faint · mag 6.4`).
- **HUD:** shows the Sun's elevation at the user and the sky state (day / civil / nautical / astronomical twilight / night).

### 12.6 Testing
- **Unit tests:** shadow test geometry, phase angle, the magnitude formula (equal to M₁₀₀₀ at 1,000 km and 90°), the twilight classification, and the Sun direction vs. a known solstice/equinox declination.
- **Field test:** compare predicted ISS and Starlink visibility against Heavens-Above for the same evening.

## 13. Lock-on view and Analyze

*Implemented 2026-10-16 on the `satellite-analysis` branch.*

### 13.1 Camera hole
When a **locked** satellite is in the reticle, the overlay opens a clear window at its position (26 dp radius, animated open over 250 ms) so the real satellite can be seen in the camera feed. Markers, the orbit line, dots and labels are all cleared inside it.

- **How:** while anything is locked, the overlay draws into an offscreen layer (`saveLayer`). It then clears a circle with `PorterDuff.Mode.CLEAR`, restores the layer, and draws a thin ring with four aiming ticks and the name in the lock colour *outside* the hole.

### 13.2 Analyze
With a locked satellite in the reticle, an **Analyze <name>** button appears above the info card. It opens a full-screen panel with:

- **Photo:** the satellite's Wikipedia thumbnail (about 330 px). Satellites without their own article (e.g. individual Starlinks) get a *representative* image of their constellation or object class, labelled as such.
- **Right now:** altitude, range, speed, rising/setting, az/el and the naked-eye estimate (§12), live from the tracker.
- **Catalog:** type, status, owner, launch date and site, period, inclination, perigee × apogee, and a size class from radar cross-section.
- **About:** the Wikipedia intro text and a "Read more on Wikipedia" link.

**Data sources.** Everything is online, fetched only when Analyze is tapped, and nothing is stored on the device:

| Request | Purpose | Typical size |
|---|---|---|
| `celestrak.org/satcat/records.php?CATNR=<id>&FORMAT=JSON` | Catalog facts | under 1 KB |
| `db.satnogs.org/api/satellites/?norad_cat_id=<id>` | Aliases, operator, countries, website, photo | about 1 KB (+ photo about 45 KB) |
| Wikipedia `list=search` on the satellite's names | Article when Wikidata has none | about 1 KB each, at most 3 |
| Wikidata search `haswbstatement:P377=<id>` (P377 = satellite catalog number) | Find the satellite's Wikidata item | under 1 KB |
| Wikidata `wbgetentities&props=sitelinks&sitefilter=enwiki` | Its English Wikipedia title | under 1 KB |
| Wikipedia REST `page/summary/<title>` | Description, intro, thumbnail URL | about 3 KB |
| Thumbnail | Photo | about 20–60 KB |

- **Speed and fallbacks:** the SATCAT and Wikidata/Wikipedia chains run in parallel. When Wikidata has no item for the NORAD id, the article comes from a constellation table (Starlink, OneWeb, Iridium, Globalstar, Planet "Flock", Spire "Lemur", Kuiper, …) or the object class ("Multistage rocket" for R/B, "Space debris" for DEB).
- **Caching:** results are kept in an in-memory LRU (32 satellites) for the session. A total failure (e.g. offline) isn't cached, so retrying works.
- **Etiquette:** requests carry a descriptive User-Agent with a contact URL, as Wikimedia asks. The SATCAT lookups are single, user-triggered requests, well inside CelesTrak's usage policy.
- **No new libraries:** it reuses OkHttp, kotlinx.serialization and `BitmapFactory`.

### 13.3 Zoom lens
With a locked satellite centered, a **vertical slider** appears on the right edge (1× at the bottom, 8× at the top, exponential so each step feels the same). Only the hole is magnified; everything outside stays at 1×.

- **Where the detail comes from:** CameraX binds a second use case beside the preview, a **full-resolution 4:3 `ImageAnalysis` stream** (target 4032 × 3024) with the same field of view. The preview stream is only about 1080p and is already upscaled about 2.2× to fill the screen, so the lens samples the high-res stream for real extra detail. On a Pixel 6 Pro that's roughly 3× more than the preview before it becomes plain enlargement.
- **Cost:** each frame, the analyzer converts **only the crop under the hole** (at most 320 × 320 px) from YUV to an upright ARGB bitmap. When no zoom is requested it closes frames immediately.
- **Mapping:** `ZoomCrop` maps the screen point to sensor pixels through PreviewView's FILL_CENTER scaling and the sensor rotation (unit-tested for 0/90/180/270°).
- **Lens size:** the hole widens from 26 dp to 60 dp radius as you zoom in to 2×, so the lens is big enough to be useful. It resets to 1× whenever the hole closes.
- **Caveat:** the lens is centered on the *predicted* position. If the pointing error is larger than the lens' field of view at high zoom, the real satellite can sit outside it. Zoom out a little to find it.
- **Fallback:** devices that can't stream preview + full-res analysis together fall back to preview only, with the zoom disabled.

### 13.4 Better satellite info: options

Current coverage is limited because Wikidata only tags well-known satellites with a NORAD number (P377), and about 60% of the catalog is Starlink, which only matches the constellation article.

| Source | Gives | Coverage (checked 2026-10-17) | Cost / constraints |
|---|---|---|---|
| **SatNOGS DB API** (`db.satnogs.org/api/satellites/?norad_cat_id=`) | Name aliases, **photo**, operator, countries, status, website, radio transmitters | ISS and amateur / small satellites (e.g. FOX-1B had a photo); **no Starlink** | Free, open (CC BY-SA), no key, about 1 KB |
| **Wikipedia full-text search** on the cleaned name or program (e.g. "Sentinel-2A", "NOAA 19", "Cosmos 2558") | Article + image when Wikidata has no NORAD link | Most named science / weather / navigation satellites | Free; needs a relevance check to avoid wrong articles |
| **Wikimedia Commons search** | Photos / renderings by program name | Good for major programs | Free; images vary in relevance |
| **GCAT** (Jonathan McDowell, planet4589.org) | Mass, dimensions, shape, program, category, manufacturer, comment, for **every** object incl. debris | Complete; updated daily | CC BY; but 5–19 MB TSVs, so it needs a tiny server-side index (e.g. a static per-NORAD JSON on GitHub Pages) |
| **ESA DISCOS** | Mass, shape, dimensions, cross-sections | Complete | Free account + personal token; not suited to embedding in an app |
| **LLM + web search** behind a small serverless proxy | A written summary with sources for *anything* | Best | Costs per lookup, needs a backend to hold the API key |
| Gunter's Space Page / N2YO / Heavens-Above | Rich pages and photos | Very good | No API; scraping copyrighted pages, so not recommended |

**Implemented (2026-10-17): SatNOGS + Wikipedia search.** The lookup chain is now:

1. **In parallel:** CelesTrak SATCAT, SatNOGS DB (capped at 4 s, because the volunteer-run server is sometimes unreachable) and Wikidata by NORAD number.
2. **Article:**
   - The Wikidata-linked article.
   - Otherwise, for anything that isn't a mega-constellation member (Starlink, OneWeb, Kuiper, Qianfan, Flock, Lemur, Iridium, Globalstar, Orbcomm), a **Wikipedia full-text search** on the CelesTrak name, the SatNOGS name and the aliases (at most 3 searches and 3 summary fetches). **Correct data beats any data**, so a hit has to pass all of these checks:
     - **Title:** matches once punctuation and case are dropped (*exact*), or is the program name plus a unit suffix of at most 2 characters (*program*, "SENTINEL-2A" ~ "Sentinel-2"). A parenthesised disambiguator is allowed only if it's space-related ("Aqua (satellite)"), never e.g. "Checkmate (comics)".
     - **Content:** the short description and intro must use spacecraft vocabulary, and are rejected outright if the description or first sentence marks fiction, media, people, etc. (whole-word, so "broadband" and "contractor" are fine).
     - **Catalog cross-check:** an exact article must mention the satellite's **SATCAT launch year**. That separates same-named spacecraft; a LEO "JUNO" would not get NASA's 2011 Jupiter probe. A program article must mention that year or no years at all, and is shown as general info ("About: MetOp"). If there's no SATCAT record to verify against, search results aren't used.
   - If nothing qualifies, the panel shows only the verified catalog and SatNOGS facts. *Bug fixed 2026-10-17: CHECKMATE 8 used to resolve to the DC Comics article.*
   - Otherwise, the constellation / object-class article.
3. **Photo:** the satellite's own article thumbnail, else the **SatNOGS photo**, else the general article's thumbnail. The panel captions SatNOGS and representative images.
4. **Mission section** from SatNOGS: aliases, operator, countries, status and a mission-website link.

Checked live: Sentinel-2A, NOAA-19, Landsat 9, Aqua and Terra get their own articles, and MetOp-B / Swarm A get their program articles. CHECKMATE 8, GENESIS, ATLAS and HAWK-1, whose names collide with comics, cars, anime, moons and aircraft, correctly get none. The NOAA blanket fallback was removed, because NOAA-20/21 are JPSS, not POES.

SatNOGS transmitter (radio frequency) data was left out: that endpoint was timing out when checked.

**Next step, if wanted:** a static **GCAT index** for physical specs (mass, dimensions, manufacturer) on every object, if a tiny hosted file is acceptable.

## 14. Milestones

0. **Fix the foundations.** (done) Permission flow, wait for a GPS fix before filtering, periodic recompute.
1. **Pointing.** (in progress) `AttitudeTracker` with rotation vector + declination, plus a debug HUD showing boresight az/el. *Done; still to do: a night-sky check against Polaris and the Moon.*
2. **Catalog.** (done) Room cache, LEO filter, refresh policy. *(Group tagging is deferred to the filters work.)*
3. **Overlay.** (done) CameraX preview, FOV, ARCore/SceneView dropped, projected markers, horizon.
4. **Interaction.** (done) Reticle focus, info card, orbit track, tap to lock multiple satellites with per-satellite colors, edge arrows, clear button.
5. **Polish.** (in progress) Sunlit/visible classification is done (§12); still to do: filters, calibration UX, trimmed Orekit data.
6. **Globe screen.** (done) Loading progress, 3D Earth with all LEO satellites, camera-view pyramid and locked satellites (§11).

## 15. Open questions for review

1. ~~**AR approach**~~ — decided: sensor AR with CameraX.
2. **Satellite set:** we now cache all active LEO (about 16k). Should the default *view* be filtered to curated groups (stations, visual, Starlink)?
3. **What to show:** everything above the horizon, or only naked-eye-visible satellites by default?
4. **Orekit:** keep it, given its 37 MB of data and heavy API, or switch to a small SGP4 library plus an analytic Sun model?
5. **Package/app ID:** is `com.jakedonnini.sattracker` fine?
6. **Min SDK:** currently 24. Any target devices to prioritize?

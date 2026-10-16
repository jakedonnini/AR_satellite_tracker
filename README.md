# Sat Tracker

An Android app that overlays low-Earth-orbit satellites on the live camera view. You point your phone at the sky to see what's overhead, where it's heading and what it is.

> **Status:** early prototype. The AR overlay works: it draws satellites above the horizon, and the one under the reticle gets an info card and its orbit path. Pointing accuracy still needs an outdoor night test. See [docs/DESIGN.md](docs/DESIGN.md).

## How it works

1. **GPS** locates the observer.
2. **CelesTrak** orbital elements are propagated with **SGP4** ([Orekit](https://www.orekit.org/)) to get each satellite's azimuth and elevation.
3. **Device sensors** give the camera's look direction.
4. The satellites in view are **projected onto the camera preview** with info cards.

## Project layout

```
app/src/main/java/com/jakedonnini/sattracker/
├── MainActivity.kt
├── data/       CelesTrak OMM client, Room cache, repository
├── orbit/      Orekit setup, OMM → TLE, SGP4 sky model
├── geometry/   Pure pointing math (boresight, az/el, separation)
├── camera/     CameraX preview + field of view
├── sensors/    Fused location, rotation-vector attitude
├── ar/         Sky → pixel projection, focus selection
└── ui/         SkyViewModel, AR overlay view, theme
app/src/main/assets/orekit-data/   Orekit reference data (leap seconds, EOP, ephemerides)
docs/                              Design docs
```

## Building

Requirements: Android Studio (or JDK 17+) and the Android SDK (compileSdk 35).

```bash
./gradlew assembleDebug        # build the APK
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew installDebug         # install on a connected device
```

The app needs a physical device with a camera, GPS, gyroscope and magnetometer. Emulators can't meaningfully test the pointing.

## Data sources

- Orbital elements: [CelesTrak GP data](https://celestrak.org/NORAD/documentation/gp-data-formats.php)
- Orekit data: [orekit-data](https://gitlab.orekit.org/orekit/orekit-data)

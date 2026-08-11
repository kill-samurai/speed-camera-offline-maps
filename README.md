# Speed Camera

A private, sideloadable Android camera app that:

- records video and optional microphone audio;
- reads live speed from Android GPS/location updates;
- shows the speed on the camera preview; and
- burns that speed into every recorded video frame;
- calculates driving routes with OpenStreetMap data through OSRM; and
- renders a transparent, heading-up route minimap and maneuver HUD into the preview and video.

Videos are saved in the device gallery under `Movies/SpeedCamera`. Location coordinates are not stored by the app; only the formatted speed visible in the pixels is recorded.

## Download the app

Download `speed-camera-latest.apk` from the repository's [Latest APK release](https://github.com/kill-samurai/speed-camera-offline-maps/releases/tag/latest-apk). The APK is rebuilt, signed, tested, and published automatically whenever the `main` branch changes.

Because all automated releases use the same private signing key and an increasing version code, a newer APK can update an earlier GitHub-built installation without uninstalling it first. Keep the local signing backup safe: losing it would prevent future builds from updating existing installations.

To request a build without changing any source files, install and authenticate the [GitHub CLI](https://cli.github.com/), then run:

```bash
./trigger-github-build.sh
```

The script dispatches the workflow on `main`. An alternate branch can be supplied as its first argument, for example `./trigger-github-build.sh my-branch`.

## Requirements

- Android Studio with JDK 17
- Android SDK 35
- A physical Android 10 (API 29) or newer device with a camera

## Build and sideload

1. Open this folder in Android Studio.
2. Let Gradle sync and install any requested SDK components.
3. Connect the phone with USB debugging enabled.
4. Select the phone and click **Run**, or use **Build > Build APK(s)** and install `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.
5. Grant camera, microphone, and precise-location permissions. GPS speed is most reliable outdoors while moving.

The default package is `com.example.speedcamera`. Change `applicationId` in `app/build.gradle.kts` before producing a long-term personal build if desired.

## Navigation

Tap **Destination** and enter either a street address or coordinates in `latitude,longitude` form. Address lookup uses Android's system `Geocoder`. The route request is sent to an OSRM-compatible server and returns OpenStreetMap-based route geometry and maneuvers.

Address searches use the public OpenStreetMap Nominatim service, with the current GPS position used as a geographic preference. Direct `latitude,longitude` input remains available. Searches are user-triggered, cached for the app session, and throttled to respect the service's one-request-per-second policy.

As-you-type address suggestions use Photon, are requested only after at least three characters and a short typing pause, and are biased toward the current GPS position. Selecting a suggestion passes its coordinates directly to routing. Nominatim remains the full-address fallback because its public usage policy does not permit autocomplete.

The minimap uses a navigation arrow by default. The Marker control can select a custom image through Android's document picker or restore the default. The selected image is stored across app launches and appears in both the live camera overlay and recorded video. A square transparent PNG or WebP works best.

The minimap shows surrounding streets beneath the route. When a regional offline package is installed, street geometry is read from the phone. Otherwise, the app loads OpenStreetMap standard raster tiles and caches them using Android's HTTP cache. Both versions remain translucent and rotate into the heading-up view.

The Camera control lists the recording resolutions and frame-rate ranges reported by the active back camera. The selected values persist across launches and rebind the CameraX preview and recorder when applied. Frame rate defaults to Automatic, and settings cannot be changed during an active recording.

During recording, the Camera and Marker controls are hidden, the record/stop control becomes red with a white outline, and a Pause control is shown. Pause and Resume continue writing to the same video file. Recording state is communicated by the controls rather than a separate “Recording…” status message.

After a destination is successfully routed, the Destination control is hidden until navigation is stopped or the destination is reached.

The default routing endpoint is the public OSRM demonstration server. It is suitable for personal testing and light, non-commercial use, but has no availability guarantee. Before publishing to a broad audience, self-host OSRM or configure production geocoding and routing providers in `OsmGeocodingClient.kt` and `OsrmRoutingClient.kt`.

The minimap, route, current position, heading, and next maneuver are also burned into the video. OpenStreetMap and routing attribution remains visible in both the preview and recording.

## Offline maps

Tap **Offline Maps** to download a regional package directly on the phone. The first published region covers the Dominican Republic and offers:

- **Map only:** 25.5 MB download, 54.7 MB installed. Adds offline surrounding streets to the minimap.
- **Map + search + routing:** 76.7 MB download, 194.0 MB installed. Also adds offline destination search and turn-by-turn route calculation.

Before starting, the app shows the package's exact download and installed sizes, the temporary space needed during installation, and the device's available space. Downloads use Android's system Download Manager, can continue outside the app, and are checked against a SHA-256 checksum before installation. An installed region can be removed from the same screen.

If the full package is installed, the app uses it automatically when there is no validated internet connection. It also falls back to it when the online routing request fails. An active route is saved locally so it can survive an app-process restart. GPS positioning and speed measurement continue without internet access.

Packages are published as GitHub Release assets in the public [speed-camera-offline-maps repository](https://github.com/kill-samurai/speed-camera-offline-maps). The app reads that repository's `catalog.json`, so a later regional package can be published without rebuilding the APK. See [tools/offline-packager/README.md](tools/offline-packager/README.md) for the reproducible package-generation and validation commands.

### Emulator navigation test

1. Open the emulator's **Extended controls > Location** panel.
2. Send a starting point and wait for the app to show `0 km/h`.
3. Tap **Destination** and enter nearby coordinates or an address.
4. Play an emulator route that follows the calculated path.

The app automatically recalculates when the GPS position is more than 75 metres from the route, with a 30-second throttle to protect the public routing service.

## Notes

- The speed uses the device's location-reported speed and is lightly smoothed to reduce GPS jitter.
- The display currently uses kilometres per hour.
- There is no background location access: location updates stop when the app is not visible.
- The public OSRM endpoint is not a production SLA and may rate-limit or become unavailable.
- If microphone permission is denied, video still records silently.

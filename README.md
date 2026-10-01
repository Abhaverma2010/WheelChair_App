# Wheelchair Bluetooth App

Android app for controlling an ESP32-based voice-controlled wheelchair over
Bluetooth Classic SPP, with live fall-detection alerts and GPS location
display.

- `app/` — the Android Studio project (Java, Bluetooth Classic SPP,
  compileSdk 34, minSdk 24).
- `firmware/esp32_wheelchair_bluetooth.ino` — matching ESP32 Arduino
  firmware: Bluetooth Classic motor control, MPU6050 fall detection,
  NEO-6M GPS, and SIM800L SMS alerting.

## Getting a ready-made APK

Every push to `main` (and every manual run) builds a debug `.apk` via
GitHub Actions. To get it:

1. Go to the **Actions** tab of this repo.
2. Open the latest **Build APK** workflow run (green check).
3. Scroll to **Artifacts** and download `wheelchair-app-debug-apk`.
4. Unzip it — you'll get `app-debug.apk`. Transfer it to your phone and
   install it (you'll need to allow "install from unknown sources" since
   it isn't from the Play Store).

## Building locally instead

Open the `app/` project root in Android Studio and run it, or from the
command line with the Android SDK installed:

```
./gradlew assembleDebug
```

(No `gradlew` wrapper is checked in — generate one with
`gradle wrapper --gradle-version 8.4`, or just use a system `gradle` 8.4
install, matching what the CI workflow does.)

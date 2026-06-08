# Markera

Kotlin Multiplatform + Compose Multiplatform app that detects bullet-hole
positions in a camera-captured target image (YOLOv8 ONNX) and draws them on
the frame. Scores are entered manually via the on-screen pickers.

The feature was extracted from [webshooter](https://github.com/) and
collapsed into a single `:composeApp` KMP module with `commonMain`,
`androidMain`, and `iosMain` source sets.

## Modules / source sets

```
composeApp/
  src/
    commonMain/     Pure-Kotlin vision algorithms + ViewModel + theme
    androidMain/    MainActivity, MarkeraScreen, CameraX preview,
                    ONNX Runtime HoleDetector, Material You dynamic colors
    iosMain/        MainViewController placeholder + HoleDetector iOS stub
    commonTest/     detection post-processing unit tests
iosApp/             Placeholder README — scaffold the Xcode project later
```

## Building Android

The ONNX model `best.onnx` (~99 MB) is **not** committed to the repository.
Drop it into `composeApp/src/androidMain/assets/best.onnx` before building,
then:

```sh
./gradlew :composeApp:assembleDebug
```

Install on a device or emulator (API 24+) with a back camera.

## Running tests

```sh
./gradlew :composeApp:testDebugUnitTest
```

## iOS

`HoleDetector` is currently a stub on iOS (returns no detections). To
scaffold an Xcode project that consumes the shared framework, follow
`iosApp/README.md`.

## Notable conventions

- **No DI framework** — `HoleDetector` and the ViewModel are constructed
  inline in `MarkeraScreen`.
- **Android-only UI** — `MarkeraScreen` and friends live in `androidMain`
  (CameraX, `android.graphics.Bitmap`).
- **AGP 9.x opt-outs** — `gradle.properties` sets `android.builtInKotlin=false`
  and `android.newDsl=false` so the single `:composeApp` module can apply
  both `com.android.application` and `org.jetbrains.kotlin.multiplatform`.

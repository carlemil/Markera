# Markera

A **Kotlin Multiplatform** + **Compose Multiplatform** app for Android and iOS
that automatically scores precision-shooting series from a photo of the target.

![The Markera app](docs/images/hero.png)

## Goal

To **score precision-shooting series automatically** from an Android and iOS
app, then save the results to a local database and possibly a backend
([webshooter](https://github.com/)), with export/share to CSV, Excel, etc.

> Note: persistence, backend sync, and export are goals — they are not
> implemented yet. Today scores are entered manually via the on-screen pickers.

## Solution attempts

### 1. Train the model on holes

Train the model to detect holes, then compute the score from the distance
between the "centre" and the 6th/7th ring.

**Result:** Works poorly. Detecting the centre is very jittery and almost always
lands in the wrong place, which leads to incorrect scores. The 6th–7th ring
detection would also need to be improved.

### 2. Train the model to mark scores

Train the model to mark scores directly, not just holes.

**Result:** Worked well on the verification data, but poorly in practice. A
larger training dataset might help, but it is hard to create and very
time-consuming. The lower scores in particular — which are rarer in the training
data — were scored more or less at random.

### 3. Train on holes + a geometric centre (in progress)

Train the model on holes (no "vibe coding"), detect the digits, and draw two
lines — one vertical and one horizontal — so that they pass through the centre
of as many digit boxes as possible. The intersection of those lines is the
ellipse centre.

**Result:** Unknown — implementation in progress.

## Technical overview

The app is a single `:composeApp` KMP module with `commonMain`, `androidMain`,
and `iosMain` source sets (plus a small `:eval` module used to evaluate the
hole-detection model). Hole detection runs a YOLOv8 ONNX model. Scores are
currently entered manually via the on-screen pickers.

### Building for Android

The ONNX model `best.onnx` (~80 MB) is **not** committed to the repository. Drop
it into `composeApp/src/androidMain/assets/best.onnx` before building. The model
runs at a 1536×1536 input.

The app has two product flavors (dimension `source`):

- **`camera`** — the shipping app; live CameraX preview from the back camera.
- **`mock`** — an emulator/dev flavor that fakes the camera by replaying a
  random sample of dataset images bundled at build time (the `prepareMockFrames`
  Gradle task). Detection runs automatically on each loaded image, and it
  installs side by side via the `.mock` application-id suffix.

```sh
./gradlew :composeApp:installCameraDebug   # real camera, on a device
./gradlew :composeApp:installMockDebug     # emulator, no camera needed
```

Install on a device or emulator (API 24+); the `camera` flavor needs a back
camera.

### Running tests

```sh
./gradlew :composeApp:testDebugUnitTest
```

### iOS

`HoleDetector` is currently a stub on iOS (returns no detections). Follow
`iosApp/README.md` to scaffold an Xcode project that consumes the shared
framework.

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

Train the model on holes, then find the target centre geometrically from the
printed ring digits instead of asking the model for it:

1. Detect holes with the YOLOv8 ONNX model.
2. Read the ring digits with ML Kit's on-device text recogniser.
3. Keep only the **outer 6–9** labels — they sit at fixed positions and line up
   cleanly, whereas the inner 1–5 are closer to the centre and may not align.
4. Split the digits into a horizontal and a vertical row by orientation, pivoting
   on the **median** so a stray misread can't drag the split.
5. Fit a total-least-squares line through each row; their intersection is the
   centre. A sparse row still resolves from just two digits when they straddle
   the image centre along that axis and form a roughly level row.

**Result:** Promising where enough outer digits are readable — the centre lands
convincingly, including on tilted/perspective shots, and a stray digit no longer
throws it off. It still returns "no centre" on the hard frames (heavily-pasted
close-ups where a whole digit row is unreadable). Validated by an on-device
harness that renders annotated 3×3 mosaics over the dataset for eyeballing.
Turning the centre into an automatic score — and detecting the black ellipse
(the 6/7 ring boundary) to recover scale — is the next step.

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

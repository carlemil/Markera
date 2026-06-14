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

## Approaches we've tried

Automatic scoring needs two things from the photo: the **centre** of the rings
and the **scale** (where each ring is). Below is what we've tried for each, what
failed and why, and what worked.

### Detect holes, score against a detected centre — failed

Train a model to find the bullet holes, then score each hole from its distance
to the centre and the 6/7 ring.

**Why it failed:** the centre estimate was very jittery and usually landed in the
wrong place, so the scores were wrong. It also leaned on a 6/7-ring detection
that wasn't good enough.

### Train the model to mark scores directly — failed in practice

Skip the geometry and train the model to output the scores.

**Why it failed:** good on the held-out validation images, poor in real use. The
rarer low scores were essentially guessed, and growing the dataset enough is slow
and hard.

### Centre from the printed digits — works

Read the ring digits with on-device OCR (ML Kit), keep the outer **6–9** (they
sit at fixed, well-aligned positions; the inner 1–5 can be off), split them into
a horizontal and a vertical row, fit a line through each, and intersect.

**Why it works:** the digit rows are physically anchored to the true centre. Made
robust with a **median-based** row split (a stray misread can't drag it) and a
relaxed rule that accepts a two-digit row only when the pair straddles the centre.
It returns "no centre" — on purpose, rather than guess — when the black is so
heavily pasted that the 6–9 digits can't be read.

### Black 6/7 ring: ellipse from the dark blob — partly worked

Otsu threshold → connected-component dark blob → ellipse from the blob's pixel
moments (a tilted circle projects to an ellipse, so an ellipse is the right
shape).

**Why it fell short:** it fit the *filled region*, not the edge, so pasters that
bloat or dent the blob pulled it off, and it sometimes locked onto a small
cluster of dark pasters. An "expected size ≈ viewfinder circle" prior removed the
worst false positives but didn't fix the region-vs-edge mismatch.

### Black 6/7 ring: fit to the edge — better, not enough

Sample the black→white edge directly (radial scan, then gradient edges + RANSAC)
and fit a conic to those points.

**Why it's still short:** a first version latched onto the wrong edge — printed
numbers and the paper edge *outside* the black — and produced garbage; anchoring
the search to a profile-estimated rim radius fixed that. The RANSAC version
(keeping only radially-outward edges, contrast-weighted, with a coverage check)
is precise on well-framed targets and robust to interior pasters, but it can fit
the *wrong concentric ring*, and cut-off targets fail. Only ~30% of fits land on
the true 6/7 ring; we need ~95%.

### Black 6/7 ring: let the digits identify the ring — in progress

The rings are concentric, equally-spaced circles, so under mild perspective they
project to ellipses that share a centre, rotation and aspect ratio (scaled
copies — *not* confocal). The already-detected 6–9 digit boxes give the centre
and, through their labelled radii, the ring spacing — so we can predict exactly
where the 6/7 boundary is and which detected ellipse it should be, then snap that
to the real edge for precision. Self-calibrating from the digits, with no
hard-coded target spec. Currently being wired into the on-device pipeline.

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

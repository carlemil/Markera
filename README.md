# Markera

A **Kotlin Multiplatform** + **Compose Multiplatform** app for Android and iOS
that automatically scores precision-shooting series from a photo of the target.

The app scoring a target in real time:

<video src="https://github.com/carlemil/Markera/raw/master/docs/videos/demo.mp4" controls width="360">
  Your browser can't play the embedded video — <a href="docs/videos/demo.mp4">download it here</a>.
</video>

<video src="https://github.com/carlemil/Markera/raw/master/docs/videos/markera-hero.mp4" controls width="640">
  Your browser can't play the embedded video — <a href="docs/videos/markera-hero.mp4">download it here</a>.
</video>

## Goal

To **score precision-shooting series automatically** from an Android and iOS
app, then save the results to a local database and possibly a backend
([webshooter](https://github.com/)), with export/share to CSV, Excel, etc.

> Note: scoring is now computed automatically from the photo (see *Current
> pipeline* below) and pre-fills the on-screen pickers, which stay editable.
> Persistence, backend sync, and export are still goals — not implemented yet.

## Current pipeline (what's in use)

For each captured frame the app:

1. **Detects the bullet holes** — a YOLOv8 ONNX model.
2. **Finds the centre** — OCRs the ring digits and intersects the 6–9 digit rows.
3. **Locates the 6/7 ring** — seeds a circle from the digits and snaps it to the
   black→white rim, giving the perspective-tilted ellipse (scale + perspective).
4. **Scores each hole** — undoes the perspective with the ellipse, measures the
   distance from the centre in mm against the target spec, and assigns a ring
   with edge gauging. The top hits pre-fill the five score pickers, each hole is
   labelled with its value, and the series total is shown.

A "radar" scanning overlay animates over the frozen frame while the (slow) hole
model runs — its sweep springs from the detected centre and rides the detected
6/7 ellipse.

## Approaches we've tried

Automatic scoring needs three things from the photo: the **centre** of the
rings, the **scale** (where each ring is), and the **hole positions**. Below is
what we've tried for each — what failed and why, what worked, and what is
actually used today.

### Detect holes, score against a detected centre — early version failed

Train a model to find the bullet holes, then score each hole from its distance
to the centre and the 6/7 ring.

**Why the early version failed:** the centre estimate was very jittery and
usually landed in the wrong place, so the scores were wrong. It also leaned on a
6/7-ring detection that wasn't good enough.

**Status — the hole detector is in use; this scoring shortcut was replaced.**
The YOLO hole detector is still the first step of the pipeline. What failed was
scoring against that early, unreliable centre/ring; the digit-based centre and
rim-based 6/7 ellipse (below) fixed the inputs, so geometric scoring now works.

### Train the model to mark scores directly — not used

Skip the geometry and train the model to output the scores.

**Why it failed:** good on the held-out validation images, poor in real use. The
rarer low scores were essentially guessed, and growing the dataset enough is slow
and hard. **Status — not used;** abandoned in favour of geometric scoring.

### Centre from the printed digits — works, in use

Read the ring digits with on-device OCR (ML Kit), keep the outer **6–9** (they
sit at fixed, well-aligned positions; the inner 1–5 can be off), split them into
a horizontal and a vertical row, fit a line through each, and intersect.

**Why it works:** the digit rows are physically anchored to the true centre. Made
robust with a **median-based** row split (a stray misread can't drag it) and a
relaxed rule that accepts a two-digit row only when the pair straddles the centre.
It returns "no centre" — on purpose, rather than guess — when the black is so
heavily pasted that the 6–9 digits can't be read. **Status — in use.**

### Black 6/7 ring: ellipse from the dark blob — not used

Otsu threshold → connected-component dark blob → ellipse from the blob's pixel
moments (a tilted circle projects to an ellipse, so an ellipse is the right
shape).

**Why it fell short:** it fit the *filled region*, not the edge, so pasters that
bloat or dent the blob pulled it off, and it sometimes locked onto a small
cluster of dark pasters. An "expected size ≈ viewfinder circle" prior removed the
worst false positives but didn't fix the region-vs-edge mismatch. **Status — not
used** (superseded by the rim-edge fit).

### Black 6/7 ring: fit to the edge with RANSAC — not used standalone (machinery reused)

Sample the black→white edge directly (radial scan, then gradient edges + RANSAC)
and fit a conic to those points.

**Why it's short on its own:** a first version latched onto the wrong edge —
printed numbers and the paper edge *outside* the black — and produced garbage;
anchoring the search to a profile-estimated rim radius fixed that. The RANSAC
version is precise on well-framed targets and robust to interior pasters, but it
can fit the *wrong concentric ring*, and cut-off targets fail. Only ~30% of fits
landed on the true 6/7 ring; we need ~95%. **Status — not used as a standalone
detector, but its radial edge-sampling and robust ellipse fit are reused** for
the rim-snap step of the method below.

### Black 6/7 ring: digits locate it, the rim shapes it — works, in use

The rings are concentric, equally-spaced circles; under perspective the 6/7
boundary projects to a tilted ellipse. The 6–9 digit boxes give the centre
(above) and, via their labelled radii and the **known target spec** (25 mm rings
on a 100 mm black 6/7 radius → ring-width fraction `q = 0.25`), a robust estimate
of the 6/7 radius: the **median** of each digit mapped to the boundary, which
ignores OCR misreads. That gives a circle seed at the digit centre; the ellipse
**shape** is then fit to the actual black→white rim (two-pass radial edge scan +
robust ellipse fit), which follows the real perspective. Using the digit
*labels* guarantees we land on the 6/7 ring, not another concentric ring.

**What changed (and why):** an earlier version derived the whole ellipse — centre,
axes and tilt — from the mapped digit points and self-calibrated `q` with a
sweep. It worked on well-framed dataset photos (38/38 in `BlackRing67Test`) but
broke at high camera tilt: the free `q`-sweep pushed an inner digit ~2× too far
out, and a 5-DOF conic fit on a few, often one-sided digit points degenerated
into slivers and drifted off-centre, amplifying misreads. Fixing `q` from the
spec and taking the **shape from the rim** (digits only for centre + radius
seed) is stable across tilt. **Status — in use.**

### Automatic scoring from the geometry — works, in use

With the centre, the 6/7 ellipse, and the hole boxes, each hole is scored: rotate
its offset onto the ellipse axes and stretch the short axis to undo perspective,
convert pixels→mm against the spec (black 6/7 edge = 100 mm radius, a ring every
25 mm out to ring 1 at 250 mm, inner-ten within 12.5 mm), and **gauge by the
hole's edge** — a shot whose edge breaks a line counts the higher ring. The top
hits (inner-X first, then highest ring, then nearest) pre-fill the five pickers
(still editable), each hole is labelled with its value above its box, and the
series total is shown. **Status — in use.**

## Technical overview

The app is a single `:composeApp` KMP module with `commonMain`, `androidMain`,
and `iosMain` source sets (plus a small `:eval` module used to evaluate the
hole-detection model). Hole detection runs a YOLOv8 ONNX model on the **default
CPU execution provider** — XNNPACK's fp16 path caused intermittent native
crashes in `OrtSession.run` on-device and was slower on the test hardware.
Detection is guarded against concurrent runs (the native inference can't be
aborted), and the detect button is disabled while a scan is in flight. Scores
are computed automatically and pre-fill the on-screen pickers, which stay
editable.

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

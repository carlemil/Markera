# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Markera is a Kotlin Multiplatform + Compose Multiplatform app (Android + iOS) that
scores precision-shooting series from a photo of the target. See `README.md` for the
goal and a history of detection approaches tried (and which are in use vs. abandoned).

## Build & test commands

Tasks are **product-flavored** (dimension `source`: `camera` and `mock`), so the
generic `debug` task names are ambiguous — always name the flavor:

```sh
./gradlew :composeApp:assembleCameraDebug      # real-camera app (needs a back camera)
./gradlew :composeApp:assembleMockDebug        # emulator/dev: replays dataset images
./gradlew :composeApp:installCameraDebug
./gradlew :composeApp:installMockDebug

./gradlew :composeApp:testCameraDebugUnitTest  # JVM unit tests (NOT testDebugUnitTest — ambiguous)
# single test class:
./gradlew :composeApp:testCameraDebugUnitTest --tests "se.kjellstrand.markera.vision.HitScoringTest"
```

Instrumented (`androidTest`) tests don't accept `--tests`; select with the runner arg,
and pin to one device with `ANDROID_SERIAL` (the task runs on every connected device,
including emulators):

```sh
ANDROID_SERIAL=<serial> ./gradlew :composeApp:connectedCameraDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=se.kjellstrand.markera.BlackRing67Test
```

Some instrumented tests (`BlackRing67Test`, `CentreMosaicTest`) read images from the
device at `/data/local/tmp/ring-eval/` (with an `index.txt`) and write annotated
overlays to the app's `filesDir`, pulled back via `run-as`.

The `:eval` module is a JVM tool that evaluates the hole-detection ONNX model against
dataset images (the `/eval` project skill drives it):

```sh
./gradlew :eval:test --tests "eval.HoleDetectionMosaicTest" --rerun-tasks \
  -Dmosaic.seed=42   # optional; also -Dmosaic.images=<dir>, -Dmosaic.model=<path>
```

It compiles the app's real `vision/` sources straight from `commonMain` (excluding the
expect/actual platform files, with a desktop ONNX Runtime standing in), so edits to
`vision/` affect both the app and `:eval`. The training dataset lives outside the repo
at `D:/ml/holes/dataset/images/train` (the default `mosaic.images`).

Project skills exist for the routine workflows: `/deploy` (build + adb install/launch on
the USB phone), `/eval` (detection-quality mosaic), `/release` (version bump + Play
internal track; only the `camera` flavor is ever released).

### Model asset (required to build/run)

`best.onnx` (a YOLOv8 export, ~80 MB) is **not** committed. Place it at
`composeApp/src/androidMain/assets/best.onnx` before building; the model runs at
1536×1536 input. Builds/detection won't work without it.

## Architecture

Single `:composeApp` KMP module — `commonMain` / `androidMain` / `iosMain`, plus
flavor source sets `androidCamera` and `androidMock`. iOS `HoleDetector`/`DigitDetector`
are stubs; Android is the working platform.

### Frame source is chosen at build time by the flavor

`FrameSource` (androidMain interface) is provided by a flavor-specific
`rememberFrameSource()`: `androidCamera/CameraFrameSource` (live CameraX preview) vs
`androidMock/MockFrameSource` (replays a random sample of dataset images bundled by the
`prepareMockFrames` Gradle task; auto-detects each frame, so the mock flavor runs the
full pipeline on an emulator with no camera). The mock app installs side by side via the
`.mock` applicationId suffix.

### The scoring pipeline (the core, in `vision/`)

Per frozen frame, run in two phases (`ScanPhase` GEOMETRY → HOLES, driven from
`MarkeraScreen`):

1. **Holes** — `HoleDetector` (ONNX YOLOv8) → `Detection` boxes.
2. **Centre** — `DigitDetector` (ML Kit OCR) reads ring digits; `estimateCentre`
   intersects the lines fit through the 6–9 digit rows.
3. **6/7 ring** — `fit67RingFromDigits` seeds a circle from the digit centre + a robust
   median radius, then `refine67ToEdge` snaps it to the black→white rim (a two-pass
   radial edge scan). This `FittedEllipse` supplies scale + perspective.
4. **Score** — `scoreHits` un-projects each hole via the ellipse, converts px→mm against
   the fixed target spec (black 6/7 edge = 100 mm, rings every 25 mm, inner-X ≤ 12.5 mm),
   and assigns a ring with edge gauging. Results pre-fill the editable pickers.

**Invariant:** the centre is *always* the digit-row intersection, never the ellipse
centre — the ellipse only provides scale/shape. Several `vision/` files
(`TargetCalibration`, `BlackRingCalibration`, `RansacEllipseFit`, dark-blob/`EllipseFit`
moment fit) are earlier approaches kept for reference but **not on the live path**; the
README's "Approaches" section says which is which.

`vision/` is pure-Kotlin `commonMain` (JVM-unit-tested: `HitScoringTest`,
`CentreEstimatorTest`, `DetectionPostProcessTest`). Platform edges are `expect`/`actual`:
`HoleDetector`, `DigitDetector`, `PlatformImage` (= `android.graphics.Bitmap` on Android).

### Detection runtime notes (hard-won)

- `HoleDetector` uses the **default CPU execution provider, not XNNPACK** — XNNPACK's
  fp16 path crashed natively in `OrtSession.run` and was slower on-device.
- The native `OrtSession.run` **can't be aborted**. `uiState.phase` is bridged from a
  StateFlow via `collectAsState` and lags a frame, so detection is guarded by a
  synchronous `AtomicBoolean` (set on the main thread before launch) to prevent
  concurrent runs; the scan button is disabled while a pass is in flight.

### Webshooter competition marking (`webshooter/` + `ui/competition/`)

The app replicates webshooter.se's mobile "markering" flow with the camera auto-scoring
as score entry. `webshooter/` (commonMain, JVM-unit-tested) holds the Ktor API client
(`WebshooterApi`, OAuth2 password grant against `https://test.webshooter.se/api/v4.1.9/`),
lean DTOs (`ignoreUnknownKeys`; booleans arrive as both `true/false` and `0/1` →
`LenientBoolean`), `laravelFormEncode` (the save/registration endpoints are Laravel
bracket-array form posts — `audit[shots][0]=X`), `ShotMapping` (picker 0..10 → shots,
11 → `"X"`), `MarkingLogic` (resume/skip/locked/isSelf decisions) and
`MarkingWizardViewModel` (plain class + `dispose()`, deliberately *not* an androidx
ViewModel so polling/claims die with the screen). Android side: `AppNavHost` (sealed-class
back stack, hoists the single `TargetScanController` + `FrameSource` above navigation),
`DataStoreTokenStore`, and the competition screens. Real captured API fixtures live in
`composeApp/src/androidUnitTest/resources/webshooter/`. The mock flavor runs the whole
wizard on an emulator against the test server ("Testa mobilregistrering", competition 244).

Gotcha: kotlinx-serialization omits fields equal to their defaults — the OAuth
`LoginRequest` fields must stay non-defaulted or the grant envelope silently drops.

### Series backend + auto-save (`server/`, `series/`)

`server/` is a **standalone Gradle project** (not in the root build, so it can
`docker build` without the Android SDK): Ktor + SQLite, exchanges a Google/Apple ID
token (`POST /auth/google|apple`) for an opaque session token, `POST/GET /series`.
`POST /auth/dev` exists only with `DEV_AUTH=true` (the mock flavor signs in with it).
It runs in Docker on the Mac mini (`ssh macmini`, Colima, `~/source/Markera/server`,
host port **8090** — 8080 there belongs to another site). `PLAN.md` holds the design
decisions and the still-open user actions (Google/Apple client ids).

App side: `series/` (commonMain, JVM-unit-tested) has `Caliber`, `SeriesApi`,
`BackendSessionRepository` and `SeriesRecorder`. `TargetScanController.onSeriesDetected`
feeds every scored scan to the recorder (hoisted in `AppNavHost`, exposed via
`LocalSeriesRecorder`): signed out → hint, caliber `-` → chooser dialog, else POST, then the
scanned frame goes up as a ≤1024 px JPEG (`POST /series/{id}/image`; a failed upload never
fails the series). The history screen (`ui/history/`) lists series with thumbnails.
The caliber chip lives in the shared `TargetScanner` viewport. Sign-in is a
flavor-specific `signInWithProvider` (camera: Credential Manager + `googleid`, needs
`markera.google.client.id` in `local.properties`; mock: dev endpoint). Backend URL is
the Gradle property `markera.backend.url` (BuildConfig). iOS has the same entry points
in `iosMain/series/` but no host app yet.

### UI

`MarkeraScreen` (androidMain) is the single screen: top bar (with a debug-overlay
toggle) → square `Viewport` (live preview or frozen frame + `DetectionOverlay`) →
results area (total badge, editable `ScorePickerRow`, actions). `DetectionOverlay`'s
`showDebug` gates the raw detection boxes/digit boxes/row-lines; the clean view shows
only ring + centre + hole markers + score labels. `topScores` are picker indices 0–10
plus 11 = inner-X. Theme is a deliberate dark, green-accented scheme (no dynamic color).

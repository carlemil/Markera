# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Markera is a Kotlin Multiplatform + Compose Multiplatform app (Android + iOS) that
scores precision-shooting series from a photo of the target. See `README.md` for the
goal and a history of detection approaches tried (and which are in use vs. abandoned).

## Build & test commands

```sh
./gradlew :composeApp:assembleDebug     # the app (needs a back camera)
./gradlew :composeApp:installDebug

./gradlew :composeApp:testDebugUnitTest # JVM unit tests
# single test class:
./gradlew :composeApp:testDebugUnitTest --tests "se.kjellstrand.markera.vision.HitScoringTest"
```

### Release build

`release` is minified + resource-shrunk (R8). Keep-rules live in
`composeApp/proguard-rules.pro` (only two blocks: readable stack traces, and
`ai.onnxruntime.**` for JNI; kotlinx-serialization ships its own consumer rules).
Mapping lands at `composeApp/build/outputs/mapping/release/mapping.txt` and rides
to Play inside the AAB, so the Play plugin needs no mapping config. Native symbol
tables (`ndk.debugSymbolLevel = "SYMBOL_TABLE"`) ride along too as
`BUNDLE-METADATA/com.android.tools.build.debugsymbols/` — that only works when
`android.ndkVersion` names an NDK that is actually installed (AGP's default may not
be, and then the extraction silently produces nothing).

Instrumented (`androidTest`) tests don't accept `--tests`; select with the runner arg,
and pin to one device with `ANDROID_SERIAL` (the task runs on every connected device,
including emulators):

```sh
ANDROID_SERIAL=<serial> ./gradlew :composeApp:connectedDebugAndroidTest \
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
internal track).

### Model asset (required to build/run)

`best.onnx` (a YOLOv8 export, ~40 MB, fp16 body with fp32 inputs/outputs — the
iOS ORT API has no fp16 tensors; `scripts/cast_model_io.py` makes one from an fp16
export) is **not** committed. Place it at
`composeApp/src/androidMain/assets/best.onnx` before building; the model runs at
1536×1536 input. Builds/detection won't work without it.

## Architecture

Single `:composeApp` KMP module — `commonMain` / `androidMain` / `iosMain`. iOS
`HoleDetector`/`DigitDetector` are stubs; Android is the working platform. The frame
fed into detection comes from `FrameSource` (androidMain) — `CameraFrameSource`, a live
CameraX preview, via `rememberFrameSource()`. `capture()` is a full-resolution
`ImageCapture` still (~3000² on the OnePlus 9 Pro), cropped by a 1:1 `ViewPort` to
exactly what the square preview shows; `previewView.bitmap` (a ~1440² screen render)
is only the fallback when `takePicture` fails. That same frame is what gets stored.

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
`composeApp/src/androidUnitTest/resources/webshooter/`. The test server's
"Testa mobilregistrering" (competition 244) is the wizard's dev target.

Gotcha: kotlinx-serialization omits fields equal to their defaults — the OAuth
`LoginRequest` fields must stay non-defaulted or the grant envelope silently drops.

### Series backend + auto-save (`server/`, `series/`)

`server/` is a **standalone Gradle project** (not in the root build, so it can
`docker build` without the Android SDK): Ktor + SQLite, exchanges a Google/Apple ID
token (`POST /auth/google|apple`) for an opaque session token, `POST/GET /series`.
`POST /auth/dev` exists only with `DEV_AUTH=true`.
It runs in Docker on the Mac mini (`ssh macmini`, Colima, `~/source/Markera/server`,
bound to 127.0.0.1:**8090** — 8080 there belongs to another site — and published as `https://markera.duckdns.org` by the Mac's host Caddy). `PLAN.md` holds the design
decisions and the still-open user actions (Google/Apple client ids).

App side: `series/` (commonMain, JVM-unit-tested) has `Caliber`, `SeriesApi`,
`BackendSessionRepository` and `SeriesRecorder`. `TargetScanController.onSeriesDetected`
feeds every scored scan to the recorder (hoisted in `AppNavHost`, exposed via
`LocalSeriesRecorder`). A scan is only *pending* while its frozen frame is on screen;
`recorder.commit(topScores)` (the "Spara" button in free marking, moving on from a lane in the
wizard) merges the picker values into the pending request via `withPicks` — so a `HoleDto`'s
`ring`/`innerTen` are what the user confirmed and `detectedRing`/`detectedInnerTen` what the
detector said (null for a hand-placed or typed-in hole) — then
saves it: signed out → toast, caliber `-` → chooser dialog, else POST, then the scanned frame
goes up as a ≤3072 px q90 JPEG (`POST /series/{id}/image`; a failed upload never fails the
series). A rescan `clear()`s the pending series. Save feedback is one toast (`AppNavHost`).
The history screen (`ui/history/`) lists series with thumbnails.

**Local cache (PLAN task 57b).** Nothing fetches per screen any more: `SeriesRepository`
(commonMain, hoisted in `AppNavHost` beside `SeriesServices`) is the single source —
`series: StateFlow<List<SeriesDto>>` read from **SQLDelight** (`series` + `sync` tables,
`commonMain/sqldelight/.../series/db/Series.sq`, the whole `SeriesDto` kept as `json`).
`refresh()` sends the stored `updatedAt` stamp as `GET /series?since=`, upserts what came
back and deletes the tombstones (no stamp yet → a full paged load); it returns the failure
instead of throwing, so offline keeps the cached rows. Writes go to the server first, the
cache after; `SeriesRecorder` saves through it and hands the uploaded JPEG straight to the
image cache (`cacheDir/series/<id>.jpg`, the `ImageCache` interface), so History never
re-downloads what the phone just took. History/Statistik/Detail read the flow (Detail looks
its series up by id); `refresh()` runs at startup, on those screens opening, and after
sign-in. Sign-out, account deletion and a different user `clear()` both tables and the
image dir. JVM-tested in `SeriesRepositoryTest` (in-memory SQLite + `ktor-client-mock`).
History's share action exports everything as one zip (`series.csv` + `holes.csv`,
semicolon/CRLF/BOM so Excel opens them, plus `images/<id>.jpg`): the CSV text is pure
`series/SeriesExport.kt`, the zip and `ACTION_SEND` are `series/SeriesExportAndroid.kt`,
written to `cacheDir/export` and shared via the `${applicationId}.fileprovider`
`FileProvider` (`res/xml/file_paths.xml`).
The caliber chip sits beside the total in the shared `TotalBadge`. Sign-in is
`signInWithProvider` (Credential Manager + `googleid`, needs
`markera.google.client.id` in `local.properties`). Backend URL is
the Gradle property `markera.backend.url` (BuildConfig). iOS has the same entry points
in `iosMain/series/` but no host app yet.

### UI

`MarkeraScreen` (androidMain) is the single screen: top bar (with a debug-overlay
toggle) → square `Viewport` (live preview or frozen frame + `DetectionOverlay`) →
results area (total badge, editable `ScorePickerRow`, actions). `DetectionOverlay`'s
`showDebug` gates the raw detection boxes/digit boxes/row-lines; the clean view shows
only ring + centre + hole markers + score labels. Tapping a missed hole on the frozen
frame adds it: `TargetScanController.addManualHit` scores that point with the same
geometry (`ManualHit.kt` holds the pure viewport→image and box-sizing maths) and it is
drawn orange, `manual = true`, so it saves with no `detected*` values.
`topScores` are picker indices 0–10
plus 11 = inner-X. Theme is a deliberate dark, green-accented scheme (no dynamic color).

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

### Tickets verification and test map

Follow [the project verification guide](docs/ticket-verification.md) for the
changed-area test map, final gate and artifact reuse rules. Development uses
targeted tests; independent final review retains the full relevant suite. When
Android tests and packaging are both required, use one PowerShell invocation:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest :composeApp:assembleDebug
```

Keep incremental outputs and daemons in normal iteration; no routine `clean`,
`--rerun-tasks` or daemon shutdown. Reuse a verified APK only with evidence for
the exact source, build inputs and artifact hash. Later fixes, changed integration
sources or configuration invalidate earlier verification; the guide details the
checks. Documentation-only changes follow the Tickets docs-only reference check.

### Release build

`release` is minified + resource-shrunk (R8). Keep-rules live in
`composeApp/proguard-rules.pro` (three blocks: readable stack traces,
`ai.onnxruntime.**` for JNI, and the `<init>` of Firebase `ComponentRegistrar`s;
kotlinx-serialization ships its own consumer rules).
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

Some instrumented tests read images from the device (with an `index.txt`) and write
annotated overlays to the app's `filesDir`, pulled back via `run-as`:
`BlackRing67Test.probeRings` reads `/data/local/tmp/ring-probe/` plus the app's cached
series images, `CentreMosaicTest` reads `/data/local/tmp/centre-eval/`.

The `:eval` module is a JVM tool that evaluates the hole-detection ONNX model against
dataset images (the `/eval` project skill drives it):

```sh
./gradlew :eval:mosaic \
  -Dmosaic.seed=42   # optional; also -Dmosaic.images=<dir>, -Dmosaic.model=<path>
```

The explicit `:eval:mosaic` task always runs fresh inference (including with a fixed seed),
while unchanged compilation remains reusable. It fails if the external model or images
are missing. The ordinary `:eval:test` suite keeps optional-asset skips.

It compiles the app's real `vision/` sources straight from `commonMain` (excluding the
expect/actual platform files, with a desktop ONNX Runtime standing in), so edits to
`vision/` affect both the app and `:eval`. The training dataset lives outside the repo
at `D:/ml/holes/dataset/images/train` (the default `mosaic.images`).

Project skills exist for the routine workflows: `/deploy` (build + adb install/launch on
the USB phone), `/eval` (detection-quality mosaic), `/release` (version bump + Play
internal track).

### iOS (simulator on the Mac mini)

No iPhone: the iOS app is built and verified on the simulator over ssh
(`ssh macmini`). `iosApp/README.md` holds the recipes; the short form:

```sh
sh scripts/mac-sync.sh                         # mirror HEAD + uncommitted files to the Mac
sh scripts/mac.sh 'sh gradlew -q :composeApp:linkDebugFrameworkIosSimulatorArm64'
sh scripts/mac.sh 'sh gradlew :composeApp:iosSimulatorArm64Test'   # the commonTest suite on K/N
sh scripts/mac.sh 'bash scripts/mac-build.sh'  # xcodegen + xcodebuild + simctl install/launch
```

`CONFIGURATION=Release` builds release; the optimised framework link needs the
4 GB `org.gradle.jvmargs` in `gradle.properties` (`sh gradlew --stop` after changing
it). The Mac clone needs `best.onnx` copied into `composeApp/src/androidMain/assets/`
too (xcodegen bundles it from there). Kotlin/Native rejects test names containing
`,` or `()`. Store uploads: `bash scripts/mac-beta.sh` (binary → TestFlight),
`bash scripts/mac-metadata.sh` (listing), the fastlane `release` lane (submit).

### Model asset (required to build/run)

`best.onnx` (a YOLOv8 export, ~40 MB, fp16 body with fp32 inputs/outputs — the
iOS ORT API has no fp16 tensors; `scripts/cast_model_io.py` makes one from an fp16
export) is **not** committed. Place it at
`composeApp/src/androidMain/assets/best.onnx` before building; the model runs at
1536×1536 input. Builds/detection won't work without it.

## Architecture

Single `:composeApp` KMP module — `commonMain` / `androidMain` / `iosMain`. All
screens, navigation (`AppNavHost`), view models, strings (`composeResources`, `Res`)
and the scoring pipeline live in commonMain; `AppServices` (services + model path +
share) is built by `MainActivity` / `MainViewController`. Platform edges are
`expect`/`actual`: `HoleDetector`, `DigitDetector`, `PlatformImage` (`Bitmap` /
`UIImage`), `rememberFrameSource`, `rememberCameraPermission`, `rememberSignIn`,
`encodeSeriesJpeg`/`decodeSeriesJpeg`. The frame fed into detection comes from
`FrameSource` via `rememberFrameSource()`. Android: `CameraFrameSource`, a live CameraX
preview; `capture()` is a full-resolution `ImageCapture` still (~3000² on the OnePlus 9
Pro), cropped by a 1:1 `ViewPort` to exactly what the square preview shows;
`previewView.bitmap` (a ~1440² screen render) is only the fallback when `takePicture`
fails. iOS: AVFoundation `CameraFrameSource` on a device (compile-only, no iPhone to
test on) and `PhotoPickerFrameSource` (PHPicker, "Välj foto") on the simulator or
wherever no camera exists. That same frame is what gets stored.

### The scoring pipeline (the core, in `vision/`)

Per frozen frame, run in two phases (`ScanPhase` GEOMETRY → HOLES, driven from
`MarkeraScreen`):

1. **Holes** — `HoleDetector` (ONNX YOLOv8; Android runs ORT directly, iOS hands the
   letterboxed tensor to the Swift `OrtHoleModel` through the `HoleModel` protocol) →
   `Detection` boxes.
2. **Centre** — `DigitDetector` (ML Kit OCR on Android, Vision `VNRecognizeTextRequest`
   on iOS) reads ring digits; `estimateCentre`
   intersects the lines fit through the 6–9 digit rows.
3. **6/7 ring** — `fit67Ring` (`RingProbe.kt`): four 50 px probe disks start on the
   digit-predicted boundary along the two digit rows and step until their dark share
   matches a disk centred on the rim; an ellipse goes exactly through them. No
   fallback: an implausible ellipse (checks include a size check against the
   `fit67RingFromDigits` median-radius seed) means no ring, so no scores — the user
   rescans or places holes by hand. This `FittedEllipse` supplies scale + perspective.
4. **Score** — `scoreHits` un-projects each hole through `TargetPlane` (a full
   homography from the four probes, or from the ellipse + the centre's polar line
   for old series; the drawn rings use the same map), converts px→mm against
   the fixed target spec (black 6/7 edge = 100 mm, rings every 25 mm, inner-X ≤ 12.5 mm),
   and assigns a ring with edge gauging. Results pre-fill the editable pickers.

**Invariant:** the centre is *always* the digit-row intersection, never the ellipse
centre — the ellipse only provides scale/shape. The README's "Approaches" section
records the earlier ring methods (their code was removed).

`vision/` is pure-Kotlin `commonMain` (JVM-unit-tested: `HitScoringTest`,
`CentreEstimatorTest`, `DetectionPostProcessTest`). Platform edges are `expect`/`actual`:
`HoleDetector`, `DigitDetector`, `PlatformImage` (= `android.graphics.Bitmap` on Android).

### Detection runtime notes (hard-won)

- `HoleDetector` uses the **default CPU execution provider, not XNNPACK** — XNNPACK's
  fp16 path crashed natively in `OrtSession.run` and was slower on-device.
- iOS runs the model through the ORT **C API** (`iosApp/iosApp/OrtRunner.c`, bridged to
  `OrtHoleModel`) with the **CPU arena and memory pattern off**: the Objective-C wrapper cannot
  switch them off, and with them an inference at 1536 px left the app at 1.3 GB and the next
  peaked at 2.5 GB (iOS kills the app). Lean, it peaks at about 1.3 GB and settles near 0.6 GB.
  `MemTest.swift` (DEBUG, `SIMCTL_CHILD_MARKERA_MEMTEST=1 xcrun simctl launch --console-pty …`,
  `=arena` for ORT's defaults) prints the footprint per inference.
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
11 → `"X"`) and `MarkingLogic` (resume/skip/locked/isSelf decisions); the
`MarkingWizardViewModel` lives in commonMain `ui/competition/` (plain class + `dispose()`, deliberately *not* an androidx
ViewModel so polling/claims die with the screen). `AppNavHost` (commonMain sealed-class
back stack, hoists the single `TargetScanController` + `FrameSource` above navigation)
reaches the wizard only through the `CompetitionHost` seam: androidMain
`ui/competition/CompetitionFlow.kt` owns the four steps, `SHOW_COMPETITION` and the
competition screens (`DataStoreTokenStore`, androidMain `webshooter/auth/`, is built in
`WebshooterServices`); iOS passes `null`. Real captured API fixtures live in
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
decisions and the still-open user actions.

App side: `series/` (commonMain, JVM-unit-tested) has `Caliber`, `SeriesApi`,
`BackendSessionRepository` and `SeriesRecorder`. `TargetScanController.onSeriesDetected`
feeds every scored scan to the recorder (hoisted in `AppNavHost`, exposed via
`LocalSeriesRecorder`). A scan is only *pending* while its frozen frame is on screen;
`recorder.commit(topScores)` (the "Spara" button in free marking, moving on from a lane in the
wizard) merges the picker values into the pending request via `withPicks` — so a `HoleDto`'s
`ring`/`innerTen` are what the user confirmed and `detectedRing`/`detectedInnerTen` what the
detector said (null for a hand-placed or typed-in hole) — then
saves it: caliber `-` still opens the chooser; otherwise the recorder encodes the frame and
writes the request and JPEG to durable local storage before any HTTP. Signed-out saves return
`SavedOnPhone`; signed-in saves send the same persisted `clientId`. Encoding errors are reported
and still keep the series metadata, as before. A rescan `clear()`s only the pending scan; a
committed save survives the next scan. Feedback is one toast (`AppNavHost`).
**False positives.** A `HoleDto` also stores the detector's box (`detectedW/H/Conf`, from `HitScore.boxW/boxH/conf`). A detection the user long-presses away on the scan screen goes into `MarkeraUiState.removedDetections` (emptied by a rescan/`clearResults`), is passed with `onSeriesDetected`, and `commit` appends it after `withPicks` as `HoleDto(deleted = true)` (the cache never holds it; the server keeps it, like the detail screen's deleted holes; the admin editor's removals do the same, and `replaceSeries` drops a stored deleted copy again when that detection is live in a later PUT, i.e. an Undo). `GET /admin/training-export.zip` (`server/.../TrainingExport.kt`, all users' non-deleted series with a photo) is the next training round's data: `images/<id>.jpg`, `labels/<id>.txt` (YOLO, confirmed holes, classes `Hole 0..10`, `Hole X`), `negatives.csv` (`image;cx;cy;w;h;conf;reason`: deleted / moved more than a diameter / scored 0 by hand), `nudges.csv` (moved less than a diameter) and `data.yaml`; a hole without a stored box gets a caliber-sized one. The privacy page says saved photos and holes may be used to improve hole detection.
The history screen (`ui/history/`) lists series with thumbnails.
`Screen.SeriesDetail` carries the walk order (`ids`, the filtered list it was opened from) and its
previous/next arrows step with `replace`, so Back still returns to the list.

**Local cache and durable local saves.** `SeriesRepository` is the single read source:
`series: StateFlow<List<SeriesDto>>` merges the SQLDelight server read cache (`series` + `sync`)
with `local_series`, newest first, also while signed out. Local DTOs use `id = -local_id` and
a transient `LocalMark` (`UNCLAIMED`, `QUEUED`, `REFUSED`). Their JPEGs and thumbnails use
`LocalImageStore` at durable `filesDir/local-series/`; synced images remain evictable at
`cacheDir/series/`. Negative IDs route image, edits, deletion, statistics and export locally.
Deleted-hole provenance and the persisted clientId survive local edits.

Every save first commits a local request and an atomic JPEG file. `send` serializes with refresh
and edits; acknowledgement atomically caches the server row and sets `server_id` before photo
upload. The photo-pending local row is hidden from the merge, but still supplies that positive
ID's image until upload succeeds. Success removes the local row/file. Offline, 5xx and 429 keep
queued rows for every refresh; other 4xx keep refused rows, retry once per repository/process
start and through Detail's Try again. Each row reports a refusal once. A 401 signs out without
deleting the owned local request/photo. `refresh()` sends only the current account's queue in
local-id order, then applies the server's `updatedAt` delta/tombstones to the server cache only.
Schema v4 retains the old outbox until restart-safe adoption moves each request and BLOB photo
to the local store; a failed file write never deletes the outbox row.

Sign-out, account deletion and account switch clear only `series`, `sync` and the synced image
cache. Local work survives. All sign-in providers go through the held-auth gate: the token is
not persisted or published while rows saved signed out or for another account need a decision.
The shared `LocalClaimController`/dialog offers Add, confirmed Delete, or Stay signed out;
dismiss means Stay. Add transfers every offered row in one transaction before publishing auth,
then refresh sends them. Partial failures stay in that account's queue. Own-account rows resume
silently. A row another account's server already acknowledged (only its photo pending) is never
offered: it waits for that account, so Add cannot copy it across accounts. Process death while the decision is open leaves no saved token and changes no rows.
Residual limit: a lost 201 under A followed by claiming the same request for B can create one
copy per account (the server deduplicates only within one user).

**Local data and system backups.** Uninstall/clear-data loses unsent work in the active app
storage. Android currently uses `android:allowBackup="true"` with default platform backup rules;
iOS uses Application Support for durable JPEGs and SQLDelight's application database directory,
without backup exclusion. OS backups/device migration may therefore include app data. Help and
privacy explicitly disclose that possibility and say not to rely on backups to recover unsent
series. This feature does not introduce or promise a cloud backup service. Privacy source prose
requires a separately authorized backend publish to become public; no API/deployment change is
needed by the clientId protocol.

History's share action exports everything as one zip (`series.csv` + `holes.csv`,
semicolon/CRLF/BOM so Excel opens them, plus `images/<id>.jpg`): the CSV text is pure
`series/SeriesExport.kt`, the zip and `ACTION_SEND` are `series/SeriesExportAndroid.kt`,
written to `cacheDir/export` and shared via the `${applicationId}.fileprovider`
`FileProvider` (`res/xml/file_paths.xml`).
The caliber chip sits beside the total in the shared `TotalBadge`. Sign-in is
`signInWithProvider` (Credential Manager + `googleid`, needs
`markera.google.client.id` in `local.properties`), with a two-button chooser that also offers
**Sign in with Apple**: Apple has no Android SDK, so `AppleSignIn.kt` opens the browser at the
backend's `/auth/apple/start?state=`, which mediates the whole OAuth flow and parks the session for
`POST /auth/apple/claim` (`state` = hex sha256 of a secret that stays on the device, so catching the
`markera://auth/apple` deep link buys nothing; the claim also needs the one-time `nonce` that only that deep
link carries, so an attacker who mails someone a start link for their own `state` cannot redeem it). Needs the five `APPLE_*`/`PUBLIC_URL` vars on the
server; without them that route answers 503 and only Google works. No account linking — Apple and
Google are separate users. Backend URL is
the Gradle property `markera.backend.url` (BuildConfig). iOS: Sign in with Apple in
`iosMain/series/SignIn.kt` with a dev-auth fallback when Info.plist `MarkeraDevAuth`
is set (Debug), backend URL from `MarkeraBackendUrl` (Debug `http://127.0.0.1:8091`,
Release production), token in NSUserDefaults, share via `UIActivityViewController`.

### Error reporting (`diag/`)

`ErrorLog` (commonMain) is the remote log: `breadcrumb(category, message)` leaves a trail,
`report(where, error, details)` sends a handled failure with that trail. Android wires it to
**Sentry** (`SentryErrorSink.kt`, started by hand in `MarkeraApplication`, which also catches
crashes/ANRs). iOS: `iosApp/iosApp/SentryReporter.swift` starts Sentry Cocoa (SPM) and implements
`NativeErrorReporter` (`iosMain/diag/IosErrorReporting.kt`, strings only), DSN from
`MARKERA_SENTRY_DSN` in `Local.xcconfig` (write `https:/$()/…`, since `//` starts a comment). `Throwable.userMessage()` reports every
toasted failure except IOExceptions (offline is not a bug), so new error paths that toast are
covered for free; sign-in leaves breadcrumbs per step. Every breadcrumb is also a Sentry log (Android `options.logs`, iOS `options.experimental.enableLogs`), sent whether or not an error follows. iOS sign-in errors carry the whole NSError chain in the exception message: Sentry scrubs the `kotlin_stack` extra. Never log tokens, names or e-mail.
DSN: `markera.sentry.dsn` in `local.properties` (empty = Sentry off). The Sentry Gradle plugin
is there only to upload the R8 mapping, and only when `sentry.properties` (gitignored:
`auth.token`, `defaults.org`, `defaults.project`) exists at the repo root. iOS dSYMs go up with
`sh scripts/sentry-dsyms.sh` after `mac-beta.sh` (it fetches the archive's dSYM zip from the Mac).

### UI

`MarkeraScreen` (commonMain) is the scan screen: top bar (with a debug-overlay
toggle) → square `Viewport` (live preview or frozen frame + `DetectionOverlay`) →
results area (total badge, editable `ScorePickerRow`, actions). `DetectionOverlay`'s
`showDebug` gates the raw detection boxes/digit boxes/row-lines; the clean view shows
only ring + centre + hole markers + score labels. Tapping a missed hole on the frozen
frame adds it: `TargetScanController.addManualHit` scores that point with the same
geometry (`ManualHit.kt` holds the pure viewport→image and box-sizing maths) and it is
drawn orange, `manual = true`, so it saves with no `detected*` values.
**Import images** (Home's menu → `ImportDialog` in `HomeScreen.kt`: Gallery / Files, an Auto
import switch, the caliber/tag chips) picks many images through `rememberImagePicker`
(`ImagePicker.kt`, expect/actual: Android `PickMultipleVisualMedia` / `OpenMultipleDocuments`,
`BitmapFactory` subsampled to a ~3000 px short side + `ExifInterface` rotation; iOS `PHPicker` /
`UIDocumentPickerViewController`, an ImageIO thumbnail ≤ 4096 px with the orientation baked in).
Each `PickedImage` is decoded only on its turn. `Screen.Import(ImportQueue)` is `MarkeraScreen`
with an `ImportFrameSource` (the picked image is the frame, so the normal crop/store/upload apply)
and its own view-model key; `ImportRunner` sets `SeriesRecorder.takenAt` (EXIF date taken via
`exifInstant`, else now) and scans. Manual: Save/Skip move on. Auto: `importDecision` saves what
scored, holds the rest, and those are reviewed by hand after the pass; one summary toast, back to Home.
A caliber `-` is asked for before the first scan (a commit waiting on the dialog would be dropped by
the next scan's `clear()`).
Every screen but Home uses the one `AppTopBar` (`ui/AppTopBar.kt`): back button (left of the menu
button, whenever the screen has a back), menu button, then title; back is that button plus system back.
Top-bar actions go in the one `AppMenu` (FieldShootingTimer's speed dial; `ui/AppMenu.kt`,
drawn by `MenuOverlay` at the nav root), which always ends with Suggestion and Settings (`ui/settings/`:
language + light/dark theme, applied in `MarkeraApp`; a language change re-keys the tree).
Suggestion (`ui/suggestion/`, `LocalOpenSuggestion`) is the suggestion box: title + description required,
e-mail optional → `SeriesApi.postSuggestion` → the server's `POST /suggestions` (open to signed-out users),
which stores it (`/admin/suggestions`) and mails it via `SMTP_*` to `SUGGESTIONS_TO` with Reply-To = the sender.
Every UI string lives in `composeResources`: English is the default `values/strings.xml`,
Swedish is `values-sv/` — add each new key to both (iOS: `CFBundleLocalizations` in
`project.yml` + `sv.lproj/InfoPlist.strings`). Caliber labels stay untranslated (stored values).
`topScores` are picker indices 0–10
plus 11 = inner-X, one per hole and at least five; a series has no hole cap (the detector keeps
every box over its confidence threshold), the score rows show five a row and wrap the rest, and the
competition wizard prefills its five boxes from the five most confident holes (`confidentSlots`). Theme is a green-accented dark or light scheme (Settings; no dynamic color); photo overlays keep fixed colours in both.
Overlay text is drawn with `TextMeasurer` (Skia on iOS, so the two platforms render alike);
back navigation is Compose's common `BackHandler`; toasts are one `SnackbarHost` behind
`LocalToast`.

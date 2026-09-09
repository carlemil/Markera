# iOS version of Markera

## Context

Markera is a KMP + Compose Multiplatform app, but only Android works: every screen
(~5900 lines in `androidMain/ui`), the camera, both detector actuals, the SQLite
cache and the Xcode project are Android-only or missing. What already compiles
for iOS (PLAN task 6): all of `vision/` maths, `series/` + `webshooter/` logic,
view models, theme, Sign in with Apple (`iosMain/series/SignIn.kt`), an
NSUserDefaults token store, Ktor Darwin, and a static `ComposeApp` framework for
iosArm64/iosSimulatorArm64.

Decisions (user, 2026-09-09): full feature parity; share the Compose UI by moving
it to commonMain; bundle id `se.kjellstrand.markera`; the hidden webshooter
competition wizard stays Android-only; **no iPhone — simulator only** on the Mac
mini (`ssh macmini`, macOS 26.5, Xcode 26.6, Apple silicon, K/N 2.2.10 cached,
xcodegen installed, no CocoaPods, no `best.onnx` there yet, signing identity for
team `BSZST7M33J` present, `server/.env` has `APPLE_BUNDLE_ID=` blank). The
orchestrator workflow applies: one task = one reviewed commit, Android gate
(`./gradlew.bat :composeApp:testDebugUnitTest :composeApp:assembleDebug --rerun-tasks`,
158 tests) must stay green after every task.

Two facts that shape the design: ORT's Objective-C API has **no Float16 tensor
type**, so the fp16-I/O `best.onnx` cannot be fed through it as is; and ORT ships
an official Swift Package (`microsoft/onnxruntime-swift-package-manager`, 1.24.x,
xcframework incl. simulator) so CocoaPods is not needed. Both to be re-verified in
task C1/B2 (they came from the planning agent, not from a build).

## Architecture

**Principle:** `git mv` `androidMain/ui/**` (minus `ui/competition/**`) to
commonMain, then replace each Android-only call with the smallest seam. Android
actuals are the existing code moved into `*.android.kt`.

### Entry objects (no `LocalContext` in shared code)

- `commonMain/AppServices.kt`:
  `class AppServices(val series: SeriesServices, val modelPath: String, val shareFile: (path: String) -> Unit)`,
  `@Composable fun AppNavHost(app: AppServices, competition: CompetitionHost? = null)`.
- `SeriesServices` becomes one commonMain class (delete both platform copies):
  `SeriesServices(engine: HttpClientEngine, baseUrl: String, store: BackendTokenStore, driver: SqlDriver, cacheDir: Path)`;
  body = today's Android init. `BackendTokenStore` gets `readCaliber()/writeCaliber()`
  with defaults (both stores already implement them). `FileImageCache(dir: Path)`
  moves to commonMain on kotlinx-io (already transitive via Ktor 3.2; declare it).
- `MainActivity` builds `AppServices(SeriesServices(OkHttp.create(), BuildConfig.BACKEND_URL, DataStoreBackendTokenStore(this), AndroidSqliteDriver(...), Path(cacheDir.path)), prepareModel(this), { shareFile(this, File(it)) })`
  where `prepareModel` is the asset-copy block lifted out of `rememberTargetScanController`.
- `MainViewController` builds the same with `Darwin.create()`, backend URL from
  Info.plist (`MarkeraBackendUrl`, default `https://markera.duckdns.org`),
  `UserDefaultsBackendTokenStore`, `NativeSqliteDriver(MarkeraDb.Schema, "markera-series.db")`,
  caches dir, `NSBundle.mainBundle.pathForResource("best", "onnx")`, `UIActivityViewController` share.
- **Competition seam:** `Screen` loses the four competition screens, gains
  `Screen.Competition`; `class CompetitionHost(val content: @Composable (FrameSource, TargetScanController, onExit: () -> Unit) -> Unit)`
  in commonMain; androidMain `ui/competition/CompetitionFlow.kt` holds
  `WebshooterServices(context)`, the session check and the inner 4-step stack
  (the `when` branches moved out of `AppNavHost`). Home card shows only when
  `competition != null && SHOW_COMPETITION`. iOS passes `null`. The five wizard
  screens, `WebshooterServices`, `DataStoreTokenStore` stay in androidMain untouched.

### expect/actual (new; the existing four stay)

| expect (commonMain) | Android actual | iOS actual |
|---|---|---|
| `PlatformImage.width/height` | `Bitmap` | `actual typealias PlatformImage = UIImage` (ARC-managed; the current `CGImage` struct alias is unusable as a value), `CGImageGetWidth` |
| `PlatformImage.centerSquare()` | existing | `CGImageCreateWithImageInRect` |
| `PlatformImage.argb(w, h): IntArray` — the one pixel primitive (scaled if w/h differ) | `createScaledBitmap(..., true).getPixels` (today's path, identical tensors) | `CGBitmapContextCreate` RGBA + `CGContextDrawImage`, repack to ARGB |
| `PlatformImage.toImageBitmap()` | `asImageBitmap()` | skiko `Image.makeRaster(...).toComposeImageBitmap()` |
| `encodeSeriesJpeg(image): EncodedImage` | existing | scale >3072 via CGContext, `UIImageJPEGRepresentation(0.9)` |
| `decodeSeriesJpeg(bytes, maxDim): ImageBitmap?` (call sites drop `.asImageBitmap()`) | existing | `CGImageSourceCreateThumbnailAtIndex(kCGImageSourceThumbnailMaxPixelSize)` |
| `@Composable rememberFrameSource(): FrameSource` (interface → commonMain, `capture(): PlatformImage?`) | existing `CameraFrameSource` | camera if `AVCaptureDevice` exists, else photo picker (see below) |
| `@Composable rememberCameraPermission(fs): CameraPermissionState` | existing | `AVCaptureDevice.authorizationStatus/requestAccess`; picker → granted |
| `@Composable rememberSignIn(session): suspend () -> BackendAuth` | `{ signInWithProvider(LocalContext, session) }` | `{ signInWithProvider(session) }` + Debug dev-auth fallback |

Shared with no seam:
- **Pixel loops once, in commonMain** `vision/ImageOps.kt`: `lumaFromArgb(argb, w, h)`,
  `letterboxChw(argb, w, h, inputSize)` (same newW/newH/pad maths as today, pad 0f).
  `BitmapPreprocess` shrinks to `toGrayscale()`/`toModelInput()` over `argb()`.
  `:eval` can adopt `letterboxChw` later (not in scope).
- **Toast → one `SnackbarHost`** at the nav root via `LocalToast: (String) -> Unit`
  (3 files lose `Toast` + `LocalContext`).
- **Strings → composeResources:** `git mv` `strings.xml` to
  `commonMain/composeResources/values/`, keep only `app_name` in androidMain res,
  `compose.resources { packageOfResClass = "se.kjellstrand.markera.res" }`, then a
  sed pass over the 160 call sites (`R.string.` → `Res.string.`, import swaps).
  Hand fixes: `HelpDialog(sections: List<Pair<StringResource, StringResource>>)`,
  `StatsScreen` preset/id maps, `SeriesExportAndroid` chooser title, competition
  screens (androidMain) use the same `Res` import.
- **drawText:** `rememberTextMeasurer()` + `DrawScope.drawText(... TextStyle(shadow = Shadow(Black, Offset(0,2), blur)))`
  in `DetectionOverlay` (label stacking on `androidx.compose.ui.geometry.Rect`) and
  `StatsScreen.digit(...)`.
- **Dates:** kotlinx-datetime for `localStamp`, `utcDay`, the export file stamp
  (replaces `java.time`; both become commonTest-able).
- **BackHandler:** `androidx.compose.ui.backhandler.BackHandler` (CMP 1.9).
- **ViewModel:** keep androidx lifecycle 2.10 (iOS artifacts resolve; move
  `lifecycle-viewmodel-compose` to commonMain); `MarkeraSnapshotViewModel` → `PlatformImage`.
- **Icons:** `org.jetbrains.compose.material:material-icons-extended` in commonMain
  (fallback: paste the 8 `ImageVector`s if iOS link time hurts).
- **TargetScanController → commonMain:** `kotlin.concurrent.atomics.AtomicBoolean`,
  `TimeSource.Monotonic`, `println`; `rememberTargetScanController(modelPath)`.
- **Zip export → commonMain** `series/Zip.kt`: store-only writer with table CRC-32
  (~60 lines) + `exportSeriesZip(repository, cacheDir): Path` on kotlinx-io, used by
  both platforms; Android keeps only `shareFile`. Test: CRC("123456789") = 0xCBF43926.
- Portrait via Info.plist; `WindowInsets.safeDrawing` already works on iOS.

### ONNX on iOS: Swift bridge over the ORT Swift Package + fp32 model I/O

- `scripts/cast_model_io.py` (onnx, ~15 lines) wraps the graph: new fp32 input
  `images` → `Cast(FLOAT16)` → old input; output → `Cast(FLOAT)`. ORT's Cast is
  round-to-nearest-even like `Fp16Conversions.floatToFp16`, so Android/`:eval`/iOS
  stay **bit-identical**; Android and `:eval` already branch on the declared input
  type, so the regenerated `best.onnx` needs no code change. Keep the original at
  `D:/ml/holes/best.fp16.onnx` (outside `assets/`, or it ships in the APK).
- iosMain `interface HoleModel { fun run(input: NSData, shape: List<Long>): NSData }`
  (exported as an ObjC protocol); `HoleDetector.ios` packs the `FloatArray` to
  `NSData` (`usePinned`), calls it on `Dispatchers.Default`, unpacks floats, applies
  the 0.01 prefilter like Android. `MainViewController(holeModel: HoleModel)`.
- Swift `iosApp/iosApp/HoleModel.swift` (~40 lines): `ORTEnv`, `ORTSession(intraOpNumThreads 4)`,
  `ORTValue(tensorData:elementType: .float, shape:)`, `session.run`, `tensorData()`.
- Rejected: CocoaPods + cinterop (needs brew cocoapods, still hits the Float16 gap),
  raw C-API cinterop (most code), CoreML export (changes numerics).

### Camera / photo picker (iOS)

- `PhotoPickerFrameSource` (simulator; first to build): `Preview` shows the picked
  image (`ContentScale.Crop`) or a "Välj foto" button; `PHPickerViewController`
  (no permission prompt) → `UIImage` normalised upright via `UIGraphicsImageRenderer`;
  `capture()` returns it; a re-pick replaces it.
- `CameraFrameSource` (real device; **compile-only, unverifiable without an iPhone**):
  `AVCaptureSession` `.photo`, `AVCapturePhotoOutput`, `UIKitView` preview with
  `AVCaptureVideoPreviewLayer` resizeAspectFill in the square viewport; `capture()`
  returns the upright full frame; the shared `centerSquare()` then matches
  Android's 1:1 FILL_CENTER crop. A photo-library icon in the preview corner
  opens the same picker ("scan a saved photo").

### Vision OCR (iOS)

`DigitDetector.ios`: `VNRecognizeTextRequest` (Accurate, no language correction) on
`VNImageRequestHandler(cgImage)`; per whitespace token of `topCandidates(1)`, keep
exactly one char `'1'..'9'` (ML Kit's single-char element rule), box from
`boundingBoxForRange` with the y-flip `top=(1-maxY)*H, bottom=(1-minY)*H`, conf 1f.

### Sign-in, backend URL, dev fallback

Keep `iosMain/series/SignIn.kt`. Info.plist `MarkeraBackendUrl`/`MarkeraDevAuth`
from per-config xcodegen settings: Debug → `http://127.0.0.1:8091` + dev auth
(`NSAllowsLocalNetworking`), Release → production, no dev auth. iOS `rememberSignIn`:
try Apple, on failure `signInDev("ios-sim")` when dev auth is on. Local server on
the Mac: `PORT=8091 DEV_AUTH=true APPLE_BUNDLE_ID=se.kjellstrand.markera DB_PATH=/tmp/markera-dev.db sh gradlew run`
in `server/` (background). Production Apple sign-in additionally needs
`APPLE_BUNDLE_ID` in the Mac's `server/.env` + `docker compose up -d --build` (user).

## Xcode project (xcodegen)

Commit `iosApp/project.yml`; gitignore `iosApp/iosApp.xcodeproj`, `iosApp/build`,
`iosApp/Config/Local.xcconfig` (untracked, `DEVELOPMENT_TEAM = BSZST7M33J`; ship a
`.example`). Files: `iosApp/iosApp/iOSApp.swift` (SwiftUI `App` +
`UIViewControllerRepresentable` → `MainViewControllerKt.MainViewController(holeModel:)`),
`HoleModel.swift`, `Assets.xcassets` (icon from `store/`), `iosApp.entitlements`
(`com.apple.developer.applesignin`), Info.plist keys (`CFBundleDisplayName Markera`,
portrait only, `NSCameraUsageDescription`, `NSPhotoLibraryUsageDescription`,
`MarkeraBackendUrl`, `MarkeraDevAuth`, `MARKETING_VERSION 1.5.0`/build 6 mirroring
Android). project.yml: SPM package `onnxruntime` exact 1.24.2, `best.onnx` as a
resource from `composeApp/src/androidMain/assets/`, framework dependency at
`../composeApp/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)/ComposeApp.framework`
(the README's `$(SRCROOT)/build/...` path is wrong for this layout), `libsqlite3.tbd`,
`OTHER_LDFLAGS -ObjC -lsqlite3`, pre-build script
`./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`, `ENABLE_USER_SCRIPT_SANDBOXING NO`,
deployment target iOS 16.

Mac commands (`scripts/mac-build.sh`, always `export PATH=/opt/homebrew/bin:$PATH`):
`xcodegen generate` → `xcodebuild -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' -derivedDataPath build/dd build`
→ `xcrun simctl install booted .../iosApp.app` → `xcrun simctl launch booted se.kjellstrand.markera`
→ `xcrun simctl io booted screenshot` (scp back, view with Read) →
`xcrun simctl addmedia booted <jpg>` to inject dataset target photos →
`xcrun simctl spawn booted log stream --predicate 'process == "iosApp"'` for logs.
**Taps:** install idb on the Mac (`brew tap facebook/fb && brew install idb-companion`,
`pipx install fb-idb`): `idb ui describe-all` (accessibility tree incl. frames),
`idb ui tap X Y`, `idb ui text`. Fallback if idb misbehaves on Xcode 26: a one-test
XCUITest target driven by an env var label list.

## Windows → Mac sync without committing WIP

`scripts/mac-sync.sh` (Git Bash): add remote `macmini` (`macmini:source/Markera`),
`git push -f macmini HEAD:refs/heads/sync`, on the Mac `git checkout -B build sync`,
then `git ls-files -mo --exclude-standard -z | tar --null -T - -cf - | ssh macmini 'tar -C ~/source/Markera -xf -'`
plus `git ls-files -d` removals. The Mac never commits or pushes. One-off: scp
`best.onnx` (cast version) and a few `D:/ml/holes/dataset/images/train/*.jpg` to
`macmini:~/eval/`. Never create `Local.xcconfig` on Windows (the tar would carry it).

## Tasks (execution order, one commit each; Android gate after every task)

| # | Task | Verification | Status |
|---|------|--------------|--------|
| C0 | Sync/build scripts, `.gitignore` entries, scp model + test photos, fix `iosApp/README.md` paths. | Mac HEAD = Windows HEAD; `best.onnx` on the Mac | done (2026-09-09; `scripts/mac-sync.sh` + `scripts/mac.sh`; model + 3 dataset photos in `macmini:~/eval/`; README fixed in C1; framework link at HEAD verified on the Mac) |
| C1 | **Placeholder iOS app on the simulator first**: project.yml, iOSApp.swift, entitlements, Info.plist, `Local.xcconfig.example`, SPM package, `linkerOpts("-lsqlite3")`. Runs the existing `App()` placeholder. | `sh gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`; xcodebuild + install; screenshot shows the placeholder text | done (2026-09-09; `scripts/mac-build.sh`; ORT SPM pinned by commit (newest tag is 1.19.2, main = 1.24.2 binaries); Compose aborts without `CADisableMinimumFrameDurationOnPhone`; two simulators are booted so the script names the iPhone; placeholder screenshot checked) |
| C2 | Install idb on the Mac; prove describe-all + tap + screenshot on the placeholder. | screenshot after a tap | done (2026-09-09; `brew trust facebook/fb` was needed first; the tree is empty until the first tap; recipe in `iosApp/README.md`) |
| A1 | `scripts/cast_model_io.py`; regenerate local `best.onnx` (fp32 I/O), keep `best.fp16.onnx`. | Android gate; `/eval` seed 42 detections identical to before; phone scan unchanged; scp to the Mac | done (2026-09-09; local `best.onnx` now fp32 I/O, original kept at `D:/ml/holes/best.fp16.onnx` (not under assets/, it would be packaged); eval seed 42: identical hole counts on all 9 images, mosaics differ by 147 antialiased edge pixels in 3 tiles, so equivalent but not bit-identical; phone scan check pending, phone absent) |
| A2 | Strings → composeResources (sed pass + hand fixes), `app_name` kept in androidMain. | Android gate; phone: a help dialog and the signed-in text with its arg | done (2026-09-09, commit 0e7c510; 204 strings in `commonMain/composeResources`, `Res` in package `se.kjellstrand.markera.res`; `stats_percent` lost its `%%` (CMP does not unescape it, nor backslash-quote escapes: never add those); phone check pending) |
| A3 | Toast → `LocalToast` snackbar; drop `LocalContext` from AppNavHost/History/Detail. | Android gate; save/failed message shows | done (2026-09-09; `LocalToast` + one `SnackbarHost` at the nav root; History keeps `LocalContext` for the export/share until A6; phone check pending) |
| A4 | Common `SeriesServices` + `AppServices`; `MainActivity` builds them; caliber defaults on `BackendTokenStore`; `FileImageCache` on kotlinx-io; delete the iOS/Android service copies. | Android gate; History thumbnails still served from `cacheDir/series` | open |
| A5 | Competition seam (`Screen.Competition`, `CompetitionHost`, androidMain `CompetitionFlow.kt`). | Android gate; flip `SHOW_COMPETITION=true` once locally to smoke it | open |
| A6 | Common zip writer + `exportSeriesZip`, kotlinx-datetime stamps, delete `java.util.zip`/`java.time` uses. | Android gate + `ZipTest`/stamp tests; phone: export opens in Files | open |
| A7 | PlatformImage seams (`width/height/centerSquare/argb/toImageBitmap/encode/decode`), `vision/ImageOps.kt` + test, Android actuals, **real iOS actuals** (`UIImage` alias + CoreGraphics), `MarkeraSnapshotViewModel` common. | Android gate; `/eval` unaffected; Mac link | open |
| A8 | Move `ui/**` (minus competition) to commonMain: `TargetScanController`, `FrameSource`, `CameraPermissionState` common; `rememberFrameSource`/`rememberCameraPermission`/`rememberSignIn` expects with Android actuals = existing code; drawText via `TextMeasurer`; common `BackHandler`; icons + viewmodel-compose deps; delete `App.kt`; `MainViewController` → `AppNavHost` with minimal iOS actuals so it compiles. Split into A8a (moves + expects) and A8b (drawText/backhandler/icons) if the diff passes ~1500 lines. | Android gate; phone scan screenshot before/after (overlay text now Skia); Mac link; simulator shows Home with strings, icons, nav, snackbar | open |
| B1 | iOS `PhotoPickerFrameSource` + permission actual; `HoleModel` interface; real `HoleDetector.ios`; `MainViewController(holeModel)`. | sim: addmedia a target JPEG → Välj foto → Detektera → frozen frame | open |
| B2 | Swift `HoleModel.swift` on ORT SPM + wiring. | sim log `raw=N kept=M`; hole count matches `/eval` for the same image | open |
| B3 | Vision `DigitDetector.ios`. | sim: digits found, centre + ring drawn on the photo, scores shown | open |
| B4 | iOS series wiring: `suspend` UserDefaults store, caches dir, `UIActivityViewController` share, dev-auth fallback + Info.plist reads; local dev server recipe in README. | sim vs local server: sign in (dev), scan → Spara → caliber dialog → saved; Historik thumbnail; delete; Serie move/save; Statistik; export share sheet | open |
| B5 | iOS `CameraFrameSource` (AVFoundation) with the picker icon overlay. **Compile-only + review; no device.** | Mac link; simulator still uses the picker | open |
| C3 | App icon, launch colour, `DEVELOPMENT_TEAM` from `Local.xcconfig`, Release simulator build. | Release sim screenshot | open |
| C4 | fastlane `beta` lane + `Appfile` (blocked on the user's App Store Connect API key + App ID). | `fastlane beta` once the key exists | open |
| D1 | Move `SeriesApiTest`, `BackendSessionRepositoryTest`, `SeriesRecorderTest` to commonTest with `runTest` and `expect fun testImage()` (Android: today's `Unsafe`; iOS: `UIImage()`); `SeriesRepositoryTest` stays JVM (JDBC driver). | Android gate stays 158; optionally `sh gradlew :composeApp:iosSimulatorArm64Test` | open |
| D2 | Docs: CLAUDE.md/README iOS sections (build, sim, idb, dev server, model cast), PLAN.md rows, memory note. | — | open |

Rationale: C0–C2 make every later task screenshot-verifiable; A1–A7 are Android-visible
refactors that keep the gate green; A8 lands with iOS compiling; B1–B4 make the
simulator app functional end to end; B5/C3/C4 are device/TestFlight and blocked on
hardware or user credentials.

## Risks to check early

1. `lifecycle-viewmodel-compose` 2.10.0 for iOS (fallback `org.jetbrains.androidx.lifecycle` 2.9.x or plain `remember`).
2. `ui-backhandler` transitive on uikit targets (else add it explicitly).
3. JetBrains `material-icons-extended` next to Compose 1.9 on Android; iOS link time.
4. ORT SPM 1.24.2 + Xcode 26.6 + static Kotlin framework (`-ObjC`); fp16-body model on the CPU EP on arm64 sim.
5. skiko `Image.makeRaster`/`toComposeImageBitmap` on CMP 1.9 iOS.
6. CMP resource escapes (`\n`, `\'`) in the 205 strings.
7. Sign in with Apple on the simulator (dev fallback covers verification either way).
8. Gradle 9.4.1 on JDK 26 on the Mac (task 6 built there; else `brew install openjdk@21` + `JAVA_HOME`).
9. `NativeSqliteDriver` needs `-lsqlite3` at the app link.
10. Vision `boundingBoxForRange` may be nil for some ranges → fall back to the observation box for single-token strings.

## User actions

- Apple Developer portal: App ID `se.kjellstrand.markera` with Sign in with Apple.
- Xcode on the Mac: sign into the Apple ID once (Settings → Accounts) for device/TestFlight signing; create `iosApp/Config/Local.xcconfig` with `DEVELOPMENT_TEAM = BSZST7M33J` (simulator work needs neither).
- Server: `APPLE_BUNDLE_ID=se.kjellstrand.markera` in the Mac's `server/.env`, then `docker compose up -d --build`.
- App Store Connect: app record + API key (`.p8`, untracked under `iosApp/fastlane/`) for `fastlane beta`.
- Approving this plan also approves: brew-installing idb on the Mac (C2) and regenerating the local `best.onnx` with fp32 I/O (A1, original kept as `best.fp16.onnx`).

## Verification (end to end)

- After every task: Android gate green (158 tests + assembleDebug), debug build on the phone for UI-visible tasks (A2, A3, A6, A8).
- iOS: `linkDebugFrameworkIosSimulatorArm64` from A7 on; from C1 on, a simulator build + screenshot per task; from B1 on, a scripted flow on the simulator (idb taps + screenshots): pick an injected dataset photo → Detektera → holes/letters/scores shown → Spara → caliber → Historik → Serie → Statistik → export sheet, against the local dev server.
- Accuracy parity: for two dataset images, iOS hole count and scores equal `/eval` (same cast model, same letterbox maths).

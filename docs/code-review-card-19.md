# Card 19: full code review, split into follow-up cards 19a–19j

Review of the whole codebase (~21.5k lines: `vision/`, `ui/markera`, `:eval`, `series/`,
history/stats, `server/`, webshooter/competition, navigation/settings/theme, the platform
actuals, build files, scripts, docs). Card 19 changes no code; each section below is one
follow-up card, sized for one commit or a few. Line numbers are as of `d1d9b43`.

**Gate for every app card:** `./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug --rerun-tasks`.
Server cards also run the `server/` tests and need a redeploy on the Mac mini. Behaviour
changes are checked on the phone (34282ee3), never on an emulator.

## Decisions (from the card's answers)

1. **19e, manual-hole scoring:** a hand-placed hole is scored from a hole circle sized by the
   caliber and edge-gauged like a detected hole. A score the user *selected* is never overwritten.
2. **Caliber whitelist:** dropped on the server; it checks only the shape (1–16 chars of
   `[A-Za-z0-9 .,/-]`). Done in 19c.
3. **Competition wizard:** fix it now (19j is a normal-priority card, not parked).
4. **Backend session expiry:** 90 days, sliding. Done in 19c.
5. **iOS token:** left as is on the user's instruction (2026-09-19), note only (19b).

## Suggested order

19b → 19a → 19d → 19c → 19e → 19h → 19f → 19j → 19i → 19g

Data loss and security first, then robustness, scoring and the wizard, then refactors; the
pure file moves last so they do not conflict.

---

## 19a: Series save pipeline, data-loss races (high) — card #22

1. **A slow save wipes the next scan.** When series N's save finishes, `SeriesRecorder.kt:190-193`
   sets `pending = null`; a series N+1 scanned during that POST is lost, and its `commit()` returns
   silently at `:113`. Fix: snapshot (`request`, `image`) at `startSave`, clear `pending` only when
   `pending === snapshotSource`.
2. **One shared `saveJob` is cancelled by the wrong things:** `clear()` (`:162`, rescan), a new
   `startSave` (`:184`), `selectCaliber` during `Saving` (`:125-129`). Result: cancelled image
   upload, lost cache insert, or a **duplicate POST** from the caliber chip. Fix: one job per
   committed save (tracked for `dispose()`); `clear()` drops only what is pending; `selectCaliber`
   never restarts a running save.
3. **POST /series is not idempotent.** A lost response, or `reload()` throwing after the insert,
   gives `Failed` and the next commit re-posts. Fix: `clientId` UUID in `SeriesRequest`; server
   `client_id` column with `UNIQUE(user_id, client_id)`, a repeat returns the existing id
   (idempotent migration in `Db.kt`'s style; the field has a default so older apps still work).
4. **`SeriesRepository` refresh and writes are not serialised** (`SeriesRepository.kt:47-63,146-167`).
   History's `LaunchedEffect(auth)` plus the sign-in refresh run two full loads at once; a write in
   between is undone until the next refresh. Fix: one `Mutex` around refresh/save/update/delete;
   skip a refresh when one is running.
5. **`reload()` sits outside the try in `refresh()`** (`:53`, also `:83,95,115`): one undecodable
   cached row makes refresh throw and turns a successful save into `Failed`. Fix:
   `mapNotNull { runCatching { … }.getOrNull() }` and delete the bad rows.
6. **A 401 is never handled** (`SeriesApiException.isUnauthorized`, `SeriesApi.kt:24`, is unused).
   Fix: on a 401 in the recorder or refresh, `session.signOut()` + `repository.clear()` + one
   localised toast.
7. **Detail: deleting a hole added in the same session** (`SeriesDetailScreen.kt:409`) sends it to
   the server as a `deleted = true` record. Fix: tombstone only holes from the original `series.holes`.

Tests: `SeriesRecorderTest` (overlapping scans, chip during a save, 401); `SeriesRepositoryTest`
(corrupt cached row, refresh concurrent with a save). Phone: two quick scans on a throttled
network both land in History exactly once, with images.

## 19b: Android backup and model copy (high, small) — card #23

1. **Backups include the model and tokens.** `AndroidManifest.xml:12` has `allowBackup="true"`;
   `backup_rules.xml` / `data_extraction_rules.xml` are untouched templates. The ~40 MB
   `best.onnx` in `filesDir` exceeds the 25 MB Auto Backup limit, and the Markera/webshooter bearer
   tokens (DataStore) get restored onto other devices. Fix: model into `noBackupFilesDir`; exclude
   the token DataStore files in both XMLs; keep `settings`.
2. **Model copied on the main thread** (`MainActivity.kt:14-26,44-56`, first launch after every
   install/update → ANR risk). Fix: copy on `Dispatchers.IO`, hand over `modelPath` when done.
3. **iOS token:** deferred; add a note at `UserDefaultsBackendTokenStore.kt:60-64` that
   NSUserDefaults is backed up and unencrypted.

Check: `adb shell bmgr backupnow se.kjellstrand.markera` succeeds without `best.onnx`.

## 19c: Server hardening (med-high, needs a redeploy) — card #24

1. **No hole validation or size cap** (`Server.kt:294-307`). Reject >50 holes, `ring` outside
   `0..10`, `innerTen` with `ring != 10`, out-of-range `detected*`, bodies over ~1 MB. Replace the
   `CALIBERS` whitelist with the shape check; delete the server list and its test.
2. **Image upload** (`Server.kt:260-268`): reject empty bodies and non-`FF D8` starts;
   `Files.createTempFile` instead of `"$seriesId.jpg.tmp"`; `X-Content-Type-Options: nosniff` on
   `respondFile` (also `Admin.kt:175`). App: iOS `SeriesImage.ios.kt:43-45` returns `ByteArray(0)`
   on failure — throw instead, and encode on `Dispatchers.Default` (blocks the main thread today).
3. **Sessions** (`Db.kt:50-54,142-156`) are plain text, never expire, can't be revoked. Store
   `sha256(token)` (lazy migration on lookup), 90-day sliding expiry, `DELETE /auth/session` called
   from `BackendSession.signOut()`.
4. **Auth** (`Auth.kt:36-41`): a token without `sub` → NPE → 500; add `.withClaimPresence("sub")`.
   JWKS provider: `.cached(...).rateLimited(...)`.
5. **Admin:** the JSON blob escapes only `</` (`Admin.kt:279,282`) — escape every `<` as `\u003c`;
   `POST …/restore` is cross-site triggerable — require `X-Admin: 1` on writes; the editor's PUTs
   are unsequenced (`Admin.kt:509-527`) — chain them.
6. **`Db.kt`:** `insertSeries`/`replaceSeries` in a transaction; bind the two interpolated `Long`s
   (`:234,308`).

A server test per item; then `/health` and one save from the phone after the redeploy.

## 19d: Scan pipeline and lifecycle robustness (med-high) — card #25

1. `TargetScanner.kt:432` calls `frozen.toImageBitmap()` on every recomposition (~72 MB churn per
   drag event on iOS). Fix: `remember(frozen) { … }`.
2. **ORT session closed during `run`** (`TargetScanner.kt:348-350` → `HoleDetector.android.kt:74-76`):
   an activity recreation mid-`session.run` is a native use-after-free. Defer the close to the
   scan's `finally` while `detecting`; close `SessionOptions` with `.use {}`.
3. **`CancellationException` swallowed** by broad catches: `TargetScanner.kt:159`,
   `CameraFrameSource.kt:47-54`, `SeriesDetailScreen.kt:314,425`, `SeriesHistoryScreen.kt:200,326`
   (also `exporting = false` into a `finally`), `AppNavHost.kt:526-527,558`,
   `MarkingWizardViewModel.kt:309,437`. Add `catch (e: CancellationException) { throw e }` first.
4. **A language change rebuilds everything heavy:** `key(language)` (`AppNavHost.kt:141`) recreates
   the `FrameSource`, scan controller (ONNX session) and `SeriesRecorder`, cancelling an in-flight
   save and dropping the pending series. Create them above the `key`. Separately, `MainActivity`
   builds a new `SeriesServices` per recreation, leaking a SQLite driver: build it once in an
   `Application` subclass.
5. **iOS reports failures as empty results** (`HoleDetector.ios.kt:31-35`,
   `DigitDetector.ios.kt:41-42`): throw, so `markera_error_inference` shows.
6. **iOS camera** (`FrameSource.ios.kt`, code-read only): check `session.running` + an active video
   connection before `capturePhotoWithSettings` (else an uncatchable ObjC exception); run
   `startRunning`/`stopRunning` on one serial queue; call `upright()` on the thread its comment names.
7. `addHit` returns `true` even when a sixth hole was refused (`TargetScanner.kt:181-187`): make
   `addManualHit` return a `Boolean`.

Phone: change language with a frozen scan, then Spara → saved; toggle dark mode during a scan → no crash.

## 19e: One manual-hole scoring rule, sized by caliber (med) — card #26

Today a scan-screen tap gets a median-size box, edge-gauged in `scoreHits` (`ManualHit.kt:70-72`,
`HitScoring.kt:130-131`); Detail's `GeometryDto.scoreHoleAt` (`SeriesDtos.kt:235-243`) uses the
centre distance with no gauge; a drag throws away a typed score on both screens.

1. `vision/HitScoring.kt`: extract `scoreAt(distMm, holeRadiusMm)`; `scoreHits` passes the box
   radius, `scoreHoleAt` the caliber radius.
2. `Caliber.holeRadiusMm()` = `diameterMm / 2`. `Caliber.NONE` falls back to today's behaviour
   (median box on the scan screen, 0 in Detail).
3. Scan: `manualDetection(..., holeSidePx: Float?)`, side = `diameterMm / mmPerPx`,
   `mmPerPx = TARGET_BLACK_RING_RADIUS_MM / ring.semiMajor`; caliber from
   `LocalSeriesRecorder.current?.caliber`.
4. Detail: `scoreHoleAt`/`withNewHole`/`moveHole` take the screen's `caliber`; fix the KDoc.
5. A selected score survives a drag: scan `HitScore.movedFrom` keeps ring/X/`typed` when
   `old.typed`; Detail (no typed flag, no schema change) treats a score as selected when it differs
   from `scoreHoleAt` at the old position, and then only moves the hole.
6. A caliber change never rescores placed holes; only new placements and drags use it.

Tests: `HitScoringTest` (`scoreAt` at a ring line, with/without radius), `ManualHitTest` (caliber
side, median fallback), `SeriesDtosTest` (same point → same score via both paths; selected score
survives a drag; auto score is rescored). Phone: .22 hole just outside 9/10 scores 10; typed 7
survives a drag; same in Detail.

## 19f: Single source of truth, vision and eval (med) — card #27

1. Eval keeps all detections, the app the top 5; thresholds 1536/0.35/0.45 are copied into
   `HoleDetectionMosaicTest.kt:31-34`. One common `postProcess(raws, inputSize, w, h)` in
   `vision/DetectionPostProcess.kt` for both.
2. The `[1,300,6]` parser + `PREFILTER_CONFIDENCE` exists three times (Android, iOS, eval): one
   common, tested `parseNmsRows(count, get)`.
3. Eval re-implements the letterbox (`eval/ImageOps.kt`) and re-declares `RawDetection`/`Detection`
   (`EvalDetections.kt`): use `letterboxDims`/`letterboxChw`; move the data classes out of the
   expect file into a plain file (`CentreTypes.kt` pattern).
4. Dead fp16 input branch (`HoleDetector.android.kt:78-89`, eval `OnnxHoleDetector.kt:63-74`) and
   unread `RawDetection.cls`: delete.
5. `:eval` hard-codes `D:/` paths (`HoleDetectionMosaicTest.kt:22-43`): `Assume.assumeTrue` on the
   images dir; model defaults to `../composeApp/src/androidMain/assets/best.onnx`.
6. Target geometry hard-coded in `DetectionOverlay.kt:36`, `TargetScanner.kt:671,686`: derive from
   `RING_RADII_MM`/`TARGET_BLACK_RING_RADIUS_MM` as `StatsScreen.kt:601-630` does; add
   `CentreConfig.usable(digits)` (3 copies + `RingProbe.kt:76`) and `RING_WIDTH_FRACTION`.
7. Colours/helpers: `0xFF00E676` ×5 → `MarkeraGreen`; `0xFFFFB74D` ×2 → one constant; drop the 6
   un-overridden `DetectionOverlay` colour params; merge `singleDigitOrNull`,
   `nearestDetectionIndex`/`nearestHoleIndex`, `HIT_SCORE_ORDER`/`HOLE_ORDER`, the label-size
   formula, `DAY_MS`, `IMAGE_MAX_DIM`.
8. Android digit `conf = 1f` (`DigitDetector.android.kt:47`) vs real iOS values: document in the
   expect KDoc.
9. Missing tests: each `fit67Ring` gate, `fit67RingFromDigits`, the tap/long-press/drag decision
   in `photoGestures`.

Check: `/eval` mosaic for `-Dmosaic.seed=42` unchanged before/after.

## 19g: Split oversized files, move shared UI (low-med, pure moves; do last) — card #28

Start only after 19a, 19d, 19e and 19f are done **and** UI cards 6–18 (in verify on
2026-09-19) are merged: they touch the same screens (`MarkeraScreen`, `SeriesHistoryScreen`,
`StatsScreen`, `AppNavHost`).

| File | What moves out |
|---|---|
| `ui/markera/TargetScanner.kt` | `TargetScanController.kt`, `ScanningOverlay.kt`, `ViewfinderGuide.kt` |
| `ui/markera/MarkeraScreen.kt` | `TotalBadge`, `PillSegment`, `Primary/SecondaryActionButton` → shared `ui/` |
| `ui/stats/StatsScreen.kt` | `StatsTarget.kt`, `TrendChart.kt`; `stats == null` → `plotted.isEmpty()` |
| `ui/history/SeriesHistoryScreen.kt` | `SeriesCard.kt`, delete dialogs, `rememberExport()` |
| `ui/history/SeriesDetailScreen.kt` | `DetailPhoto(...)`, the save lambda |
| `ui/AppNavHost.kt` | `HomeScreen.kt`, `SeriesDialogs.kt` |
| `MarkingWizardScreen.kt` | station summary, final standings |

Also move `SectionHeader` and `DateRangeDialog` from `ui.stats` into `ui/`, and merge
`StatsScreen.FilterRow` / `SeriesHistoryScreen.HistoryFilterRow` into one `SeriesFilterChips`
(one way to build the tag list instead of three).

## 19h: i18n and user-facing errors (med) — card #29

1. Raw exception text in the UI: `AppNavHost.kt:205,527` (incl. a cancelled sign-in),
   `SeriesHistoryScreen.kt:138`, `StatsScreen.kt:182`. Map to a few localised strings (offline,
   session expired, server error), log details, stay silent on user cancel.
2. Trend tab strips units out of format strings (`StatsScreen.kt:486,491`), concatenates a label
   (`:496`), hard-codes `.` decimals (`:488,827`): whole format strings + a locale decimal formatter.
3. Hard-coded fragments in `MarkingWizardScreen.kt`, `MarkingGroupsScreen.kt`,
   `MarkingWizardViewModel.kt:303` → string resources.
4. Locale override writes during composition (`AppLocale.android.kt:14-25`; iOS writes
   NSUserDefaults every recomposition): move into `SideEffect`.

## 19i: Dead code, stale docs, build hygiene (low) — card #30

- **Delete:** `DynamicColor.kt` + actuals; `LocalAppLocale.current` reads;
  `ExampleInstrumentedTest.kt` + `espresso-core`; `CameraPreview`'s `onCameraReady` (rename file to
  `CameraPreview.kt`); unreachable branches `TargetScanner.kt:546-547`, `Trend.kt:99`; unused keys
  `detail_no_score`, `marking_pick_group`, `wizard_lane`, `wizard_show_summary`, `wizard_all_marked`;
  unread side columns in `Series.sq`; the landscape branch `MarkeraScreen.kt:179-203`; unused
  imports / redundant `!!` in the competition screens.
- **Move:** `InMemoryBackendTokenStore` → `commonTest`.
- **Layering:** `SCORE_PICKER_INNER_TEN` down into `series/`; `SeriesApi` stops reusing
  webshooter's client, JSON and `ApiErrorDto`; `Trend.kt` uses the parsed `at` in `PlottedSeries`.
- **Stale comments:** `RingProbe.kt:104-105`; "iOS stub" in `HoleDetector.kt`/`DigitDetector.kt`;
  "~80 MB" ×2; "FP16"/"8400 detections" in `HoleDetector.android.kt`; `MarkeraUiState.kt:13`;
  `ImageOps.kt:7`; `FrameSource.kt:9`; `BackendSession.kt:10`; `AuthSession.kt:13`; "read-only
  admin" in `Admin.kt`/`Server.kt`; `MarkingWizardViewModel.kt:256`. Rename `EllipseFit.kt` →
  `FittedEllipse.kt`.
- **Docs:** `CLAUDE.md` (three ProGuard blocks; locations of `MarkingWizardViewModel` and
  `DataStoreTokenStore`; stale `fastlane beta` line); `README.md:18-24` status lines; eval skill's
  "no automatic scoring"; `/deploy` logcat filter misses `TargetScanner`'s `println`; `.gitignore`
  "98 MB" and `deploy.ps1`; `iosApp/README.md` (add `mac-metadata.sh`, one of `sh`/`bash` throughout).
- **Build:** align `lifecycleViewmodel 2.10.0` with `jetbrainsLifecycle 2.9.5`; Play plugin version
  into the catalog; drop `testImplementation(libs.junit)` if `kotlin("test")` covers it.
- **Line endings:** `.gitattributes` (`* text=auto eol=lf`, `*.bat eol=crlf`) + one renormalise commit.
- **Calibers:** after 19c the app enum is the only list; drop the "lockstep" notes.

## 19j: Competition wizard fixes (med — "fix it now") — card #31

1. A save can write the previous lane's shots to a newly tapped lane and yank the user off it
   (`MarkingWizardViewModel.kt:274-313`): keep the save as a `Job`, cancel on navigation, apply the
   result only if the indices still match; disable the lane strip while saving.
2. Personal history saves the detector's scores, not the confirmed shots
   (`MarkingWizardScreen.kt:129-136,143`).
3. Token refresh signs out on any exception (`SessionRepository.kt:73-87`): only on 400/401; return
   to Login when the session becomes null.
4. Smaller: claim race ignores `stationIndex`; empty `stations` → blank screen; competition list
   loads only page 1; groups ViewModel never reloads; "Series N of M" uses `sortorder`;
   `StationDto.removed` should be `LenientBoolean?`; claim not released when leaving during `Saving`.
5. Config: OAuth secret + base URL (`WebshooterApi.kt:36-40`) into Gradle properties;
   `SHOW_COMPETITION` (`CompetitionFlow.kt:20`) follows `BuildConfig.DEBUG` so it can be exercised.
6. Consistency: one ViewModel lifetime pattern; the common `BackHandler`; kotlinx-datetime instead
   of `SimpleDateFormat`; one copy of the "not active" check.

Check on the phone against test competition 244.

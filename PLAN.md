# Plan: series backend + login + caliber

Requested 2026-09-06. Source of truth for the orchestrated work; one task = one commit.

## Goal

Every scanned series (the detected holes with their scores) is saved automatically to a
backend running in Docker on the Mac mini (`ssh macmini`, 192.168.1.191), tagged with a
timestamp and a caliber. Users log in with Google (Android) or Apple (iOS). The caliber is
shown next to the scanned image; the default "no caliber" (-) triggers a chooser dialog
after holes are detected.

Calibers: `22lr, 32, 38, 357, 45, 44, 9mm, 10mm` plus `-` (none, default).

## Decisions (pinned)

- **Backend = standalone Gradle project in `server/`** (Ktor, SQLite via `sqlite-jdbc`,
  `java-jwt` + `jwks-rsa` for Google/Apple ID-token verification). Not included in the
  root Gradle build: the root build needs the Android SDK at configuration time, which
  would break a `docker build`. Its two DTOs are duplicated in the app rather than shared.
- **Auth model**: the app exchanges a provider ID token once (`POST /auth/google`,
  `POST /auth/apple`) for an opaque backend session token stored in `sessions`;
  every later call sends `Authorization: Bearer <token>`. Google ID tokens live 1 h and
  Apple's 10 min, so re-sending them per request is not viable.
- **Dev auth** (`POST /auth/dev`, only when `DEV_AUTH=true`) lets the mock flavor and
  tests log in without Google Cloud / Apple Developer configuration.
- **Runtime on the Mac mini = Colima + docker CLI via Homebrew** (headless, no Docker
  Desktop GUI login). `brew services start colima` for autostart; `docker compose up -d`
  from a clone at `~/source/Markera`. SQLite file on a named volume.
- **Backend URL** is a Gradle property `markera.backend.url`, default
  `http://192.168.1.191:8090` (8080 on the Mac mini is taken by an nginx site); cleartext allowed for that host only via a network
  security config.
- **Auto-save hook lives in `TargetScanController`** (shared by free marking and the
  competition wizard): after `onHolesDetected` with ≥1 hole it emits a pending series.
  A nav-host-level saver reads the persisted caliber, shows the chooser dialog when it
  is `-`, then posts. Not logged in → no save, a "sign in to save" hint instead.
- **Caliber badge** is drawn inside the shared `TargetScanner` viewport (top-right, frozen
  frame only); tapping it opens the chooser. One component serves both screens.
- Images: task 8 adds `POST /series/{id}/image` as a raw `image/jpeg` body (no multipart), stored
  as `/data/images/<id>.jpg` on the volume; upload happens after the series POST succeeds and
  its failure never fails the series. The recorder stays commonMain: JPEG encoding is injected
  (`encodeJpeg: suspend (PlatformImage) -> ByteArray`) like the caliber persistence.
- **Admin UI** (task 10, requested 2026-09-06): lives in the Ktor server, plain server-rendered
  HTML from string templates (no template/HTML dependency), read-only, no auth. Gated by
  `ADMIN_UI=true` so a future public deployment cannot expose it by accident.
- **Save timing** (task 11, requested 2026-09-06): a scan is only *pending* while its frozen
  frame is on screen; the POST (and the caliber dialog when the caliber is `-`) happens when the
  user returns to the camera.
- **Edited scores** (task 15, requested 2026-09-06, for training data later): the score
  pickers are *positional* — `topScores[i]` is built from `scores[i]` (`MarkeraViewModelImpl.
  onHolesDetected`, first `SCORE_PICKER_COUNT` = 5 holes, padded with 0) and edited in place —
  so picker slot `i` maps back onto detected hole `i`. Each saved hole carries both the value
  the user confirmed (`ring`, `innerTen`, the existing fields — history totals and the admin
  pages keep using them) and what the detector said (`detectedRing`, `detectedInnerTen`,
  `null` when the detector said nothing). Three kinds of hole, all derived, nothing stored
  for the kind itself: **detected** (`detectedRing != null`; `edited` = the confirmed pair
  differs from the detected pair), **manual** (task 18: placed by a tap on the frozen photo,
  so it has `x`/`y`/`distanceMm` scored by the same ellipse geometry, but `detected*` null),
  **typed** (a score entered in an empty picker slot: `x`/`y`/`distanceMm` null — those become
  nullable — and `detected*` null). A detected hole the user sets to 0 is kept as a 0 (a
  false-positive signal). Detected holes beyond the 5 picker slots are saved unedited.
  Merging happens in the app (`SeriesRecorder.commit(topScores)`); the server only stores.
  Ordering: server half first and deployed (the server JSON is strict about unknown keys),
  then the app half. `HitScore` gains `manual: Boolean = false` so the DTO mapping and the
  overlay colour know the kind.
- **Production hardening** (requested 2026-09-07, tasks 24–30): the admin UI is gated by a
  password (`ADMIN_PASSWORD`, HTTP Basic, user `admin`; unset = admin routes off — replaces the
  `ADMIN_UI` flag) and `DEV_AUTH=false` on the Mac mini, so the mock flavor can no longer sign in
  against it (point it at a local server with `DEV_AUTH=true`, or re-enable temporarily).
  Pagination is cursor-based: `GET /series?limit=N&before=<id>` (newest first, default 50, max
  200; response stays a plain array). `DELETE /series/{id}` (owner only; holes + image go too)
  and `DELETE /account` (everything the user owns, sessions included; the app signs out after).
  Manual markers are removed by tapping them again (within the same 24 dp gap); a removed hole
  shifts the picker slots after it left by one, so edits stay aligned. Release signing: upload
  keystore at the repo root (`keystore`, gitignored) read from `keystore.properties`; Play App
  Signing re-signs, so the Play-generated key's SHA-1 is what goes into the Android OAuth client.
- Sub-agents run on `opus`.

## User actions needed (cannot be done by the orchestrator)

- [x] Google Cloud Console (done 2026-09-06; Web client id in local.properties + Mac .env, Android client with debug SHA-1): an OAuth **Web** client ID (used as the token audience on the
      backend and as `serverClientId` in the app) and an **Android** client ID with the
      debug/release SHA-1 for `se.kjellstrand.markera`. Put the Web client ID in
      `local.properties` as `markera.google.client.id=...` and in the Mac mini's
      `server/.env` as `GOOGLE_CLIENT_ID=...`.
- [ ] Apple Developer: enable "Sign in with Apple" on the iOS bundle id; set
      `APPLE_BUNDLE_ID` in `server/.env`.
- [ ] Play Console → Setup → API access: link a Cloud project, create a service account with the
      "Release manager" role, download its JSON key to `play-account.json` at the repo root
      (gitignored). Needed by `/release`.
- [x] (done 2026-09-08: Android OAuth client with the Play SHA-1 saved; Google sign-in + Historik verified on the Play-installed 1.2.0 (3)) After the first upload with Play App Signing: copy the **App signing key certificate**
      SHA-1 (Play Console → Setup → App signing) into the Android OAuth client in Google Cloud
      Console (or add a second Android client with it). Until then the release build's Google
      sign-in fails. The upload key's SHA-1 is printed by task 26 for reference.
      Read from the Play-installed 1.2.0 (3) on 2026-09-08: app signing key SHA-1
      `92:96:43:90:D0:7B:94:74:B7:BA:83:CE:7E:10:79:C4:E0:A0:A3:49` (CN=Android, O=Google Inc.).
- [x] Privacy policy: Play Console now points at `https://markera.duckdns.org/privacy` (swapped
      2026-09-08 via the browser; saved as a pending change — the user sends it for review from
      Publishing overview, best together with the Data safety fix). All 10 App content declarations
      are actioned.
- [ ] Data safety declaration is wrong (says "App doesn't collect or share data", "Data isn't
      encrypted"): the app collects account info (Google sign-in name + subject), photos (target
      frames) and user content (series), all encrypted in transit (HTTPS), with in-app deletion. Fix
      in Play Console → App content → Data safety → Manage; the account-deletion URL
      `https://markera.duckdns.org/delete-account` (task 61) goes into that form's data-deletion
      section.
- [x] Review test account for Play's App access declaration (done 2026-09-08: Google account
      "Markera Review", signed in and tested by the user; credentials live only in Play Console).

## Tasks

| # | Task | Status |
|---|------|--------|
| 1 | `server/`: Ktor backend (auth google/apple/dev, series POST/GET, health), SQLite, tests, Dockerfile + compose | done |
| 2 | Mac mini: install Colima + docker, clone repo, `docker compose up -d`, autostart, verify `/health` over LAN | done |
| 3 | App common: `Caliber` enum, `SeriesApi` client + DTOs, backend token store, unit tests (MockEngine) | done |
| 4 | App Android: sign-in (`camera` flavor Google via Credential Manager, `mock` flavor dev auth), account row on Home, persisted session | done |
| 5 | App: auto-save flow (controller emits series → saver → caliber dialog → POST), caliber badge in viewport, save status; emulator end-to-end against the Mac mini | done |
| 6 | iOS: Apple sign-in actual (AuthenticationServices), ATS exception; compile on the Mac mini | done (compiled + linked only; no iOS host app yet) |
| 7 | App Android: series history screen (Home card → list of saved series: time, caliber, total, per-hole scores; loading/empty/error/signed-out states) | done |
| 8 | Image upload: server `POST/GET /series/{id}/image` (raw JPEG on the volume, `hasImage` in the DTO); app posts the scanned snapshot (JPEG ≤1024 px) after a successful series save; thumbnails in history | done |
| 10 | Admin web UI in the server (`/admin`): users → their series → series details with holes + image; server-rendered HTML, no login, enabled only by `ADMIN_UI=true` (LAN-only deployment); tests; deploy on the Mac mini | done |
| 11 | App: defer the auto-save until the user leaves the frozen frame ("Tillbaka till kamera" in free marking; moving on from a scanned lane in the wizard); rescans discard; caliber dialog moves to that moment. Same commit (amended 2026-09-06): the caliber chip shows only the caliber, moves out of the viewport to the left of the total score, equal height with it; save outcome (and the signed-out hint) becomes a Toast | done |
| 12 | App: score pickers stop showing the previous/next value; tapping a score opens a dialpad-style dialog (0–10 + X) that sets it (queued 2026-09-06 behind task 11, same results area) | done |
| 13 | Server: users get a `name` (Google: `name` claim, else `email`; Apple: `email`; dev: the subject), refreshed at every login, shown on the admin pages | done |
| 14 | App: caliber dialog rows half as tall, "Ingen vald" for no caliber, dialpad order 0–10 then X (orchestrator-applied, 2026-09-06) | done |
| 15a | Server: holes gain `detected_ring`/`detected_inner_ten` (nullable) and `x`/`y`/`distance_mm` become nullable (table rebuilt in the migration); DTO `Hole` mirrors it with defaults so old clients still post; admin series page marks edited holes (detected → chosen) and the series lists show an edited count; tests; deploy | done |
| 15b | App: `SeriesRecorder.commit(topScores)` merges the picker values into the pending holes per the pinned decision (`SeriesDtos` helper, unit-tested); `MarkeraScreen` (Spara) and the wizard (lane change) pass the current `topScores`; phone check that an edited series shows as edited on `/admin` | done |
| 16 | App: remove the "Dela" share button (nothing to share for now); orchestrator-applied | done |
| 17 | Admin UI: whole table rows clickable wherever a row has exactly one target page (users → user, series → series) | done |
| 18a | Admin series page: draw the holes on the photo (positions are in source-image px of the uploaded JPEG, same aspect, so scale by the rendered size), detected and manual in different colours, with the confirmed score as label; typed holes have no position and stay list-only; the image upload takes `?width=&height=` (the scored frame's size) so markers scale onto the JPEG | done |
| 18b | App: manual marking — a tap on the frozen frame (not on an existing marker) adds a `manual` hole at that image point, scored with the current centre + ring, appended to `scores`, filling the next free picker slot, re-emitted to the recorder as the pending series; overlay draws manual markers in their own colour; no tap effect without geometry; the image upload sends `?width=&height=` (source frame) | done (phone-verified 2026-09-07: orange marker, saved as `manual`, markers drawn on `/admin/series/30`) |
| 19 | App: "Återställ" button beside "Spara" on the frozen frame (requested 2026-09-06 as "next to Detektera" — Detektera only exists in the live view, the frozen view shows Spara in its place): discards the scan (`recorder.clear()`, `clearResults()`, unfreeze, back to the live camera) without saving, for when the geometry/detection went wrong (orchestrator-applied) | done |
| 20 | App: hide the "Tävling" (competition) entry on Home until the user reprioritises it (requested 2026-09-06); the code stays, only the entry point goes (`SHOW_COMPETITION` in `AppNavHost`) | done |
| 21 | Home texts: "Fri markering" → "Markera", "Skanna en tavla utan tävling" → "Scanna en tavla" (orchestrator-applied with task 20) | done |
| 22 | Admin: drop the "created" column from the users list (orchestrator-applied, 2026-09-06) | done |
| 23 | Mock flavor: no auto-scan after Spara/Återställ (new image, wait for Detektera) so the flow mirrors the camera; first frame and preview tap still auto-scan (orchestrator-applied, 2026-09-07) | done |
| 24 | Server: lock-down + deletes + pagination — `ADMIN_PASSWORD` basic auth replaces `ADMIN_UI`; `DELETE /series/{id}`; `DELETE /account`; `GET /series?limit&before`; tests; Mac mini `.env`: `ADMIN_PASSWORD=…` set on deploy, `DEV_AUTH=false` flipped 2026-09-07 after 25b | done |
| 25a | App: history pagination (loads the next page when the list end is reached) and series delete (long-press a row → confirm dialog → `DELETE`, row disappears) | done — verified on the phone 2026-09-07 (series 12 deleted, 5 left) |
| 25b | App: account deletion — "Radera konto" on Home under the signed-in row, confirm dialog naming what goes, `DELETE /account`, then sign out; camera + mock | done — verified on the phone 2026-09-07 with the dev account (user 3 wiped) |
| 25c | App: remove a manual marker by tapping it again; picker slots shift; recorder re-published | done — verified on the phone 2026-09-07 (mock flavor, orange marker added then removed) |
| 26 | Release signing: upload keystore + `keystore.properties` (gitignored), `signingConfigs.release` wired when the file exists, upload-key SHA-1 `56:A5:24:7E:75:96:CB:58:48:D9:63:F4:6E:AA:71:F6:36:B3:98:A2` (valid to 2054, alias `markera`), release APK verified signed (orchestrator-applied 2026-09-07) | done |
| 27 | Store assets under `store/`: Swedish short/full description, 512 px icon, 1024×500 feature graphic, phone screenshots; launcher icon replaced (target rings, adaptive vector + legacy webps from `store/gen_assets.py`) | done — a results-screen screenshot with a real target is still wanted |
| 28a | Server: `PUT /series/{id}` (owner only, body = the POST body, replaces caliber/timestamp/holes → 204) + `detectedX`/`detectedY` on holes (migration + backfill from x/y where detected_ring is set); admin kind shows "moved" | done 2026-09-07, deployed |
| 28b | App: series detail screen from a history row — photo with markers, time, caliber, total, hole list (score, mm, kind); tap a score to edit it, drag a marker to move it; Spara → PUT; history refreshes | done — verified on the phone 2026-09-07 (series 11: 10 → 9 + moved) |
| 28c | Admin web: the series page becomes editable — score inputs per hole, drag a marker to move it, click the photo to add a hole, delete per row, Save → `PUT /admin/series/{id}` (Basic auth; same body/logic as the app PUT) | done 2026-09-07, deployed |
| 29 | Admin: score split into read-only `detected` and editable `manual` columns; manual empty = use detected; detected greyed out when overridden; marker labels 13 px; photo 960 px right of the table (requested 2026-09-07) | done, deployed |
| 30 | App: detail screen score split — read-only detected cell (struck through when overridden) + tappable manual cell; dialpad gains "Använd detekterad" to revert to the detected score (requested 2026-09-07) | done — verified on the phone 2026-09-07 (series 11 reverted 9 → 10, saved) |
| 31 | Server + admin: store the target geometry per series — digit-row centre and the 6/7 ring ellipse (`geometry: {centreX, centreY, ringCx, ringCy, ringSemiMajor, ringSemiMinor, ringRotationRad}`, source-image px, nullable, on POST/PUT/GET/admin); admin photo draws the centre and the ring; moving/adding a hole recomputes `distanceMm` from the geometry (JS port of the `scoreHits` un-projection) instead of nulling it (requested 2026-09-07) | done 2026-09-07, deployed — verified in Chrome on series 32: ring + centre drawn, dragging a marker recomputed 48 → 66 mm ("moved"); the JS mm matches the app's stored value to 7 digits |
| 32 | App: send the geometry with every scan (`onSeriesDetected` gets centre + ring), draw centre + ring on the detail photo, recompute `distanceMm` on drag from a shared pure `distanceMm(x, y, centre, ring)` in `vision/` that `scoreHits` also uses; old series without geometry keep nulling (requested 2026-09-07) | done 2026-09-07 — verified on the phone with series 31 (ring + centre drawn; dragged hole 32 → 78 mm, "flyttad", saved and read back 77.9 mm) |
| 33 | Admin: no "Add hole" button when the photo is placeable (clicking the photo is the only way in, so no empty typed row is left behind); photo margin/border moved onto `.shot` so a click, the markers and the ring all map to the exact image pixels — the new hole is centred on the pointer tip (requested 2026-09-07, orchestrator-applied) | done, deployed |
| 34 | App detail screen: tap the photo to add a hole (scored from the geometry when present, else dialpad), delete icon per row (Spara disabled with no holes), pinch zoom + pan on the photo with hole drag still working while zoomed (requested 2026-09-07) | done 2026-09-07 — add (tap at ring 8 → "8, 53 mm, manuell", total 48 → 56) and delete verified on the phone; **pinch zoom awaiting the user** (adb cannot inject multitouch on the unrooted phone) |
| 35 | App detail screen: tighter row spacing in the hole list; the kind column says "detekterad" for an unedited detected hole (today it is blank while hand-placed ones say "manuell") (requested 2026-09-07) | done — verified on the phone 2026-09-07 |
| 36a | Statistik model (commonMain, JVM-tested): `targetOffsetMm` (un-project + rotate back, `distanceMm` = its hypot), `series/stats/SeriesStats.kt` — `StatsFilter` (caliber, from/to, exact hit count, default 5), `plotSeries` (geometry required, positioned holes, sorted oldest→newest with `age` 0..1), `statistics()` (mean distance to centre, mean hole-to-hole, mean score, mean group size, mean point of impact, tens share, best/worst). Plan: `~/.claude/plans/compiled-spinning-boole.md` (requested 2026-09-07) | done 2026-09-07 (134 tests) |
| 36b | Statistik screen (androidMain): Home card + `Screen.Statistics`; loads all pages; filter chips (caliber, date presets 7/30/365/alla + DateRangePicker, hits stepper); drawn target to ring 5 with hits coloured old (blue) → new (green) + legend; measurements list (mm whole numbers, score 1 decimal) | done — verified on the phone 2026-09-07 (series 31: plot matches the photo, 34 mm mean, 48.0, 60 % tens; hits stepper, presets and the custom range picker all work) |
| 37 | Statistik screen: draw the mean and the median point of impact on the target (distinct markers + legend entries); add `medianXMm`/`medianYMm` (component-wise medians) to `SeriesStatistics` and list the median under the mean in the measurements (requested 2026-09-07) | done — verified on the phone 2026-09-07 (2 series: + at -17/16 mm, × at -23/18 mm, both drawn where the numbers say) |
| 38 | First Google Play release (`/release`, camera flavor): add the Play Publisher plugin + `play {}` block, bump 1.0 (1) → 1.1.0 (2), Swedish release notes, `bundleCameraRelease`, upload to the internal track, tag `v1.1.0`. Blocked on the user: `play-account.json` (Play Console → API access → service account "Release manager"); the Play App Signing SHA-1 must be in the Android OAuth client or Google sign-in fails on the Play build (requested 2026-09-07) | bundle 1.1.0 (2) built + tagged v1.1.0; build 2 uploaded by hand, rejected for 16 KB pages (→ task 39); 1.2.0 (3) built + tagged v1.2.0, 1.2.0 (3) uploaded to the internal track via the plugin 2026-09-07 after the user granted the service account release rights. Review test account created and tested by the user 2026-09-08 (Google account "Markera Review", credentials only in Play Console → App access). App signing SHA-1 → Android OAuth client done 2026-09-08 (sign-in verified on the Play build). 1.3.0 (4) uploaded to the internal track 2026-09-08 (tag v1.3.0: R8, portrait lock, full-res capture, cache, export; fresh Statistik/Historik screenshots pushed with the listing). Still on the user: Data safety declaration + Send for review, promote 1.3.0 |
| 39 | Play rejected build 2: "does not support 16 KB memory page sizes" — `libonnxruntime.so` 1.19.2 is 4 KB-aligned (every other .so is fine). Bump `onnxruntime-android` to 1.29.0 (16 KB aligned on all ABIs), re-verify detection on the phone, then release 1.2.0 (3) (requested 2026-09-07) | done — mock flavor on the phone: inference 5.6 s, raw=3 kept=3 digits=9 ring=true, no crash |
| 40 | Store listing via the Play Publisher plugin: `composeApp/src/main/play/` (the plugin ignores `androidMain/play`, release notes moved too) — sv-SE title/short/full description, icon, feature graphic, `store/crop_screenshots.py` for phone screenshots (Play needs height ≤ 2× width). `publishCameraReleaseListing` (requested 2026-09-07) | done — listing + 4 screenshots (hem, serie, statistik, historik) published 2026-09-07 |
| 41 | Admin UI: delete a series — `DELETE /admin/series/{id}` (Basic auth, same as PUT; drops holes, row and image) + a "Delete" button on the series page with a `confirm()`, redirecting to the owner's user page; ApiTest coverage (requested 2026-09-07) | done — 44 server tests; deployed to the Mac mini 2026-09-07 |
| 42 | App: mirror the admin delete on the series detail screen — a visible "Radera" action (top bar or beside Spara) with the existing `history_delete_*` confirm dialog, `api.deleteSeries`, then back to a history list that no longer shows the series (long-press delete in the list stays) (requested 2026-09-07) | done — verified on the phone 2026-09-07 |
| 43 | Statistik screen polish: "Välj…" chip moves onto the hits row; − and + become real icon buttons placed left of the "N träffar" text; tighter spacing between the filter rows; ring digits 5–9 also drawn along the vertical axis of the target (requested 2026-09-07) | done — verified on the phone 2026-09-07 |
| 44 | Statistik: every filter row starts with an "Alla" chip, selected by default — date presets reordered (Alla first, already the default); hits row gets an "Alla" chip left of −/+ meaning any hit count (`StatsFilter.hits: Int? = null`, null = no count filter; tapping −/+ selects a count, tapping Alla clears it); caliber row already has it (requested 2026-09-07) | done — verified on the phone 2026-09-07 (136 tests) |
| 45 | Scan screen top bar reported missing ("we dont show the top bar with the title and back arrow") — on the debug build 2026-09-07 the phone shows ← + "Markera" + debug toggle in both live and frozen states (screenshots `scan45*.png`); needs the user to say where it is missing (Play build 1.2.0? landscape? permission prompt has no bar) (requested 2026-09-07) | done 2026-09-07 (verified on the phone): the live preview was a SurfaceView, which punches its own window hole and ignores clipping, so the fill-cropped 4:3 frame spilled over the top bar; PreviewView now `COMPATIBLE` (TextureView) + `clipToBounds` on the preview box |
| 46 | Scan screen: same hole editing + pinch zoom as the series detail — pinch/pan on the frozen frame, drag any hole to move it (rescored via the geometry; a moved detected hole keeps its detected ring/position), long-press a hole to delete it, tap empty to add (existing); the pending series and the pickers follow every edit (requested 2026-09-07) | done 2026-09-07 (gate 141 green; phone check pending — phone not connected): `PhotoGestures.kt` shared with the detail screen, `HitScore.original`, `moveHit`/`removeHit` |
| 47 | Scores only from hole position: remove score editing by tapping the digit/picker (scan screen, series detail, wizard?) — a score changes only by moving a detected hole or adding + positioning a new one, then it is computed from the geometry, so every saved score is accurate for training (requested 2026-09-07) | done 2026-09-07 (gate 143; detail drag → "9 → X, flyttad", re-sort, no dialpad verified on the phone; scan-screen drag needs a target in front of the camera): sort lives in `MarkeraViewModelImpl.withHoles` and `SeriesDtos.withNewHole/moveHole`; `scoreHits` now keeps input order (fixes a 46 bug: detections/scores were misaligned) |
| 48 | Statistik screen: a "?" (top bar action) opening a dialog that explains each value shown (mean distance, hole-to-hole, group size, mean/median point of impact, tens share, best/worst, colour scale) (requested 2026-09-07) | done 2026-09-07 (gate 143; phone check with 49/50): `HelpDialog` in `StatsScreen.kt`, `stats_help_*` strings |
| 49 | Statistik: the mean and median markers in the target image must be the same size and style as in the legend (requested 2026-09-07) | done 2026-09-07 (gate 143; phone check with 50): shared `drawMark` in dp for target + legend, × arms normalised to the + extent |
| 50 | Statistik: widen the date colour scale of the hits and make it pop against the white/black target (requested 2026-09-07) | done 2026-09-07 (gate 143; 48-50 verified on the phone, `stats48.png`/`stats50.png`): five-stop `HIT_SCALE` violet→blue→cyan→yellow→orange-red, same list feeds the legend bar |
| 51 | Scan screen: after Detektera with fewer than 5 holes, a press on the photo adds a hole there; holes drag and drop. Tap-add exists since task 33 and drag since 46 (fca2b58) — open: is "fewer than 5" a hard cap on adding? (requested 2026-09-07) | done 2026-09-07 (gate 143; user: "adding holes should stop at 5"): tap-add refused at `SCORE_PICKER_COUNT` holes in `addManualHit` and the detail `onAdd`; the "sixth manual hit" test replaced by `addManualHitStopsAtFiveHoles` |
| 52 | Keep the score digits sorted (highest first) whenever any value changes — after a hole is moved, added or removed, on the scan screen and the series detail (requested 2026-09-07) | done with 47 |
| 53 | Long-press deletes a hole and its digit goes back to the default (0). Already so since 46: `removeHit` → `withHoles` re-sorts and refills the pickers with 0 (requested 2026-09-07) | done (covered by 46/47) |
| 54 | Delete the old series without geometry (pre 2026-09-07 test data) from the production DB — user: "old series without geometry can be deleted, we dont need old test data"; no rescan path needed (requested 2026-09-07) | awaiting the user: 13 series without geometry (ids 1, 4-11, 13-16, all 2026-09-06); the classifier blocked my admin DELETE curl loop — the user runs it (command in the chat) or deletes them in the admin UI |
| 55 | Delete the mock flavour ("we dont need it anymore"): drop the `source` flavor dimension, `androidMock`/`src/mock`, `prepareMockFrames`, the dev sign-in, fold `androidCamera`/`src/camera` into the main source sets; task names lose the flavor (`assembleDebug`, `testDebugUnitTest`, `bundleRelease`, `publishReleaseBundle`); update CLAUDE.md, README, `/deploy`, `/release`; server `POST /auth/dev` stays (gated by DEV_AUTH) (requested 2026-09-07) | done (2026-09-07) |
| 56 | Release hardening: R8 minify + resource shrinking on `release` with keep rules for ONNX Runtime, ML Kit, kotlinx-serialization DTOs, Ktor, Credential Manager/googleid; native debug symbols in the bundle (`ndk.debugSymbolLevel`) so Play shows symbolised crashes; mapping upload via the Play plugin; verify a release build on the phone end to end (scan, sign-in, save) (requested 2026-09-07) | done (2026-09-07; release build verified on the phone after a second keep rule for the ML Kit registrar constructors) |
| 57a | Local cache, server side: `updated_at` + soft-delete `deleted_at` on `series`, `GET /series?since=` delta with tombstones, paged list ordered by id (cursor bug), `updatedAt`/`deleted` on the wire, ApiTest cover, deploy (plan `~/.claude/plans/compiled-spinning-boole.md`; requested 2026-09-07, user chose read-cache + delta refresh in SQLite) | done (2026-09-07; 49 server tests; DELETE /account still hard-deletes since sessions die with it) |
| 57b | Local cache, app side: SQLDelight store (series json + sync stamp), `SeriesRepository` (StateFlow + `refresh()` delta + image file cache in `cacheDir`) hoisted in `AppNavHost`; History/Statistik/Detail read it, writes go through it, sign-out clears it; JVM tests with in-memory driver + ktor mock | done (2026-09-07; 151 tests; phone-checked 2026-09-08: second Historik open rewrote no cached image, Historik + Statistik work in airplane mode, a Detail edit and a delete show in the list at once and the sync stamp advances; sign-out clearing covered by the JVM test only) |
| 58 | Export from Historik: "Exportera" builds one ZIP (`series.csv`, `holes.csv`, `images/<seriesId>.jpg`) in `cacheDir` and shares it through `ACTION_SEND` via a FileProvider content URI (`application/zip`); CSVs semicolon-separated with a header, UTF-8 BOM so Excel opens them; data from the local cache once 57b lands (else from the API pages) (requested 2026-09-07) | done (2026-09-07; 155 tests; phone-checked 2026-09-08: share sheet opens with `markera-export-<stamp>.zip`, zip holds series.csv/holes.csv/images) |
| 59 | Full-resolution scan capture: CameraX `ImageCapture` cropped to the square viewport (~3000² on the OnePlus) feeds both detection and the saved JPEG (`IMAGE_MAX_DIM` 3072, q90, server `MAX_IMAGE_BYTES` 10 MB); `FrameSource.capture()` suspends, preview bitmap only as fallback; History/Detail decode with `inSampleSize`. Reason: training photos are 12 MP (hole ~80 px), the 1024 px upload gives ~18 px (plan `~/.claude/plans/compiled-spinning-boole.md`; requested 2026-09-07, do before 57a/b) | done (2026-09-07; phone shows snapshot 3000x3000, capture 797 ms, OCR 288 ms; accuracy on a real target awaits the user) |
| 60 | Screen rotation must not reset navigation: the sealed-class back stack in `AppNavHost` (and the frozen scan/result state) has to survive the Activity recreation, e.g. `rememberSaveable` for the stack or `configChanges` for orientation (requested 2026-09-08, seen on the phone: rotating on the scan screen lands on Home) | done (2026-09-08; `android:screenOrientation="portrait"` on MainActivity — the layout is portrait-only and the camera viewport is bound with one rotation, so a rotation never recreates the Activity; phone reports the portrait request and adb cannot force a rotation on the OnePlus, so a hand rotation by the user is the final check) |
| 61 | Public account-deletion page on the backend for the Play listing: `GET /delete-account` (no auth) serves a small static HTML page, Swedish + English, saying how to delete in the app (Home → Radera konto), what goes (account, every series, image, session; immediately, nothing kept) and how to ask by mail when the app is unavailable (`CONTACT_EMAIL` env, line omitted when unset); ApiTest cover; deploy (requested 2026-09-08) | done (2026-09-08; 51 server tests; live at `https://markera.duckdns.org/delete-account`, no `CONTACT_EMAIL` set so the page points at the Play listing's developer e-mail) |
| 62 | Privacy policy served by the backend (`GET /privacy`, Swedish + English, shares the HTML shell with `/delete-account`) describing what Markera really stores — Google sign-in subject + display name, saved series (time, caliber, holes/scores), target photos, session tokens; on-device OCR/detection, no analytics/ads; own server in Sweden; kept until the user deletes (in-app or `/delete-account`); export via Historik; rights + IMY — replacing the generic flycricket policy in Play Console (requested 2026-09-08) | done (2026-09-08; 53 server tests; live at `https://markera.duckdns.org/privacy`; the user pastes it into Play Console → App content → Privacy policy) |
| 63 | Statistik filter rows: the date-range chip (`Välj…`) moves up onto the date row after `1 år`; `7 dagar`/`30 dagar` become `7 d`/`30 d`; the hit-count row loses the +/- buttons and the count text — it is just `Alla` and a `5 träffar` chip, so the hole-count filter is either five or all (requested 2026-09-08) | done (c8b3766; phone-checked 2026-09-08) |
| 64 | Help icon (?) in the top bar of every screen, like Statistik's, each opening a dialog with useful per-screen guidance: Home (what the app does, sign-in, caliber, Radera konto), scan screen (aim, scan, tap to add / long-press to delete / drag, pinch zoom, Spara, caliber), Serie/Detail (edit holes, delete series), Historik (list, export zip, tap for details) (requested 2026-09-08) | queued |
| 65 | Statistik chips: a selected chip must stand out clearly — today the outline on unselected chips makes them look almost selected; give selected chips a solid accent fill (and drop or fade the border on unselected ones) (requested 2026-09-08) | done (2026-09-08; primary fill + onPrimary label when selected, surfaceVariant pill without border otherwise; phone-checked) |
| 66 | Statistik: the Medel (+) and Median (×) markers must use a colour nowhere near the hole date scale (purple→cyan→green→yellow→orange→red) — white with a thin dark outline, on the target and in the legend alike (`drawMark`) (requested 2026-09-08) | queued |
| 9 | HTTPS for the backend (queued 2026-09-06 as "if the backend ever leaves the LAN") | done 2026-09-07 — `https://markera.duckdns.org` via the Mac mini's host Caddy (block appended over ssh, backup `Caddyfile.bak-20260907`); container bound to 127.0.0.1:8090; app default URL switched, cleartext config removed |

## API (server)

```
GET  /health                       -> 200 {"status":"ok"}
POST /auth/google {idToken}        -> 200 {token, userId}
POST /auth/apple  {idToken}        -> 200 {token, userId}
POST /auth/dev    {subject}        -> 200 {token, userId}   (DEV_AUTH=true only)
POST /series      Bearer, {timestamp (ISO-8601), caliber, holes:[{x?,y?,ring,innerTen,distanceMm?,detectedRing?,detectedInnerTen?}]}
                                   -> 201 {id}
GET  /series      Bearer, ?limit=N&before=<id>  -> 200 [{id, timestamp, caliber, holes:[...], hasImage, imageWidth, imageHeight}] newest first
DELETE /series/{id}  Bearer          -> 204 (owner only; holes + image removed)
DELETE /account      Bearer          -> 204 (user, sessions, series, holes, images)
POST /series/{id}/image  Bearer, raw image/jpeg body (≤ 5 MB) -> 204
GET  /series/{id}/image  Bearer     -> 200 image/jpeg | 404
POST /series/{id}/image?width=W&height=H   optional: size of the scored frame (markers on the admin photo)
GET  /admin, /admin/users/{id}, /admin/series/{id}[/image]   HTML, HTTP Basic admin:$ADMIN_PASSWORD; off when unset
```

## HTTPS recipe (task 9, apply the day the backend leaves the LAN)
- **Series detail + editing** (requested 2026-09-07, tasks 28a/28b): edits to a saved series stay
  "confirmed vs detected", no flag column — a score edit changes `ring`/`innerTen` and the admin
  keeps showing `8 → 9`; a *moved* marker changes `x`/`y` while the new `detectedX`/`detectedY`
  keep where the detector put it (admin kind gets ", moved"). Moving a marker nulls `distanceMm`
  until tasks 31/32 land (they store the centre + 6/7 ring per series and recompute it). `PUT /series/{id}` (bearer, owner) and `PUT /admin/series/{id}` (Basic) take the
  same body as the POST and replace the series' caliber, timestamp and holes; the admin page edits
  in the browser with a small inline script (no dependencies), added holes there are manual
  (no detected values). UI rule (user, 2026-09-07): measurements and positions (mm, x, y) show
  no decimals, in the app and on the admin pages.


No app code changes: OkHttp (Android) and Darwin (iOS) trust public CAs already.

1. The Mac mini already runs Caddy on the host (`/opt/homebrew/etc/Caddyfile`, root launchd
   service, Let's Encrypt for thinnikatech.se; 80/443 are already forwarded from the router, WAN
   IP 92.34.29.118 on 2026-09-07). So: no Caddy container. Add a DNS A record for the chosen
   hostname (chosen: `markera.duckdns.org`, DuckDNS) → the WAN IP, then a Caddyfile block
   `<host> { reverse_proxy 127.0.0.1:8090 }` and `sudo caddy reload --config /opt/homebrew/etc/Caddyfile`.
2. `server/docker-compose.yml`: publish `127.0.0.1:8090:8080` so plain HTTP is only reachable
   through Caddy on the same box.
3. App: `markera.backend.url=https://<host>` in `gradle.properties`; drop the cleartext
   entry for 192.168.1.191 from `network_security_config.xml`.
4. `DEV_AUTH=false` and a strong `ADMIN_PASSWORD` in `server/.env` (done in task 24) — `/auth/dev`
   is a free login and `/admin` shows every user's series.

## Follow-ups / out of scope

- Legacy series (saved before the upload carried the frame size) show no markers on the admin
  photo. Frame sizes are known: camera flavor on the OnePlus = 1440×1440 (`previewView.bitmap`
  of the square viewport, confirmed in logcat 2026-09-07), mock flavor = 1203×1203 (dataset
  images downscaled to 1600 on the long side, then centre-squared; two 3072×4096 images give
  1200). One-off SQL on the Mac mini (blocked for the orchestrator, user to run):
  `docker run --rm -v server_markera-data:/data alpine sh -c "apk add -q sqlite && sqlite3 /data/markera.db \"UPDATE series SET image_width=1440, image_height=1440 WHERE image_width IS NULL AND user_id=7; UPDATE series SET image_width=1203, image_height=1203 WHERE image_width IS NULL AND user_id IN (3,9);\""`

- Wizard: corrections made in the wizard's own Confirm picker (`wizardVm.updateShot`) are not
  what `resetScanner(save = true)` passes to `recorder.commit` (it passes the scanner's
  `topScores`), so a series saved from the wizard carries the detector values, not the lane's
  edits. Low priority while the competition entry is hidden (task 20); fix = commit with the
  lane's shots at lane confirmation.

- ~~Image upload alongside a series~~ → task 8.
- ~~Series history screen in the app~~ → task 7.
- ~~HTTPS / exposing the backend outside the LAN~~ → task 9.

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
- Sub-agents run on `opus`.

## User actions needed (cannot be done by the orchestrator)

- [x] Google Cloud Console (done 2026-09-06; Web client id in local.properties + Mac .env, Android client with debug SHA-1): an OAuth **Web** client ID (used as the token audience on the
      backend and as `serverClientId` in the app) and an **Android** client ID with the
      debug/release SHA-1 for `se.kjellstrand.markera`. Put the Web client ID in
      `local.properties` as `markera.google.client.id=...` and in the Mac mini's
      `server/.env` as `GOOGLE_CLIENT_ID=...`.
- [ ] Apple Developer: enable "Sign in with Apple" on the iOS bundle id; set
      `APPLE_BUNDLE_ID` in `server/.env`.

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
| 17 | Admin UI: whole table rows clickable wherever a row has exactly one target page (users → user, series → series) | queued |
| 18a | Admin series page: draw the holes on the photo (positions are in source-image px of the uploaded JPEG, same aspect, so scale by the rendered size), detected and manual in different colours, with the confirmed score as label; typed holes have no position and stay list-only | queued |
| 18b | App: manual marking — a tap on the frozen frame (not on an existing marker) adds a `manual` hole at that image point, scored with the current centre + ring, appended to `scores`, filling the next free picker slot, re-emitted to the recorder as the pending series; overlay draws manual markers in their own colour; no tap effect without geometry | queued |
| 19 | App: "Återställ" button beside "Spara" on the frozen frame (requested 2026-09-06 as "next to Detektera" — Detektera only exists in the live view, the frozen view shows Spara in its place): discards the scan (`recorder.clear()`, `clearResults()`, unfreeze, back to the live camera) without saving, for when the geometry/detection went wrong | queued |
| 20 | App: hide the "Tävling" (competition) entry on Home until the user reprioritises it (requested 2026-09-06); the code stays, only the entry point goes | queued |
| 9 | HTTPS for the backend (queued 2026-09-06 as "if the backend ever leaves the LAN") | deferred — not needed on the LAN; recipe pinned below |

## API (server)

```
GET  /health                       -> 200 {"status":"ok"}
POST /auth/google {idToken}        -> 200 {token, userId}
POST /auth/apple  {idToken}        -> 200 {token, userId}
POST /auth/dev    {subject}        -> 200 {token, userId}   (DEV_AUTH=true only)
POST /series      Bearer, {timestamp (ISO-8601), caliber, holes:[{x?,y?,ring,innerTen,distanceMm?,detectedRing?,detectedInnerTen?}]}
                                   -> 201 {id}
GET  /series      Bearer           -> 200 [{id, timestamp, caliber, holes:[...], hasImage}]
POST /series/{id}/image  Bearer, raw image/jpeg body (≤ 5 MB) -> 204
GET  /series/{id}/image  Bearer     -> 200 image/jpeg | 404
GET  /admin, /admin/users/{id}, /admin/series/{id}[/image]   HTML, no auth, only with ADMIN_UI=true
```

## HTTPS recipe (task 9, apply the day the backend leaves the LAN)

No app code changes: OkHttp (Android) and Darwin (iOS) trust public CAs already.

1. Give the Mac mini a public hostname (a domain or DDNS) and forward 443 → the Mac.
2. Add a `caddy` service to `server/docker-compose.yml` in front of `markera-server:8080`
   (Caddyfile: `<host> { reverse_proxy markera-server:8080 }`; `caddy_data` volume for the
   Let's Encrypt state). Stop publishing 8090 on the host.
3. App: `markera.backend.url=https://<host>` in `gradle.properties`; drop the cleartext
   entry for 192.168.1.191 from `network_security_config.xml`.
4. Set `DEV_AUTH=false` and `ADMIN_UI=false` in `server/.env` before exposing anything —
   `/auth/dev` is a free login and `/admin` shows every user's series without one.

## Follow-ups / out of scope

- Wizard: corrections made in the wizard's own Confirm picker (`wizardVm.updateShot`) are not
  what `resetScanner(save = true)` passes to `recorder.commit` (it passes the scanner's
  `topScores`), so a series saved from the wizard carries the detector values, not the lane's
  edits. Low priority while the competition entry is hidden (task 20); fix = commit with the
  lane's shots at lane confirmation.

- ~~Image upload alongside a series~~ → task 8.
- ~~Series history screen in the app~~ → task 7.
- ~~HTTPS / exposing the backend outside the LAN~~ → task 9.

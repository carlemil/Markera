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
- Images are **not** uploaded (request is holes + timestamp + caliber).
- Sub-agents run on `opus`.

## User actions needed (cannot be done by the orchestrator)

- [ ] Google Cloud Console: an OAuth **Web** client ID (used as the token audience on the
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
| 6 | iOS: Apple sign-in actual (AuthenticationServices), ATS exception; compile on the Mac mini | todo |

## API (server)

```
GET  /health                       -> 200 {"status":"ok"}
POST /auth/google {idToken}        -> 200 {token, userId}
POST /auth/apple  {idToken}        -> 200 {token, userId}
POST /auth/dev    {subject}        -> 200 {token, userId}   (DEV_AUTH=true only)
POST /series      Bearer, {timestamp (ISO-8601), caliber, holes:[{x,y,ring,innerTen,distanceMm}]}
                                   -> 201 {id}
GET  /series      Bearer           -> 200 [{id, timestamp, caliber, holes:[...]}]
```

## Follow-ups / out of scope

- Image upload alongside a series.
- Series history screen in the app.
- HTTPS / exposing the backend outside the LAN.

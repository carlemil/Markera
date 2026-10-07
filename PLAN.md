# Plan: series backend + login + caliber

Tasks live on the Tickets board now. The finished task log (tasks 1–134, the old API
block, the HTTPS recipe and the follow-ups) is in git: `git show 9799667:PLAN.md`.
This file keeps only the decisions still in force that are not spelled out in
`CLAUDE.md`, and the user actions still open.

## Decisions (still in force)

- **Backend = standalone Gradle project in `server/`** (Ktor, SQLite via `sqlite-jdbc`,
  `java-jwt` + `jwks-rsa` for Google/Apple ID-token verification), not in the root build:
  the root build needs the Android SDK at configuration time, which would break a
  `docker build`. Its DTOs are duplicated in the app rather than shared.
- **Auth model**: the app exchanges a provider ID token once (`POST /auth/google`,
  `POST /auth/apple`) for an opaque session token; every later call sends
  `Authorization: Bearer <token>`. Google ID tokens live 1 h and Apple's 10 min, so
  re-sending them per request is not viable.
- **Production hardening**: `DEV_AUTH=false` on the Mac mini (`/auth/dev` is a free login;
  point the mock flavor at a local server with `DEV_AUTH=true` instead). The admin pages
  are HTTP Basic, user `admin`, password `ADMIN_PASSWORD`; unset = admin routes off. The
  server refuses to start on the `.env.example` placeholder and warns under 16 characters.
- **Calibers**: the label is both UI text and wire value, and it is stored in the
  production DB, so add and reorder built-ins freely but **never rename one**. The server
  checks only a label's shape (`CALIBER_SHAPE`), so a new or user-added caliber needs no
  server change. `-` (none) triggers the chooser on save.
- **Hole kinds** (all derived, nothing stored for the kind itself): every saved hole has
  what the user confirmed (`ring`, `innerTen` — totals and admin use these) and what the
  detector said (`detectedRing`, `detectedInnerTen`). **Detected** = `detectedRing != null`
  (edited when the pairs differ; a detected hole set to 0 is kept as a 0, a false-positive
  signal); **manual** = tapped on the photo, has `x`/`y`/`distanceMm` but `detected*` null;
  **typed** = entered in an empty picker slot, position and `detected*` all null. A moved
  marker changes `x`/`y` while `detectedX`/`detectedY` keep the detector's spot. Merging
  happens in the app (`SeriesRequest.withPicks`); the server only stores.
- **Server JSON is strict about unknown keys** (Ktor's default `json()`): a change that adds
  a field ships the server half first and deployed, then the app half.
- **Images**: `POST /series/{id}/image` is a raw `image/jpeg` body (no multipart), uploaded
  after the series POST succeeds; its failure never fails the series.
- **Release signing**: upload keystore at the repo root (`keystore` + `keystore.properties`,
  gitignored). Play App Signing re-signs, so the Play key's SHA-1 is what the Android OAuth
  client in Google Cloud needs (read from the Play-installed 1.2.0 (3), 2026-09-08:
  `92:96:43:90:D0:7B:94:74:B7:BA:83:CE:7E:10:79:C4:E0:A0:A3:49`); without it Google sign-in
  fails on Play builds.
- **UI rule** (user, 2026-09-07): measurements and positions (mm, x, y) show no decimals,
  in the app and on the admin pages.

## User actions still open

- [ ] Data safety declaration is wrong (says "App doesn't collect or share data", "Data isn't
      encrypted"): the app collects account info (sign-in name + subject), photos (target
      frames) and user content (series), all encrypted in transit (HTTPS), with in-app
      deletion. Fix in Play Console → App content → Data safety → Manage; the account-deletion
      URL `https://markera.duckdns.org/delete-account` goes into the data-deletion section.

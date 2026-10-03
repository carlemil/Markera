# Markera server

Ktor + SQLite backend for scanned series. Standalone Gradle build (not part of the root build).

- Run locally: `DEV_AUTH=true ./gradlew run` (or `./gradlew installDist && build/install/server/bin/server`)
- Tests: `./gradlew test`
- Docker: `cp .env.example .env` then `docker compose up -d --build` (bound to 127.0.0.1:8090 for the host's Caddy, which serves it as `https://markera.duckdns.org`; SQLite on the `markera-data` volume)

Env vars: `PORT` (8080), `DB_PATH` (`./data/markera.db`), `IMAGES_DIR` (`<DB_PATH dir>/images`),
`GOOGLE_CLIENT_ID`, `APPLE_BUNDLE_ID`, `DEV_AUTH` (`true` enables `POST /auth/dev`, never in production),
`ADMIN_PASSWORD` (serves the read-only `/admin` pages — users → series → holes + image — behind HTTP Basic
with user `admin` and this password; blank/unset leaves them unregistered),
`TZ` (the zone the admin pages show timestamps in; compose sets `Europe/Stockholm`).

Suggestion box: `POST /suggestions` `{title, description, email?, platform?, appVersion?}` → 201 `{id}`. Open to
signed-out users (a valid Bearer token only links the suggestion to its user; a stale one is ignored). Title
≤ 120 and description ≤ 5000 characters, both required; `email` optional but must look like an address. At most
50 a day from everyone together (then 429). Each is stored (`/admin/suggestions`, deleted with the sender's
account) and, with `SMTP_HOST`, `SMTP_USER` and `SMTP_PASSWORD` set, mailed to `SUGGESTIONS_TO` (default
`CONTACT_EMAIL`, then `SMTP_USER`) from `SMTP_FROM` (default `SMTP_USER`) with Reply-To = the sender's
address. `SMTP_PORT` defaults to 587 (STARTTLS); 465 is implicit TLS. A failed mail is logged, the suggestion
still answers 201 and shows as "not mailed" on the admin page. Gmail needs an app password.

Sign in with Apple from a browser (how **Android** reaches it — Apple ships no Android SDK, and the flow's
client secret must never live in an APK) needs five more, all of them or none:
`APPLE_SERVICES_ID`, `APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY` (the `.p8` on one line, PEM header
optional) and `PUBLIC_URL`. Missing any leaves `/auth/apple/{start,callback,claim}` answering **503**;
`POST /auth/apple` (the native iOS token) keeps working off `APPLE_BUNDLE_ID` alone. The Services ID's
*Primary App ID* must be the iOS bundle id, or Apple hands Android a different `sub` and the user lands on a
second, empty account; its Return URL must be exactly `$PUBLIC_URL/auth/apple/callback`. Apple verifies the
domain by fetching `/.well-known/apple-developer-domain-association.txt`, served from
`src/main/resources/apple-developer-domain-association.txt` (404 while that file is absent).

API: `GET /health` (runs `SELECT 1`; 503 when the database does not answer), `POST /auth/{google,apple,dev}` → `{token, userId}`,
`GET /auth/apple/start?state=` → 302 to Apple, `GET /auth/apple/callback` → 302 to
`markera://auth/apple?state=&nonce=`, `POST /auth/apple/claim` `{state, secret, nonce}` → `{token, userId}`
(`state` is the hex sha256 of `secret`, which never leaves the device — any app can catch the deep link, only
the one that started the flow can redeem it; the one-time `nonce` goes only to the browser that signed in, so
whoever chose the `state` cannot redeem someone else's login either; single use, 60 s),
`POST /series` / `GET /series` / `PUT /series/{id}` (same body as POST, replaces timestamp, caliber and
every hole → 204) / `DELETE /series/{id}` (→ 204, 404 if not yours) with
`Authorization: Bearer <token>`, `DELETE /account` (→ 204; wipes the caller's series, images and sessions,
so the token stops working), `POST /series/{id}/image` (raw `image/jpeg` body ≤ 10 MB → 204) /
`GET /series/{id}/image`. `POST /series` answers 429 once a user has created 300 series in 24 h (a
retry of a stored `clientId` still gets its id); the app keeps a 429 in its outbox for later.

`GET /series` returns a plain array, newest first, and pages with `?limit=` (default 50, clamped to 1..200)
and `?before=<series id>` (only ids below it). Page by passing the last id of the previous page.

The image upload takes optional `?width=&height=` — the size, in source pixels, of the frame the hole
coordinates were measured in (the JPEG is that frame downscaled, same aspect). They come back on the series
as `imageWidth`/`imageHeight`, and the admin page uses them to draw the holes on the photo; without them the
photo shows unmarked.

A hole is `{x, y, ring, innerTen, distanceMm, detectedRing, detectedInnerTen, detectedX, detectedY}`.
`ring`/`innerTen`/`x`/`y` is what the user confirmed, `detected*` what the detector said (all optional,
omitted or null when there was no detection — kept for training data). `x`/`y`/`distanceMm` may be null.
The three kinds are derived, not stored: **detected** = `detectedRing != null` (**edited** when the
confirmed pair differs from the detected one, **moved** when `x`/`y` differ from `detectedX`/`detectedY`),
**manual** = `x != null` without a detection, **typed** = `x == null`.

`ADMIN_PASSWORD` also unlocks `PUT /admin/series/{id}` (same body and replace as `PUT /series/{id}`, for
any user's series). The admin series page is the editor for it: pick a score per hole, delete holes, drag
the markers on the photo (which clears that hole's `distanceMm`), click the photo to add one; every edit saves at once (no Save button). Holes the app soft-deleted show greyed and are never re-saved.
Adding by clicking needs a photo with a stored frame size; otherwise "Add hole" adds a position-less one.
The score is split in two columns: a read-only `detected` one (struck through once overridden) and an
editable `manual` one, whose empty option reverts the hole to the detected score.
Every other `/admin` page is read-only, and all of them round millimetres and pixel positions to whole
numbers.

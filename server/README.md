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

API: `GET /health`, `POST /auth/{google,apple,dev}` → `{token, userId}`,
`POST /series` / `GET /series` / `PUT /series/{id}` (same body as POST, replaces timestamp, caliber and
every hole → 204) / `DELETE /series/{id}` (→ 204, 404 if not yours) with
`Authorization: Bearer <token>`, `DELETE /account` (→ 204; wipes the caller's series, images and sessions,
so the token stops working), `POST /series/{id}/image` (raw `image/jpeg` body ≤ 5 MB → 204) /
`GET /series/{id}/image`.

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
the markers on the photo (which clears that hole's `distanceMm`), click the photo to add one, then Save.
Adding by clicking needs a photo with a stored frame size; otherwise "Add hole" adds a position-less one.
Every other `/admin` page is read-only, and all of them round millimetres and pixel positions to whole
numbers.

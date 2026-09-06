# Markera server

Ktor + SQLite backend for scanned series. Standalone Gradle build (not part of the root build).

- Run locally: `DEV_AUTH=true ./gradlew run` (or `./gradlew installDist && build/install/server/bin/server`)
- Tests: `./gradlew test`
- Docker: `cp .env.example .env` then `docker compose up -d --build` (host port 8090; SQLite on the `markera-data` volume)

Env vars: `PORT` (8080), `DB_PATH` (`./data/markera.db`), `IMAGES_DIR` (`<DB_PATH dir>/images`),
`GOOGLE_CLIENT_ID`, `APPLE_BUNDLE_ID`, `DEV_AUTH` (`true` enables `POST /auth/dev`, never in production),
`ADMIN_UI` (`true` serves the read-only, login-less `/admin` pages — users → series → holes + image; LAN only).

API: `GET /health`, `POST /auth/{google,apple,dev}` → `{token, userId}`,
`POST /series` / `GET /series` with `Authorization: Bearer <token>`,
`POST /series/{id}/image` (raw `image/jpeg` body ≤ 5 MB → 204) / `GET /series/{id}/image`.

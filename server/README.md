# Markera server

Ktor + SQLite backend for scanned series. Standalone Gradle build (not part of the root build).

- Run locally: `DEV_AUTH=true ./gradlew run` (or `./gradlew installDist && build/install/server/bin/server`)
- Tests: `./gradlew test`
- Docker: `cp .env.example .env` then `docker compose up -d --build` (host port 8090; SQLite on the `markera-data` volume)

Env vars: `PORT` (8080), `DB_PATH` (`./data/markera.db`), `GOOGLE_CLIENT_ID`, `APPLE_BUNDLE_ID`,
`DEV_AUTH` (`true` enables `POST /auth/dev`, never in production).

API: `GET /health`, `POST /auth/{google,apple,dev}` → `{token, userId}`,
`POST /series` / `GET /series` with `Authorization: Bearer <token>`.

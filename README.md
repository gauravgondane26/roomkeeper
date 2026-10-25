# RoomKeeper

A hotel booking backend in Java 25 and Spring Boot, built test-first. It focuses on the problems real booking systems get wrong: selling the last room twice, charging for a payment that arrives twice, and holds that never release.

> Work in progress. See the [roadmap](docs/roadmap.md) for where things stand.

## Docs

- [Project design](docs/Hotel%20Booking%20Backend%20Project%20Design.md): goals, schema, API, booking lifecycle, and the hard problems
- [Roadmap](docs/roadmap.md): the build plan broken into tasks

## Running locally

Requires JDK 25 and Docker.

```bash
# Whole stack from source: app (prod profile), Postgres 18, Redis 8
docker compose up -d --build --wait
scripts/smoke.sh                      # smoke checks over HTTP

# Or run only the services and start the app yourself (local profile)
docker compose up -d postgres redis
./mvnw spring-boot:run
```

The app listens on port 8080. Postgres and Redis are published on 15432 and 16379 so they don't collide with native installs. Override with `APP_PORT`, `POSTGRES_PORT`, and `REDIS_PORT`.

## Tests

```bash
./mvnw test      # unit tests (*Test), no Docker needed
./mvnw verify    # unit + integration tests (*IT) against real Postgres and Redis via Testcontainers
```

`verify` writes a coverage report to `target/site/jacoco/index.html`. CI runs the same steps on every push, then builds the Docker image and smoke tests it.

## Configuration profiles

| Profile | Used by | Connections |
| --- | --- | --- |
| `local` (default) | Running on a developer machine | The Compose services |
| `test` | The test suite | Testcontainers |
| `prod` | The Docker image | Environment variables only: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` |

Everything runs in UTC regardless of the host's time zone.

# RoomKeeper

A hotel booking backend in Java 25 and Spring Boot, built test-first. It focuses on the problems real booking systems get wrong: selling the last room twice, charging for a payment that arrives twice, and holds that never release.

> Work in progress. See the [roadmap](docs/roadmap.md) for where things stand.

## Docs

- [Project design](docs/Hotel%20Booking%20Backend%20Project%20Design.md): goals, schema, API, booking lifecycle, and the hard problems
- [Roadmap](docs/roadmap.md): the build plan broken into tasks
- [Query plans](docs/query-plans.md): `EXPLAIN ANALYZE` of hotel search over a million inventory rows (about 1 ms) and the indexes it uses

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

## Using the API

Swagger UI is the client: open http://localhost:8080/swagger-ui.html.

1. `POST /api/v1/auth/signup` with an email, a password (at least 8 characters, at most 72 bytes: BCrypt's limit), and a name. Add `"role": "HOTEL_MANAGER"` to manage hotels.
2. `POST /api/v1/auth/login` returns an `accessToken` (valid 15 minutes) and a `refreshToken` (valid 7 days, single use).
3. Click **Authorize** and paste the access token. Routes with a lock icon now work.
4. When the access token expires, `POST /api/v1/auth/refresh` with the refresh token returns a new pair. `POST /api/v1/auth/logout` revokes it.

Per client IP, login allows 10 attempts a minute, signup 20 an hour, and refresh 30 a minute; past that the API answers 429 with a `Retry-After` header. Every error is an RFC 9457 problem detail (`application/problem+json`); match on its `type`, such as `urn:roomkeeper:problem:invalid-credentials`.

### Finding a hotel (no login needed)

- `GET /api/v1/hotels/search?city=Goa&checkIn=2026-12-24&checkOut=2026-12-27` lists hotels with a room type free for every night of the stay, cheapest first. Optional: `rooms` (default 1), `page` (from 0), and `size` (default 20, at most 50). Stays are up to 30 nights. Each hotel shows `fromPricePerNight`, the cheapest nightly price of any bookable room during the stay.
- `GET /api/v1/hotels/{id}/info` shows an active hotel's details and room types.

Prices come from a rule chain applied per night: base price, then the night's surge factor, 20% more once a room type is 80% booked or held, 15% more on Friday and Saturday nights, and 25% more on configured holidays (`roomkeeper.pricing` in `application.yaml`).

### Setting up a hotel (as a `HOTEL_MANAGER`)

1. `POST /api/v1/admin/hotels` with a name, city, time zone (such as `Asia/Kolkata`), and optional amenities. New hotels start inactive. Nights are dates in the hotel's time zone, so its "today" starts at its own midnight.
2. `POST /api/v1/admin/hotels/{hotelId}/rooms` for each room type: type name, base price per night, capacity, and how many rooms of that type exist.
3. `PATCH /api/v1/admin/hotels/{hotelId}/activate` puts the hotel on sale: each room type gets one inventory row per night for the next 365 nights. Room types added later get their own year straight away.
4. `GET /api/v1/admin/inventory/rooms/{roomId}?from=…&to=…` shows each night (booked, held, available, surge, closed). `PATCH` the same path with `from`, `to`, and any of `closed`, `surgeFactor`, `totalCount` to change a whole date range at once.

Changing a room type's `totalCount` later updates every future night of that room, except nights whose total was set by hand in inventory admin (they show `totalOverridden: true`). Managers only see their own hotels; anyone else's return 403. A total can't be lowered below the rooms already booked or held on a night (409 `total-below-sold`, listing the nights), and the database enforces the same rule with a check constraint.

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
| `prod` | The Docker image | Environment variables only: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `JWT_SECRET` (base64, 32+ bytes) |

Everything runs in UTC regardless of the host's time zone.

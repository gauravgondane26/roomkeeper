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

Anyone can sign up as a manager, but a hotel only goes on sale after an admin approves it. Nobody can sign up as an admin: the first admin account is created at startup from `ADMIN_EMAIL` and `ADMIN_PASSWORD` (locally and in Compose, `admin@roomkeeper.local` / `local-admin-password`).

1. `POST /api/v1/admin/hotels` with a name, city, time zone (such as `Asia/Kolkata`), and optional amenities. New hotels start inactive. Nights are dates in the hotel's time zone, so its "today" starts at its own midnight.
2. `POST /api/v1/admin/hotels/{hotelId}/rooms` for each room type: type name, base price per night, capacity, and how many rooms of that type exist.
3. An admin reviews the hotel: `GET /api/v1/moderation/hotels` lists hotels waiting for review, and `PATCH /api/v1/moderation/hotels/{hotelId}/approve` or `/reject` (with a `reason`) decides. The manager sees `approvalStatus` and any `rejectionReason` on the hotel; editing a rejected hotel sends it back for review.
4. Once approved, `PATCH /api/v1/admin/hotels/{hotelId}/activate` puts the hotel on sale: each room type gets one inventory row per night for the next 365 nights. Room types added later get their own year straight away.
5. `GET /api/v1/admin/inventory/rooms/{roomId}?from=…&to=…` shows each night (booked, held, available, surge, closed). `PATCH` the same path with `from`, `to`, and any of `closed`, `surgeFactor`, `totalCount` to change a whole date range at once.

Changing a room type's `totalCount` later updates every future night of that room, except nights whose total was set by hand in inventory admin (they show `totalOverridden: true`). Managers only see their own hotels; anyone else's return 403. A total can't be lowered below the rooms already booked or held on a night (409 `total-below-sold`, listing the nights), and the database enforces the same rule with a check constraint.

`PATCH /api/v1/admin/hotels/{hotelId}/deactivate` takes a hotel off sale: it leaves search and can't be booked, while bookings it already has carry on. Activating puts it back. An admin takes an approved hotel off sale by rejecting it, and it stays off until the manager edits it and it's approved again. A hotel or room type that has ever had a booking can't be deleted (409 `has-bookings`). An hourly job keeps every room type on sale 365 nights ahead as each hotel's midnight passes.

### Booking a stay (any signed-in user)

1. Save the people staying: `POST /api/v1/users/guests` with a name, age, and gender (`FEMALE`, `MALE`, or `OTHER`). `GET`, `PUT`, and `DELETE` manage them; a deleted guest stays on bookings it's already on.
2. `POST /api/v1/bookings/init` with a `roomId` (a room type), `checkIn`, `checkOut`, and `rooms` (1 to 10), plus an `Idempotency-Key` header. If every night has the rooms, they're held for 10 minutes at the price quoted now, which is frozen on the booking. Otherwise it's 409 `sold-out`. Two guests can't both get the last room: init locks the stay's nights, in date order like every other writer.
3. `POST /api/v1/bookings/{id}/guests` with `guestIds` puts saved guests on the booking while it's held, at most as many as the rooms sleep. After the hold runs out it's 409 `hold-expired`.
4. `GET /api/v1/bookings/{id}`, `GET /api/v1/bookings/{id}/status`, and `GET /api/v1/users/bookings` (newest first, paged) show where bookings stand. Payment comes in week 6.

Requests that create something take an `Idempotency-Key` header: any string of 1 to 255 visible ASCII characters (a UUID works), new for each request and the same when retrying it. A retry within 24 hours gets the first response back, with `Idempotent-Replayed: true`, instead of a second booking. Without the header it's 400 `idempotency-key-required`; the same key on a different request is 422 `idempotency-key-reused`; a retry while the first request is still running is 409 `idempotency-key-in-use`. A failed request doesn't keep its key, so it can be retried as is. If Redis is down these requests get 503 rather than risk running twice.

A job runs every minute and gives back the rooms of holds that ran out unpaid; a Redis lock (`lock:hold-expiry`) makes sure only one instance runs it. A hold past its time already reads `EXPIRED` before the job gets to it.

## Tests

```bash
./mvnw test      # unit tests (*Test), no Docker needed
./mvnw verify    # unit + integration tests (*IT) against real Postgres and Redis via Testcontainers
```

`verify` writes a coverage report to `target/site/jacoco/index.html` and fails if coverage drops below 94% of lines or 83% of branches. A run of only some tests can't reach that, so skip coverage for it:

```bash
./mvnw verify -DskipUnitTests -Djacoco.skip=true -Dit.test=CachingIT   # one integration test class
./mvnw test -Dtest=BookingStatusTest                                    # one unit test class (no coverage check)
```

CI runs on every push to `main` and every pull request. It first checks that no migration already on `main` was changed (`scripts/check-migrations.sh`), then runs the same steps as above, then builds the Docker image and smoke tests it. Dependabot opens weekly update pull requests for Maven, GitHub Actions, and the Docker images.

## Configuration profiles

| Profile | Used by | Connections |
| --- | --- | --- |
| `local` (default) | Running on a developer machine | The Compose services |
| `test` | The test suite | Testcontainers |
| `prod` | The Docker image | Environment variables only: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `JWT_SECRET` (base64, 32+ bytes); optionally `STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET` (test mode), without which payments answer 503 |

Everything runs in UTC regardless of the host's time zone.

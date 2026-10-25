# Roadmap

The build plan from the [project design](Hotel%20Booking%20Backend%20Project%20Design.md), broken into tasks small enough for one session each. Tick a box when its tests pass and the work is committed.

Tasks are listed in the order to do them. Where a task names a test, that test is written and committed first.

**Current:** Week 1

## Week 1: Skeleton

Goal: an empty app that starts against real Postgres and Redis, with CI checking every push.

- [x] Git repo, design doc, GitHub remote
- [x] Repo hygiene: `.gitignore`, `.gitattributes` (LF for `mvnw` and shell scripts), README stub, `CLAUDE.md`
- [x] Choose the Java version: 25 (LTS), mainly for the virtual-thread pinning fix
- [x] Generate the Spring Boot project: Java 25, Maven wrapper, base package, starter dependencies
- [x] `compose.yaml` with Postgres and Redis for local development
- [x] Spring profiles `local`, `test`, `prod`; secrets read from environment variables only
- [x] Flyway wired up with a baseline `V1__init.sql`
- [x] Testcontainers base: one shared Postgres and one shared Redis container for the whole suite
- [x] Test: the app context starts, Flyway migrates, Redis answers a ping
- [ ] `Clock` bean, plus a fixed-clock setup for tests
- [ ] REST Assured set up for black-box API tests against the real HTTP server
- [ ] Multi-stage `Dockerfile`; add the app service to Compose
- [ ] Smoke script that checks the running Compose stack (health endpoint now, more routes each week)
- [ ] GitHub Actions: build, unit tests, integration tests, JaCoCo report, Docker image build

**Done when:** CI is green on an empty app.

## Week 2: Auth

Goal: users can sign up, log in, refresh, and log out, and login can't be brute forced.

- [ ] Migration: `users` table (unique email, password hash, role)
- [ ] Error handling: RFC 9457 problem details with stable `type` values; web slice tests for the error shape
- [ ] Test data builders, starting with `aUser()`
- [ ] Signup: validation, BCrypt hashing, duplicate email returns 409
- [ ] Login: issue a JWT access token; test that a bad password gets 401
- [ ] Security config: stateless JWT filter; test that the wrong role on `/admin` gets 403
- [ ] Refresh tokens in Redis (`refresh:{jti}`, TTL = token lifetime); `POST /auth/refresh` rotates them
- [ ] Logout deletes the refresh token; test that a revoked token can't refresh
- [ ] Rate limiter: token bucket as a Redis Lua script (`rl:{route}:{key}`), fails open and logs when Redis is down
- [ ] Test: the 11th login attempt inside the window gets 429
- [ ] `GET` and `PATCH /users/profile`
- [ ] springdoc-openapi with bearer auth, so Swagger can log in

**Done when:** Swagger can log in and call a protected route.

## Week 3: Hotels, rooms, inventory admin

Goal: a hotel manager can set up a hotel end to end.

- [ ] Migration: `hotels`, `rooms`, `inventory` (unique on `(room_id, date)`, check constraint on counts, indexes)
- [ ] Data slice test: the check constraint rejects `booked_count + reserved_count > total_count`
- [ ] MapStruct setup; entities never leave the service layer
- [ ] Hotel CRUD on `/admin/hotels`; test that a non-owner gets 403
- [ ] Room CRUD on `/admin/hotels/{id}/rooms`
- [ ] `PATCH /admin/hotels/{id}/activate`; test that it creates a year of inventory rows per room
- [ ] Adding a room to an already active hotel creates that room's inventory
- [ ] Inventory admin `GET` and `PATCH /admin/inventory/rooms/{roomId}`: close dates, change surge, change totals over a date range
- [ ] Test: lowering a total below what's already booked or held is rejected with a clean error

**Done when:** a manager can create a hotel, add rooms, activate it, and adjust inventory.

## Week 4: Pricing, search, caching

Goal: guests can search a city for dates and see correct prices.

- [ ] Pricing rule interface and base price rule
- [ ] Surge, occupancy, weekend, and holiday rules, one unit test class each
- [ ] Rule chain that prices every night in a range and totals it
- [ ] Migration: `hotel_min_price`; keep it updated when inventory or prices change
- [ ] Availability query (`GROUP BY` with `HAVING COUNT(*) = nights`); data slice test that skips a hotel with one sold-out night
- [ ] Closed dates and inactive hotels excluded from search
- [ ] `GET /hotels/search` with pagination, and `GET /hotels/{id}/info`
- [ ] Index check: capture `EXPLAIN ANALYZE` output for the README
- [ ] Redis cache manager: hotel info (about 10 min), search (about 60 s)
- [ ] Test: changing inventory evicts the cached search result for that hotel
- [ ] Optional: set up LeanToken now that the codebase is growing

**Done when:** search returns the right hotels and prices, and cached results never show stale availability.

## Week 5: Booking init, holds, expiry

Goal: no oversell under load, and holds always release.

- [ ] Migration: `bookings` (with `version`), `guests`, `booking_guests`
- [ ] Booking state machine in domain code; unit tests for every allowed and rejected transition
- [ ] Inventory locking query: `SELECT ... FOR UPDATE` ordered by date; data slice test
- [ ] `POST /bookings/init`: lock, check availability, increment `reserved_count`, freeze the price, set `hold_expires_at` from the clock
- [ ] Sold out returns a problem detail with a stable `type`
- [ ] Test: 50 concurrent requests for the last room, exactly one succeeds
- [ ] Guest CRUD on `/users/guests`
- [ ] `POST /bookings/{id}/guests`, validated against room capacity
- [ ] `GET /bookings/{id}/status` and `GET /users/bookings`
- [ ] Hold expiry job: releases `reserved_count` and marks bookings Expired; test with a fixed clock, no `Thread.sleep`
- [ ] Redis job lock (`lock:hold-expiry`); test that only one instance runs the job

**Done when:** the 50-thread test passes reliably and expired holds return their rooms.

## Week 6: Payments

Goal: a test-card payment confirms a booking exactly once.

- [ ] Migration: `payments`, `processed_webhook_events`
- [ ] Idempotency keys: Redis `SET NX` (`idem:{userId}:{key}`, 24 h TTL) storing status and response; fail closed with 503 when Redis is down
- [ ] Apply `Idempotency-Key` to `POST /bookings/init`; test that a retry returns the original booking
- [ ] Stripe client behind an interface, with a fake for tests
- [ ] `POST /bookings/{id}/payments`: checkout session for the frozen amount, booking moves to Payment pending
- [ ] Test: a retried payment request returns the same session
- [ ] Webhook: verify the signature (bad signature gets 400); in one transaction, dedupe the event, move counts from reserved to booked, confirm the booking
- [ ] Test: the same webhook delivered twice confirms the booking once
- [ ] Decide how a webhook after hold expiry is handled, update the state diagram, then implement it
- [ ] Test: a webhook after hold expiry ends confirmed or refunded, never stuck
- [ ] Handle failed and expired checkout sessions
- [ ] Manual end-to-end check with the Stripe CLI and a test card

**Done when:** a test-card payment confirms a booking, and duplicates and late webhooks are handled.

## Week 7: Cancellations, refunds, outbox, reports

Goal: the full lifecycle works, including the unhappy paths.

- [ ] Migration: `outbox_events`
- [ ] Outbox writer; webhook confirmation writes its event in the same transaction
- [ ] Test: a duplicate webhook still produces exactly one outbox row
- [ ] Outbox publisher job with a Redis lock (`lock:outbox`); fake email consumer that tolerates duplicates
- [ ] Cancellation policy as plain domain code, with unit tests
- [ ] `POST /bookings/{id}/cancel`: restore inventory, refund, write an outbox event
- [ ] Test: cancelling restores inventory and calls the refund exactly once
- [ ] `GET /admin/hotels/{id}/bookings`
- [ ] `GET /admin/hotels/{id}/reports`: revenue and occupancy over a date range

**Done when:** every path in the state diagram is covered by a test.

## Week 8: Hardening

Goal: the system is observable, load tested, and explained.

- [ ] Business metrics: bookings created, holds expired, duplicate webhooks ignored, rate-limit rejections, cache hit ratio
- [ ] Prometheus and Grafana in Compose, with a provisioned dashboard
- [ ] Actuator endpoints locked down in the `prod` profile
- [ ] Load test with k6 or Gatling against the booking endpoint
- [ ] JaCoCo: review coverage on the domain package
- [ ] README: one section per hard problem (what goes wrong, what was chosen, what was rejected, which test proves it), plus query plans and a Grafana screenshot
- [ ] Optional: deploy to a free tier for a live Swagger link

**Done when:** the README explains every design decision with links to its tests.

## Stretch goals

- [ ] Benchmark pessimistic against optimistic locking under contention and publish the numbers
- [ ] Mutation testing with PIT on the domain package
- [ ] Publish outbox events to Redis Streams or Kafka instead of polling
- [ ] Waitlist that offers a released hold to the next person in line
- [ ] Virtual threads with a before-and-after load test

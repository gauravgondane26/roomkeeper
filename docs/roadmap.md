# Roadmap

The build plan from the [project design](Hotel%20Booking%20Backend%20Project%20Design.md), broken into tasks small enough for one session each. Tick a box when its tests pass and the work is committed.

Tasks are listed in the order to do them. Where a task names a test, that test is written and committed first.

**Current:** Week 5

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
- [x] `Clock` bean, plus a fixed-clock setup for tests
- [x] REST Assured set up for black-box API tests against the real HTTP server
- [x] Multi-stage `Dockerfile`; add the app service to Compose
- [x] Smoke script that checks the running Compose stack (health endpoint now, more routes each week)
- [x] GitHub Actions: build, unit tests, integration tests, JaCoCo report, Docker image build

**Done when:** CI is green on an empty app.

## Week 2: Auth

Goal: users can sign up, log in, refresh, and log out, and login can't be brute forced.

- [x] Migration: `users` table (unique email, password hash, role)
- [x] Error handling: RFC 9457 problem details with stable `type` values; web slice tests for the error shape
- [x] Test data builders, starting with `aUser()`
- [x] Signup: validation, BCrypt hashing, duplicate email returns 409
- [x] Login: issue a JWT access token; test that a bad password gets 401
- [x] Security config: stateless JWT filter; test that the wrong role on `/admin` gets 403
- [x] Refresh tokens in Redis (`refresh:{sha256(token)}`, 7-day TTL); `POST /auth/refresh` rotates them
- [x] Logout deletes the refresh token; test that a revoked token can't refresh
- [x] Rate limiter: token bucket as a Redis Lua script (`rl:{route}:{key}`), fails open and logs when Redis is down
- [x] Test: the 11th login attempt inside the window gets 429
- [x] `GET` and `PATCH /users/profile`
- [x] springdoc-openapi with bearer auth, so Swagger can log in

**Done when:** Swagger can log in and call a protected route.

## Week 3: Hotels, rooms, inventory admin

Goal: a hotel manager can set up a hotel end to end.

- [x] Migration: `hotels`, `rooms`, `inventory` (unique on `(room_id, date)`, check constraint on counts, indexes)
- [x] Data slice test: the check constraint rejects `booked_count + reserved_count > total_count`
- [x] MapStruct setup; entities never leave the service layer
- [x] Hotel CRUD on `/admin/hotels`; test that a non-owner gets 403
- [x] Room CRUD on `/admin/hotels/{id}/rooms`
- [x] `PATCH /admin/hotels/{id}/activate`; test that it creates a year of inventory rows per room
- [x] Adding a room to an already active hotel creates that room's inventory
- [x] Inventory admin `GET` and `PATCH /admin/inventory/rooms/{roomId}`: close dates, change surge, change totals over a date range
- [x] Test: lowering a total below what's already booked or held is rejected with a clean error
- [x] Hotel time zone: "today" (activation start, past-night checks) is the hotel's local date, not UTC's
- [x] Changing a room type's total updates its future nights, except nights whose total was set by hand (`total_overridden`); rejected if below what's booked or held

**Done when:** a manager can create a hotel, add rooms, activate it, and adjust inventory.

## Week 4: Pricing, search, caching

Goal: guests can search a city for dates and see correct prices.

- [x] Pricing rule interface and base price rule
- [x] Surge, occupancy, weekend, and holiday rules, one unit test class each
- [x] Rule chain that prices every night in a range and totals it
- [x] Migration: `hotel_min_price`; keep it updated when inventory or prices change
- [x] Availability query (`GROUP BY` with `HAVING COUNT(*) = nights`); data slice test that skips a hotel with one sold-out night
- [x] Closed dates and inactive hotels excluded from search
- [x] `GET /hotels/search` with pagination, and `GET /hotels/{id}/info`
- [x] Index check: capture `EXPLAIN ANALYZE` output for the README
- [x] Redis cache manager: hotel info (about 10 min), search (about 60 s)
- [x] Test: changing inventory evicts the cached search result for that hotel
- [x] Optional: set up LeanToken now that the codebase is growing

**Done when:** search returns the right hotels and prices, and cached results never show stale availability.

## Review fixes (before week 5)

From a security, efficiency, and regression review of weeks 1 to 4. The first two are bugs reproduced against the running app.

- [x] Signup rejects a password over 72 bytes (BCrypt's limit) with a 400; a 72-character password of 2-byte characters used to return 500
- [x] `hotel_min_price` updates are safe under concurrency (upsert instead of delete and insert); test with parallel changes to one hotel, which returned 500s
- [x] Search cache eviction without scanning Redis: a per-city version in the cache key, bumped on change
- [x] Manager accounts: anyone can still sign up as a manager, but an admin (new `ADMIN` role) approves each hotel before it can be activated
- [x] Email enumeration: rate-limit signup and refresh per IP like login (the signup response itself changes in week 7)

## Week 5: Booking init, holds, expiry

Goal: no oversell under load, and holds always release.

- [x] Migration: `bookings` (with `version`), `guests`, `booking_guests`
- [x] Booking state machine in domain code; unit tests for every allowed and rejected transition
- [x] Inventory locking query: `SELECT ... FOR UPDATE` ordered by date; data slice test
- [x] `POST /bookings/init`: lock, check availability, increment `reserved_count`, freeze the price, set `hold_expires_at` from the clock
- [x] Sold out returns a problem detail with a stable `type`
- [x] Test: 50 concurrent requests for the last room, exactly one succeeds
- [x] Guest CRUD on `/users/guests`
- [x] `POST /bookings/{id}/guests`, validated against room capacity
- [x] `GET /bookings/{id}/status` and `GET /users/bookings`
- [x] Hold expiry job: releases `reserved_count` and marks bookings Expired; test with a fixed clock, no `Thread.sleep`
- [x] Redis job lock (`lock:hold-expiry`); test that only one instance runs the job
- [ ] Daily inventory roll job (`lock:inventory-roll`): keeps every active room type open a year ahead and fills any missed nights; test with a fixed clock
- [ ] Deleting a hotel or room type with bookings is rejected with 409; bookings reference rooms with a restricting foreign key as the backstop
- [ ] `PATCH /admin/hotels/{id}/deactivate`: out of search and no new bookings, existing bookings honoured, history kept; admins can also take an approved hotel off sale
- [ ] Every path that changes inventory (init, expiry, the roll job) publishes `InventoryChangedEvent`; a test per path
- [x] Decide search-cache eviction for bookings: evict only when a night sells out or reopens; the 60 s TTL covers the rest (partial availability for multi-room searches, occupancy repricing). The eviction runs after the cheapest-price recompute, so a search can't re-cache the old price
- [x] Bookings recompute cheapest prices after commit, so inventory row locks are held as briefly as possible: an after-commit listener on a small bounded async executor (not REQUIRES_NEW on the request thread, which starved the pool); moves to the outbox in week 7
- [ ] Test: bookings racing room type edits and inventory admin on the same nights

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
- [ ] Decide whether deactivating a hotel cancels and refunds its future bookings or honours them
- [ ] Drop the unused `inventory (date, room_id)` index if the reports don't need it (see [query plans](query-plans.md))
- [ ] Signup always gives the same response, so it can't reveal registered emails; an already registered email's owner gets an email through the outbox instead

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
- [ ] `prod` refuses the development JWT secret; Compose reads secrets from a gitignored `.env`; Postgres and Redis ports bound to localhost
- [ ] Swagger UI and API docs off in `prod` unless explicitly enabled
- [ ] Rate-limit public search; Redis `maxmemory` with `volatile-ttl` eviction so caches go before tokens
- [ ] Trusted-proxy setup so rate limits see the real client IP; test with forwarded headers from trusted and untrusted sources
- [ ] Refresh token reuse detection (token families)
- [ ] Dependency and code scanning in CI (Dependabot, CodeQL)
- [ ] Reprice `hotel_min_price` when the pricing settings change
- [ ] Search in one query (`count(*) over ()`) instead of a count and a page query
- [ ] Inventory admin can reset a night's total to follow the room type again
- [ ] Cache key prefix carries a version, so a deploy that changes a cached response starts fresh
- [ ] Reset the shared test clock before every test automatically
- [ ] Test: authorization matrix over every admin endpoint (anonymous, guest, other manager, owner)
- [ ] Test: JWT edge cases (expired, wrong issuer, wrong key, `alg: none`, tampered role claim)
- [ ] Test: the whole app with Redis down, and with Postgres connections lost or the pool exhausted
- [ ] Test: DST change days, UTC+14 and UTC-11 hotels, a leap day in the inventory window, holidays across the year boundary
- [ ] Test: Flyway upgrading a populated older database
- [ ] OpenAPI snapshot test, so a breaking API change fails CI
- [ ] Paginate the moderation queue, and record who approved or rejected each hotel in an admin view

**Done when:** the README explains every design decision with links to its tests.

## Stretch goals

- [ ] Benchmark pessimistic against optimistic locking under contention and publish the numbers
- [ ] Mutation testing with PIT on the domain package
- [ ] Publish outbox events to Redis Streams or Kafka instead of polling
- [ ] Waitlist that offers a released hold to the next person in line
- [ ] Virtual threads with a before-and-after load test

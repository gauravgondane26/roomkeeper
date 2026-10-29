# Roadmap

The build plan from the [project design](Hotel%20Booking%20Backend%20Project%20Design.md), broken into tasks small enough for one session each. Tick a box when its tests pass and the work is committed.

Tasks are listed in the order to do them. Where a task names a test, that test is written and committed first.

**Current:** Week 8

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
- [x] Inventory roll job (`lock:inventory-roll`): keeps every active room type open a year ahead and fills any missed nights; test with a fixed clock. Runs hourly rather than daily, so each hotel's newest night opens within an hour of its own midnight
- [x] Deleting a hotel or room type with bookings is rejected with 409; bookings reference rooms with a restricting foreign key as the backstop
- [x] `PATCH /admin/hotels/{id}/deactivate`: out of search and no new bookings, existing bookings honoured, history kept; admins can also take an approved hotel off sale
- [x] Every path that changes inventory (init, expiry, the roll job) publishes `InventoryChangedEvent`; a test per path
- [x] Decide search-cache eviction for bookings: evict only when a night sells out or reopens; the 60 s TTL covers the rest (partial availability for multi-room searches, occupancy repricing). The eviction runs after the cheapest-price recompute, so a search can't re-cache the old price
- [x] Bookings recompute cheapest prices after commit, so inventory row locks are held as briefly as possible: an after-commit listener on a small bounded async executor (not REQUIRES_NEW on the request thread, which starved the pool). Decided in week 7 to keep it there rather than move it to the outbox: prices update milliseconds after a booking, and a dropped recompute only leaves a stale "from" price until the next change, since availability is always read from inventory
- [x] Test: bookings racing room type edits and inventory admin on the same nights

**Done when:** the 50-thread test passes reliably and expired holds return their rooms.

## CI hardening (before week 6)

- [x] CI runs on pushes to `main` and on pull requests; runs on `main` are never cancelled by a newer push, so every commit there gets a result
- [x] CI fails when a change modifies, renames, or deletes a migration already on `main` (`scripts/check-migrations.sh`); adding one is fine
- [x] Dependabot: weekly Maven, GitHub Actions, Dockerfile, and Compose updates, minor and patch grouped; no automatic major bumps for Java, Postgres, or Redis
- [x] Actions pinned to commit SHAs, with the version in a comment for Dependabot
- [x] Coverage floor: `verify` fails below 94% of lines or 83% of branches
- [x] The Docker build skips the unit tests again: the POM's `skipTests` setting had been overriding `-DskipTests`
- [x] Flaky caching tests: Spring Data Redis stores cache entries without waiting for Redis (its default with Lettuce), so an entry can land just after the response. Tests that rely on a cached entry wait for it (`awaitCached`); reproduced and checked by holding Redis writes back with `CLIENT PAUSE`

## Week 6: Payments

Goal: a test-card payment confirms a booking exactly once.

- [x] Migration: `payments`, `processed_webhook_events`; a partial unique index allows one open or paid payment per booking, and check constraints require the payment intent once paid and the refund id once refunded
- [x] Idempotency keys: Redis `SET NX` (`idem:{userId}:{key}`, 24 h TTL) storing status and response; fail closed with 503 when Redis is down. A running request holds its key on a 2-minute lease; only 2xx responses are kept (a failure releases the key); the same key on a different request gets 422, and a retry while the first runs gets 409 (as in the IETF Idempotency-Key draft)
- [x] Apply `Idempotency-Key` to `POST /bookings/init`; test that a retry returns the original booking. The header is required, and racing retries with one key make one booking
- [x] Stripe client behind an interface, with a fake for tests. `stripe-java` 34 with idempotency keys on sessions and refunds, 5 s/20 s timeouts; without `STRIPE_SECRET_KEY` payments answer 503 (`payments-unavailable`) and everything else works; integration tests always get the in-memory fake
- [x] `POST /bookings/{id}/payments`: checkout session for the frozen amount, booking moves to Payment pending. Stripe is called between two short transactions, never with the booking locked; a hold that runs out meanwhile gets its session closed; the session stays open until 31 minutes past the hold (Stripe's minimum is 30); Stripe's return pages are static and public
- [x] Test: a retried payment request returns the same session (same key replays; a new key while it's open gets it back; racing requests open one)
- [x] Webhook: verify the signature (bad signature gets 400); in one transaction, dedupe the event, move counts from reserved to booked, confirm the booking. Signatures checked with the SDK against our clock (5-minute tolerance stops replays); events read from the raw JSON; another event about a payment already applied changes nothing; unknown sessions and other event types are acknowledged and ignored
- [x] Test: the same webhook delivered twice confirms the booking once, including two deliveries in flight together (held on the booking's lock), which only the dedupe table stops; checked by disabling it. The design doc's "one outbox row" half comes with the outbox in week 7
- [x] Decide how a webhook after hold expiry is handled, update the state diagram, then implement it. Decided: re-check the stay as init would (hotel on sale, stay not begun, every night open with the rooms free); if so, book the rooms at the price paid and confirm (a new Expired → Confirmed edge), otherwise refund in full and leave it Expired. Any payment the booking can't take is refunded. Stripe is called for the refund outside the transaction, and the event is recorded only once the refund is made, with one idempotency key per payment. The expiry job also closes a released hold's open checkout session, after commit and off its own thread
- [x] Test: a webhook after hold expiry ends confirmed or refunded, never stuck, including redelivered and racing deliveries (one refund), a refund Stripe can't make yet (503, then made on Stripe's retry), the hotel taken off sale, the stay already begun, and search updating when a late payment sells out the room
- [x] Handle failed and expired checkout sessions: `checkout.session.expired` and `async_payment_failed` end the payment (Expired, Failed), and while the rooms are still held the guest can pay again in a new session (a Payment pending → Payment pending edge); `async_payment_succeeded` confirms like a paid checkout. An end reported after the payment went through changes nothing, and a payment reported on a session already ended is refunded
- [x] Manual end-to-end check with the Stripe CLI and a test card: a ₹9,000 booking paid with 4242 4242 4242 4242 was confirmed (webhook 200, payment SUCCEEDED with its payment intent, both nights booked), and Stripe resending the event changed nothing. The Stripe CLI runs as a Compose service under the `stripe` profile, signed in with the test key from `.env`; `smoke.sh` reads `.env` too

**Done when:** a test-card payment confirms a booking, and duplicates and late webhooks are handled.

## Week 7: Cancellations, refunds, outbox, reports

Goal: the full lifecycle works, including the unhappy paths.

- [x] Migration: `outbox_events`: the design doc's columns plus `attempts`, `last_error` and `failed_at`, so an event that keeps failing is parked after 10 attempts rather than retried forever (decided); a partial index on waiting events. The outbox carries notifications only; booking repricing stays after-commit (decided)
- [x] Outbox writer; webhook confirmation writes its event in the same transaction. `OutboxWriter` only writes inside the caller's transaction (MANDATORY), so an event can't outlive a change that rolled back; each event type has its own payload record (`BookingConfirmed`: booking, user, hotel, room type, dates, rooms, amount, currency), carrying the details as they were
- [x] Test: a duplicate webhook still produces exactly one outbox row, including two deliveries in flight together; a late payment that's refunded writes none
- [x] Outbox publisher job with a Redis lock (`lock:outbox`); fake email consumer that tolerates duplicates. Every 5 s; each event in its own transaction, locked with `for update skip locked`; a failed attempt is recorded in a second transaction, and the 10th parks the event; each event is tried at most once a run. The confirmation email's idempotency key is the outbox event's id, and the fake sender keeps one email per key, as a provider would
- [x] Cancellation policy as plain domain code, with unit tests. Decided: a full refund with at least 48 hours' notice, 50% with less, and no cancelling once the stay has begun (409 `cancellation-closed`); a stay begins at 2 PM hotel time on its check-in date. Notice is counted in elapsed hours (a clock change doesn't move it); the half is rounded to the paisa in the guest's favour
- [x] `POST /bookings/{id}/cancel`: restore inventory, refund, write an outbox event. Decided: the refund goes through the outbox. One transaction puts the rooms back on sale, cancels the booking, makes the policy's refund due on the payment (`REFUND_PENDING`, new `refunded_amount` column, V12), and writes `REFUND_DUE` and `BOOKING_CANCELLED`; a payment consumer makes the Stripe refund (partial refunds now supported) with the key `cancel-booking-{id}`. A repeat cancel returns the same cancellation; only a paid booking can be cancelled
- [x] Test: cancelling restores inventory and calls the refund exactly once, with a repeat cancel and a redelivered refund event; also half back inside 48 hours, 409 once the stay has begun, a refund Stripe can't make yet made on a later run, the cancellation email, and a sold-out hotel back in search. A fresh refund key per attempt makes the signature test fail (2 refunds)
- [x] `GET /admin/hotels/{id}/bookings`: the hotel's bookings, newest first, paged like the guest's own list and in the same shape (guests included); only its manager (403 otherwise, 404 for no such hotel)
- [x] `GET /admin/hotels/{id}/reports`: revenue and occupancy over a date range. Decided: revenue by night stayed, each paid booking's amount less refunds spread over its nights (so a cancellation counts what was kept). Up to 366 nights, one SQL query; rooms booked and on sale come from inventory (a closed night counts only rooms in use), so holds and cancellations don't count as sold; totals, occupancy (4 places) and average daily rate (none when nothing sold); nights shown to the paisa, the total summed exactly
- [x] Decide whether deactivating a hotel cancels and refunds its future bookings or honours them. Decided: honours them, as it already did, since off sale may be a pause (renovation, a season); guests can still cancel under the normal policy. A test pins it. Closing a hotel for good would want an explicit "cancel all its bookings" action, not this one
- [x] Drop the unused `inventory (date, room_id)` index if the reports don't need it (see [query plans](query-plans.md)): they don't. `scripts/explain-report.sql` (1.1 million inventory rows, 150,000 paid bookings) shows the report and the repricing read reach inventory through `(room_id, date)`, as every inventory query does, so V13 drops it. The same plan showed the report re-reading bookings once per night; joining once halved its time (25 ms to 13 ms)
- [x] Signup always gives the same response, so it can't reveal registered emails; an already registered email's owner gets an email through the outbox instead. Decided: always 202 with one message (the 201 user body and the 409 `email-already-registered` type are gone), the password hashed either way so timing doesn't tell; a new account gets a welcome email and a registered address's owner a "someone tried to sign up" email, both through the outbox. Racing signups with one email make one account, and the losers' owner emails come from a fresh transaction after the unique index stops them

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
- [ ] Code scanning in CI with CodeQL (Dependabot came early, under CI hardening)
- [ ] Branch protection on `main`: the CI check must pass before a merge
- [ ] Scan the Docker image (Trivy or Grype); on green `main`, publish it to GHCR tagged with the commit SHA, with an SBOM and provenance
- [ ] Nightly workflow that repeats the concurrency tests 10 to 20 times to catch rare races and flaky tests
- [ ] Reprice `hotel_min_price` when the pricing settings change
- [ ] Search in one query (`count(*) over ()`) instead of a count and a page query
- [ ] Inventory admin can reset a night's total to follow the room type again
- [ ] Cache key prefix carries a version, so a deploy that changes a cached response starts fresh
- [ ] Hotel info can't be re-cached stale after an eviction: Spring Data Redis stores entries without waiting, so a guest's read can land just after a manager's change evicts the entry and serve old details for up to 10 minutes. Version the key like search, or make cache writes wait for Redis; test it by holding writes back with `CLIENT PAUSE`
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

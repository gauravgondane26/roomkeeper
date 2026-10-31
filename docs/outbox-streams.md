# Outbox over Redis Streams

Design for the stretch goal "publish outbox events to Redis Streams instead of polling". Agreed and built on 2026-10-31; where building it changed the plan, this document says what was built and why.

## Goal

Show reliable delivery through a broker: consumer groups, acknowledgements, redelivery of entries a crashed consumer held, and Postgres resending whatever Redis lost. Consumers stay inside the app; each instance joins every group.

The rule that doesn't move: if Redis is flushed, a side effect can be late, never lost. Refunds go through the outbox, so a lost stream entry must never mean a lost refund. Postgres keeps the final say on what has been delivered.

## Decisions

- **Postgres records delivery; the stream only carries it.** An event is delivered when its consumer has committed `published_at`, not when it reaches Redis. Rejected: treating the row as published once `XADD` succeeds (a `FLUSHALL` or lost AOF would lose refunds silently), and using Redis only as a doorbell while consumers lock rows as today (`LISTEN/NOTIFY` would do that; the stream would add nothing).
- **One stream per consumer**, so a slow Stripe refund can't hold up emails and each consumer's delivery is tracked on its own. Every event type has exactly one consumer today, so the event row carries the delivery state. A second consumer for one type would need a per-consumer delivery table; not built until needed.
- **`published_at` keeps its name** and now means "delivered to its consumer". V16 only adds `sent_at`. A rename was dropped: it can't be undone on main and would break an instance still reading the old name during a deploy.
- **No ordering guarantee.** A retry, claim or resend can reorder events, so after a failure a guest could get "cancelled" before "confirmed". Accepted.
- **The entry carries the event id and type only.** The consumer loads the row, so there's one copy of the payload, and the row says whether the event was already delivered.
- **An event is marked sent, and that commits, before it's added to its stream.** The first version added it inside the transaction that marked it sent; `OutboxStreamLiveIT` caught a reader taking the entry 6 ms later, finding the row still locked by the relay, and leaving it for a 30-second claim. Committing first means a reader only ever sees settled rows. If the `XADD` then fails, the relay marks the event unsent again; if the relay dies in between, the sweeper resends it.
- **One consumer per event type is checked at startup** (`OutboxRoutes`), since the row's delivery state assumes it.
- **Redis's Compose settings don't change.** Postgres covers a lost stream; AOF would only cut down on resends after a restart.
- **CLAUDE.md** changes with the implementation, approved: "Redis is for speed and protection only" becomes "Redis is for speed and protection only, and may carry outbox events, which Postgres resends if Redis loses them."

## Architecture and data flow

```
booking tx ─► outbox_events row (waiting)
                 │  after commit: wake relay  (+ safety poll every 30 s)
                 ▼
OutboxRelay ─ sent_at = now (commit) ─► XADD outbox:{consumer}
                 ▼
consumer group "{consumer}" on each app instance
   XREADGROUP ─► consume(event) ─► published_at = now (Postgres tx) ─► XACK
                 ▲
OutboxSweeper ── sent, not published after 2 min ─► clear sent_at (relay sends again)
```

- Streams `outbox:booking-emails`, `outbox:account-emails` and `outbox:refunds`, each with a group of the same name. Each instance reads as a group consumer named after its hostname.
- `OutboxWriter` registers an after-commit hook that wakes the relay, so an event goes out within milliseconds. The relay still runs every 30 s (down from 5 s) as a safety net, for example after a crash.
- The 30-second safety net runs under `lock:outbox`, so one instance does it. A wake doesn't take the lock (it could be skipped while the safety net held it); relays that overlap split the queue by row lock instead.

## Failure handling

An entry is handled in one Postgres transaction: lock the row (`for update skip locked`); if it's already published, `XACK` and stop; otherwise run `consume(event)`, set `published_at`, commit, then `XACK`. The lock stops the original reader and a later claimer from acting at once; a locked row is skipped and stays pending.

| What goes wrong | What happens |
| --- | --- |
| A consumer throws | Rolled back, not acknowledged. A second transaction counts the attempt (`attempts`, `last_error`) as today, and moves `sent_at` on, since the stream still holds the entry: the sweeper doesn't resend an event that's being retried. The entry stays pending and is claimed (`XPENDING` + `XCLAIM`) once it's been idle 30 s. On the 10th failure the row is parked (`failed_at`) and the entry acknowledged, so the pending list doesn't grow |
| An instance dies mid-entry | Its pending entries go idle and another instance claims them after 30 s. The row says whether the work was committed |
| The commit succeeds but `XACK` fails | The entry comes back, finds the row published, and is acknowledged without acting |
| Redis is down | `XADD` fails, the event is marked unsent again, and the relay retries next run. Readers back off and log. The app stays ready (readiness checks only Postgres). Events are delayed, not lost. (Today's publisher also stops while Redis is down: its job lock lives there.) |
| Redis is flushed or restarts empty | Streams and groups are gone. On `NOGROUP`, or at startup, the group is recreated with `XGROUP CREATE ... MKSTREAM`. The sweeper clears `sent_at` on rows sent over 2 minutes ago and not published, and the relay sends them again |
| Redis is full | Stream keys have no TTL, so `volatile-ttl` never evicts them. If memory fills anyway, `XADD` fails as if Redis were down. Each stream is capped with `XADD MAXLEN ~ 10000`; an unacknowledged entry trimmed away is resent by the sweeper. Three streams of id-and-type entries come to about 3 MB of the 256 MB |

Guarantees: at least once per consumer, nothing lost, no ordering. Resends come within minutes, well inside Stripe's 24-hour idempotency-key window, and `CancellationRefunds` already sends the key `cancel-booking-{id}` and records the refund only on a `REFUND_PENDING` payment, so a duplicate refund call refunds once. The email consumers key each email by the outbox event's id.

Metrics, added to `BusinessMetrics` and the Grafana dashboard ("Outbox events per second", "Outbox streams"): `roomkeeper_outbox_sent_total`, `_published_total`, `_claimed_total` and `_resent_total`, and per consumer `roomkeeper_outbox_stream_pending` and `_stream_length` (read from Redis when scraped; no value while Redis fails).

## Components

**Migration V16:** `alter table outbox_events add column sent_at timestamptz`. The partial index on waiting rows (`published_at is null and failed_at is null`) stays: the unpublished set is small, and both the relay (`sent_at is null`) and the sweeper (`sent_at < cutoff`) read through it.

**Changed:**
- `OutboxConsumer` gains `name()`: `booking-emails`, `account-emails`, `refunds`. Its stream is `outbox:{name}`.
- `OutboxEvent` gains `markSent` (refused once published or parked) and `isPublished`; `recordFailure` also moves `sent_at` on.
- `OutboxWriter` wakes the relay after commit. Wakes merge, so a burst of bookings wakes it once.
- `OutboxPublisher` is removed; its work splits into the relay and the delivery below.

**New:**
- `OutboxRoutes`: each event type's one consumer and stream, checked at startup.
- `OutboxRelay`: reads unsent rows in batches; for each, locks it and marks it sent in one transaction, then `XADD`s it to its consumer's stream (`MAXLEN ~ 10000`), marking it unsent again if that fails. Runs when woken (unlocked) and every 30 s (under `lock:outbox`).
- `OutboxDelivery`: one event per transaction, as in Failure handling. Its outcome (published, already done, unknown, failed, parked, or busy elsewhere) says whether the entry can be acknowledged.
- `OutboxStreamReader`, one per consumer, run by `OutboxStreamReaders` (`SmartLifecycle`), each on its own virtual thread: loops `XREADGROUP BLOCK 500ms COUNT 50`, hands each entry to `OutboxDelivery`, acknowledges it when settled, and every 30 s claims entries idle 30 s or more. A group Redis lost is recreated from the stream's start (`0`, not `$`, so resends already in it are read) the moment a read says `NOGROUP`. Not on `@Scheduled`: Spring's scheduler has one thread by default, shared with hold expiry and the inventory roll. A blocking read runs on a dedicated connection (Spring Data Redis does that itself), and each reader keeps one bound to its thread rather than opening one per read.
- `OutboxSweeper`: every minute under `lock:outbox-sweep`, clears `sent_at` on rows sent over 2 minutes ago and not published, using the injected `Clock`, then wakes the relay.
- `OutboxReadBlockCheck`: refuses to start with `read-block` not under `spring.data.redis.timeout`.

Spring Data Redis has no `XAUTOCLAIM`, so claiming is `XPENDING` (idle filter) plus `XCLAIM`, both in its stream API.

**Config (`roomkeeper.outbox.*`):**

| Property | Value | Why |
| --- | --- | --- |
| `relay-interval` | 30s | Safety poll; the after-commit wake does the usual work |
| `read-block` | 500ms | Checked at startup to be under `spring.data.redis.timeout` (1 s), which would cut a longer block short |
| `claim-idle` | 30s | How long an entry sits unacknowledged before another reader takes it |
| `resend-after` | 2m | How long a sent row waits for delivery before the sweeper resends it |
| `max-stream-length` | 10000 | Approximate cap per stream |
| `live-delivery` | true | Reader threads and the after-commit wake; off in tests, which deliver by hand |

**Docs:** an "Outbox streams" row in the design doc's Redis table and an updated outbox section, the README, CLAUDE.md (above), and the roadmap tick.

## Tests

Test first, as always; no `@Transactional` on the concurrency tests.

Unit:
- `OutboxEventTest`: an event is sent until it's published, and a published or parked one isn't sent again. `OutboxEvent` is on PIT's domain list, so test strength stays 100%.
- `OutboxRoutesTest`: each type routes to its consumer's stream; a type with no consumer, a type with two, and two consumers with one name are refused.
- `OutboxReadBlockCheckTest`: startup fails when `read-block` isn't under the Redis command timeout, Spring's 60 s default included.
- `BusinessMetricsTest`: the outbox counters, and the stream gauges reading no value while Redis fails.

`OutboxStreamIT`, against the real Postgres and Redis containers, driving the relay, readers and sweeper by hand (`deliverOutbox()`: the relay, then claims without waiting, then each reader once):
1. Each event goes through its consumer's stream and is delivered once.
2. Each consumer reads only its own stream.
3. A change that rolls back sends nothing.
4. A consumer that keeps failing is retried, parked on the 10th failure, and its entry let go: nothing left pending.
5. An entry a crashed reader held is claimed and delivered: not before it's been idle long enough (Redis measures idle time with its own clock, so the test waits on a 200 ms claim with Awaitility).
6. An entry handed over again after its delivery committed (the `XACK` lost) is let go without acting: `FakeEmailSender` has one email.
7. Redis loses the stream: its key is deleted after sending; not resent before 2 minutes, then resent from Postgres with the group recreated, and delivered once.
8. A stream trimmed past an unread entry (`XTRIM`): resent and delivered.
9. Redis stops answering (`whilePaused`): an event written meanwhile isn't marked sent, and is delivered once Redis is back.
10. `@RaceTest`: four deliveries of one event at once publish it once.
11. `@RaceTest`: four relays at once put each of 20 events in the stream once.
12. The safety net skips its run while another instance holds `lock:outbox`.

`OutboxStreamLiveIT`, with live delivery on (its own context, closed after the class so its readers stop): a commit reaches its consumer within a second or so with no test calling the relay or a reader, and readers blocked on their streams don't hold up other Redis calls. This is the test that found the send-before-commit race above: it failed every time it ran after `OutboxStreamIT`, whose leftover rows changed the timing.

Test classes run one at a time, so deleting stream keys and pausing Redis are safe; every test starts with Redis emptied, so recreating lost groups is exercised by every test.

Existing tests:
- `OutboxPublisherIT` is gone; its cases are in `OutboxStreamIT`.
- `SignupIT`, `CancellationIT`, `LateWebhookIT` and `PaymentWebhookIT` use `deliverOutbox()`; their assertions don't change. `CancellationIT`'s "refunded, then died before its commit" also clears `sent_at`, so the event is sent again.
- `OutboxSchemaIT` checks `sent_at`, and that a failure moves it on. `PrometheusScrapeIT` checks the new series. `FlywayUpgradeIT` and `OpenApiSnapshotIT` don't change.
- `OutageIT`'s `whilePaused` moved to `Containers`, shared with test 9.

Done when: a full `clean verify`, the race tests for 3 rounds plus one on 2 CPUs, PIT, and a Compose smoke test in which a booking paid with the Stripe CLI gets its email, `roomkeeper_outbox_published_total` rises in Prometheus, and the new metrics show on the Grafana dashboard.

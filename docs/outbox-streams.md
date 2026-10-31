# Outbox over Redis Streams

Design for the stretch goal "publish outbox events to Redis Streams instead of polling". Agreed on 2026-10-31; not built yet.

## Goal

Show reliable delivery through a broker: consumer groups, acknowledgements, redelivery of entries a crashed consumer held, and Postgres resending whatever Redis lost. Consumers stay inside the app; each instance joins every group.

The rule that doesn't move: if Redis is flushed, a side effect can be late, never lost. Refunds go through the outbox, so a lost stream entry must never mean a lost refund. Postgres keeps the final say on what has been delivered.

## Decisions

- **Postgres records delivery; the stream only carries it.** An event is delivered when its consumer has committed `published_at`, not when it reaches Redis. Rejected: treating the row as published once `XADD` succeeds (a `FLUSHALL` or lost AOF would lose refunds silently), and using Redis only as a doorbell while consumers lock rows as today (`LISTEN/NOTIFY` would do that; the stream would add nothing).
- **One stream per consumer**, so a slow Stripe refund can't hold up emails and each consumer's delivery is tracked on its own. Every event type has exactly one consumer today, so the event row carries the delivery state. A second consumer for one type would need a per-consumer delivery table; not built until needed.
- **`published_at` keeps its name** and now means "delivered to its consumer". V16 only adds `sent_at`. A rename was dropped: it can't be undone on main and would break an instance still reading the old name during a deploy.
- **No ordering guarantee.** A retry, claim or resend can reorder events, so after a failure a guest could get "cancelled" before "confirmed". Accepted.
- **The entry carries the event id and type only.** The consumer loads the row, so there's one copy of the payload, and the row says whether the event was already delivered.
- **Redis's Compose settings don't change.** Postgres covers a lost stream; AOF would only cut down on resends after a restart.
- **CLAUDE.md** changes with the implementation, approved: "Redis is for speed and protection only" becomes "Redis is for speed and protection only, and may carry outbox events, which Postgres resends if Redis loses them."

## Architecture and data flow

```
booking tx ─► outbox_events row (waiting)
                 │  after commit: wake relay  (+ safety poll every 30 s)
                 ▼
OutboxRelay (lock:outbox) ─ XADD outbox:{consumer} ─► sent_at = now
                 ▼
consumer group "{consumer}" on each app instance
   XREADGROUP ─► consume(event) ─► published_at = now (Postgres tx) ─► XACK
                 ▲
OutboxSweeper ── sent, not published after 2 min ─► clear sent_at (relay sends again)
```

- Streams `outbox:booking-emails`, `outbox:account-emails` and `outbox:refunds`, each with a group of the same name. Each instance reads as a group consumer named after its hostname.
- `OutboxWriter` registers an after-commit hook that wakes the relay, so an event goes out within milliseconds. The relay still runs every 30 s (down from 5 s) as a safety net, for example after a crash.
- The relay keeps its job lock, so one instance sends at a time.

## Failure handling

An entry is handled in one Postgres transaction: lock the row (`for update skip locked`); if it's already published, `XACK` and stop; otherwise run `consume(event)`, set `published_at`, commit, then `XACK`. The lock stops the original reader and a later claimer from acting at once; a locked row is skipped and stays pending.

| What goes wrong | What happens |
| --- | --- |
| A consumer throws | Rolled back, not acknowledged. A second transaction counts the attempt (`attempts`, `last_error`) as today. The entry stays pending and is claimed (`XPENDING` + `XCLAIM`) once it's been idle 30 s. On the 10th failure the row is parked (`failed_at`) and the entry acknowledged, so the pending list doesn't grow |
| An instance dies mid-entry | Its pending entries go idle and another instance claims them after 30 s. The row says whether the work was committed |
| The commit succeeds but `XACK` fails | The entry comes back, finds the row published, and is acknowledged without acting |
| Redis is down | `XADD` fails, `sent_at` stays null, and the relay retries next run. Readers back off and log. The app stays ready (readiness checks only Postgres). Events are delayed, not lost. (Today's publisher also stops while Redis is down: its job lock lives there.) |
| Redis is flushed or restarts empty | Streams and groups are gone. On `NOGROUP`, or at startup, the group is recreated with `XGROUP CREATE ... MKSTREAM`. The sweeper clears `sent_at` on rows sent over 2 minutes ago and not published, and the relay sends them again |
| Redis is full | Stream keys have no TTL, so `volatile-ttl` never evicts them. If memory fills anyway, `XADD` fails as if Redis were down. Each stream is capped with `XADD MAXLEN ~ 10000`; an unacknowledged entry trimmed away is resent by the sweeper. Three streams of id-and-type entries come to about 3 MB of the 256 MB |

Guarantees: at least once per consumer, nothing lost, no ordering. Resends come within minutes, well inside Stripe's 24-hour idempotency-key window, and `CancellationRefunds` already sends the key `cancel-booking-{id}` and records the refund only on a `REFUND_PENDING` payment, so a duplicate refund call refunds once. The email consumers key each email by the outbox event's id.

Metrics, added to `BusinessMetrics` and the Grafana dashboard: events sent, published, claimed and resent (counters), and pending entries and stream length per consumer (gauges).

## Components

**Migration V16:** `alter table outbox_events add column sent_at timestamptz`. The partial index on waiting rows (`published_at is null and failed_at is null`) stays: the unpublished set is small, and both the relay (`sent_at is null`) and the sweeper (`sent_at < cutoff`) read through it.

**Changed:**
- `OutboxConsumer` gains `name()`: `booking-emails`, `account-emails`, `refunds`. Its stream is `outbox:{name}`.
- `OutboxEvent` gains `markSent`, `resend` and the delivered check; `markPublished` and `recordFailure` stay.
- `OutboxWriter` wakes the relay after commit. Wakes merge, as in `PendingRecomputes`, so a burst of bookings wakes it once.
- `OutboxPublisher` is removed; its work splits into the relay and the delivery below.

**New:**
- `OutboxRelay`: under `lock:outbox`, reads unsent rows in batches, `XADD`s each to its consumer's stream (`MAXLEN ~ 10000`) and sets `sent_at`. Runs when woken and every 30 s.
- `OutboxDelivery`: one event per transaction, as in Failure handling: lock, check, consume, publish, or count the failure and park.
- `OutboxStreamReader`: one per consumer, on its own virtual thread, started and stopped with the app (`SmartLifecycle`). Loops `XREADGROUP BLOCK 500ms COUNT 50`, hands each entry to `OutboxDelivery` and `XACK`s it, and every 30 s claims entries idle 30 s or more. Not on `@Scheduled`: Spring's scheduler has one thread by default, shared with hold expiry and the inventory roll. `roomkeeper.jobs.enabled=false` keeps the threads off in tests, which call `readOnce()` and `claimIdle()` themselves.
- `OutboxSweeper`: every minute under `lock:outbox-sweep`, clears `sent_at` on rows sent over 2 minutes ago and not published, using the injected `Clock`.

Spring Data Redis has no `XAUTOCLAIM`, so claiming is `XPENDING` (idle filter) plus `XCLAIM`, both in its stream API.

**Config (`roomkeeper.outbox.*`):**

| Property | Value | Why |
| --- | --- | --- |
| `relay-interval` | 30s | Safety poll; the after-commit wake does the usual work |
| `read-block` | 500ms | Checked at startup to be under `spring.data.redis.timeout` (1 s), which would cut a longer block short |
| `claim-idle` | 30s | How long an entry sits unacknowledged before another reader takes it |
| `resend-after` | 2m | How long a sent row waits for delivery before the sweeper resends it |
| `max-stream-length` | 10000 | Approximate cap per stream |

**Docs:** an "Outbox streams" row in the design doc's Redis table and an updated outbox section, the README, CLAUDE.md (above), and the roadmap tick.

## Tests

Test first, as always; no `@Transactional` on the concurrency tests.

Unit:
- `OutboxEventTest`: unsent, sent, published and parked; a resend clears `sent_at`; a published event isn't sent again. `OutboxEvent` is on PIT's domain list, so test strength stays 100%.
- `OutboxPropertiesTest`: startup fails when `read-block` isn't under the Redis command timeout.

`OutboxStreamIT`, against the real Postgres and Redis containers:
1. A committed event reaches its consumer within about a second, without a poll (Awaitility).
2. A change that rolls back sends nothing.
3. Each consumer gets its own stream: a `REFUND_DUE` event is only in `outbox:refunds`.
4. A consumer that throws is retried, parked on the 10th failure, and its entry acknowledged: the pending list ends empty.
5. An entry read but never acknowledged (a crashed reader) is claimed by another reader and delivered. Idle time is Redis's own clock, not the app's `Clock`, so this test sets `claim-idle` to 200 ms.
6. An entry delivered and handed over again (commit done, `XACK` lost) is acknowledged without acting: `FakeEmailSender` has one email.
7. Redis loses the streams: their keys are deleted after sending and before delivery; with the clock 2 minutes on, the sweeper resends, the group is recreated, and the event is delivered once.
8. Redis stops answering (`whilePaused`): an event written meanwhile waits, then is delivered once Redis is back, and the app stays ready throughout.
9. A trimmed stream (`max-stream-length: 5` here): unacknowledged entries trimmed away are resent by the sweeper.
10. `@RaceTest`: two readers deliver the same entry at the same moment (`runTogether`); the consumer acts once.

Test classes run one at a time, so deleting stream keys and pausing Redis are safe, as long as each test leaves Redis as it found it.

Existing tests:
- `OutboxPublisherIT` becomes relay and delivery tests.
- `SignupIT`, `CancellationIT`, `LateWebhookIT` and `PaymentWebhookIT` use a new `deliverOutbox()` in `AbstractIntegrationTest` (the relay, then each reader once); their assertions don't change.
- `OutboxSchemaIT` checks `sent_at`. `FlywayUpgradeIT` and `OpenApiSnapshotIT` don't change.

Done when: a full `clean verify`, the race tests for 3 rounds plus one on 2 CPUs, PIT, and a Compose smoke test in which a booking paid with the Stripe CLI gets its email, `roomkeeper_outbox_published_total` rises in Prometheus, and the new metrics show on the Grafana dashboard.

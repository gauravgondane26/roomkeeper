# Locking benchmark: pessimistic against optimistic

A hold has to make sure two guests can't take the same room. RoomKeeper does it
pessimistically: `POST /bookings/init` locks the stay's nights (`SELECT ... FOR UPDATE`, in
date order) until it commits, so holds for the same nights wait their turn. The usual
alternative is optimistic: read the nights without a lock, and at commit write them back
only if nobody changed them in the meantime (a version column, V15), starting over if
someone did. Both are in the code, chosen with `roomkeeper.booking.locking`
(`BOOKING_LOCKING` in Compose), so they can be measured on the same machine and data.
Pessimistic stays the default; this is why.

Both modes are equally correct, which `OptimisticLockingIT` and `LastRoomConcurrencyIT`
check: the last room goes to one guest out of 50, and the database's
`inventory_not_oversold` constraint refuses an oversold night whatever the code does. An
optimistic hold that has lost the race `optimistic-attempts` times (default 5, with a
random pause of up to 2^attempt ms between) answers 503 `booking-busy`.

## How it was run

[`loadtest/locking.js`](../loadtest/locking.js), k6 in Docker against the Compose stack.
Each run makes a new hotel with one room type of 10,000 rooms, more than a run sells, so
what's measured is the contention, not running out. Every hold is for one night.

```bash
BOOKING_LOCKING=optimistic docker compose up -d --wait app
# Moderate load: a fixed rate of holds over 3 nights
docker compose --profile loadtest run --rm -e LOCKING=optimistic -e RATE=300 -e DURATION=30s k6 run /loadtest/locking.js
# Heavy contention: 50 guests on one night, each holding again as soon as answered
docker compose --profile loadtest run --rm -e LOCKING=optimistic -e VUS=50 -e HOT_NIGHTS=1 -e DURATION=10s k6 run /loadtest/locking.js
```

Afterwards the run checks no night was oversold, and reads the app's
`roomkeeper.bookings.optimistic.retries` counter from the management port.

Same laptop as the [load test](load-test.md) (Windows 11, 15.4 GB RAM, Docker Desktop with
the app, Postgres 18, Redis 8, Prometheus, Grafana and k6 on one machine), 2026-10-31. The
database pool is 10 connections. The numbers are for comparing the two modes with each
other, not a capacity figure.

## Results

### Moderate load: holds arriving at a fixed rate, over 3 nights

| Holds/s offered | Mode | Holds made | Given up | Hold median | p95 | p99 | Optimistic retries |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 50 | pessimistic | 1,500 | 0 | 7.9 ms | 15.2 ms | 19.4 ms | - |
| 50 | optimistic | 1,500 | 0 | 8.1 ms | 14.6 ms | 17.6 ms | 3 |
| 150 | pessimistic | 4,500 | 0 | 4.7 ms | 6.9 ms | 8.6 ms | - |
| 150 | optimistic | 4,500 | 0 | 4.7 ms | 7.7 ms | 9.7 ms | 11 |
| 300 | pessimistic | 9,001 | 0 | 3.0 ms | 5.0 ms | 6.9 ms | - |
| 300 | optimistic | 9,001 | 0 | 3.1 ms | 5.4 ms | 8.7 ms | 112 |

A hold takes a few milliseconds, so at these rates two holds for the same night rarely
overlap. Both modes keep up, and the difference is within noise; optimistic's retries grow
with the rate (3, 11, 112), the first sign of what follows.

### Heavy contention: guests holding one night back to back, 10 s

| Guests | Mode | Holds made | Holds/s | Given up (503) | Hold median | p95 | p99 | Optimistic retries |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 25 | pessimistic | 4,071 | 376 | 0 | 58.8 ms | 95.2 ms | 127 ms | - |
| 25 | optimistic | 1,825 | 168 | 2,150 | 60.1 ms | 127 ms | 176 ms | 11,798 |
| 50 | pessimistic | 5,603 | 534 | 0 | 83.5 ms | 141 ms | 193 ms | - |
| 50 | optimistic | 2,598 | 249 | 3,130 | 95.2 ms | 142 ms | 163 ms | 17,126 |
| 100 | pessimistic | 6,688 | 639 | 0 | 146 ms | 220 ms | 297 ms | - |
| 100 | optimistic | 2,756 | 263 | 3,281 | 181 ms | 265 ms | 300 ms | 18,068 |

(The first heavy runs lasted 20 s and pessimistic sold all 10,000 rooms, so they were run
again for 10 s; the optimistic figures from the 20 s runs, 229 to 298 holds a second with
over half given up, agreed.)

Every run passed its checks, and no night was oversold.

## What it shows

- **Under contention, pessimistic locking makes 2.2 to 2.4 times as many holds**, and none
  fail. Holds for one night queue on its row lock and go through one after another, each
  doing its work once.
- **Optimistic gives up more than half its holds** with 5 attempts, and spends most of its
  effort on work it throws away: each lost attempt has read the nights, priced the stay,
  inserted a booking and rolled it all back. About 6.5 retries for every hold made at 25
  guests, and the share given up barely moves as guests grow (54% each time). More attempts
  would turn 503s into longer waits, not into more holds a second, since only one writer per
  night can win each round.
- **Without contention the two are the same**, so optimistic would only pay off where
  conflicts are rare and holding a lock is expensive (long transactions, or a lock held
  across a slow call). A hold here is a few milliseconds, and conflicts are exactly the
  case the system exists for: a popular night is where many guests collide.
- Pessimistic latency grows with the number of guests waiting (median 59 to 146 ms) while
  throughput rises, as more holds queue for the same row and the 10-connection pool. That
  wait is bounded: a request that can't get a connection within 3 s answers 503 to retry.

So holds stay pessimistic. The optimistic mode stays in the code, off by default, so the
comparison can be rerun after changes.

# Load test

[`loadtest/booking.js`](../loadtest/booking.js) drives the booking endpoint with [k6](https://k6.io), run in Docker against the Compose stack, while the RoomKeeper dashboard in Grafana watches the app:

```bash
docker compose --profile metrics up -d --build --wait
docker compose --profile loadtest run --rm k6
# Harder: holds and searches per second, and how long
docker compose --profile loadtest run --rm -e SPREAD_RATE=600 -e BROWSE_RATE=200 -e DURATION=1m k6
```

## What it checks

Setup creates a hotel in a new city with two room types, has the admin approve it, and logs in three guests. The accounts are reused from run to run (login first, signup only if that fails), so repeated runs stay inside the login and signup rate limits. Then three scenarios run together:

| Scenario | Load | Must hold |
| --- | --- | --- |
| `spread` | `SPREAD_RATE` holds a second (default 20), on random dates across the next 330 days, 1 to 3 nights, on a room type with 2,000 rooms | Every hold gets its rooms (201); p95 under 500 ms |
| `browse` | `BROWSE_RATE` searches a second (default 10) in the hotel's city, over five date ranges | Every search finds the hotel |
| `lastRooms` | 50 guests at once, 30 seconds in, for the 5 rooms of a suite on one night | Exactly 5 holds succeed, the other 45 get 409 `sold-out`, nothing else; afterwards the night shows 5 rooms held and 0 available |

Each of these is a k6 threshold, so a run that breaks one exits non-zero.

## Results (2026-10-30)

One Windows 11 laptop (15.4 GB RAM, under 1 GB free during runs), with Docker Desktop running the app (`prod` profile), Postgres 18, Redis 8, Prometheus, Grafana and k6 itself. The load generator shares the machine with what it's testing, so these figures are a floor, not a ceiling.

| Holds/s | Searches/s | Requests/s | Hold p95 | Hold max | Failed | Last 5 rooms |
| --- | --- | --- | --- | --- | --- | --- |
| 20 | 10 | 30 | 12.8 ms | 35 ms | 0 | 5 of 50 won, none oversold |
| 200 | 100 | 300 | 5.2 ms | 40 ms | 0 | 5 of 50 won, none oversold |
| 600 | 200 | 796 | 5.1 ms | 34 ms | 0 | 5 of 50 won, none oversold |
| 1,500 | 300 | 1,791 | 7.2 ms | 67 ms | 0 | 5 of 50 won, none oversold |

The first run is slower because the JVM hadn't warmed up yet. (Its 4 failed requests, outside the scenarios, were setup's first logins for accounts that didn't exist yet, which were then signed up.)

What the dashboard and Prometheus showed over the four runs:

- **No oversell under contention.** Each run's 50 simultaneous guests got exactly 5 rooms, the rest a clean 409. The rows are locked in date order, so the last room is sold once (the same guarantee `LastRoomConcurrencyIT` proves).
- **The database pool is the first limit in sight.** It has 10 connections. During the 50-guest race, 16 requests queued for one for a moment, and at 1,500 holds a second all 10 were in use at the peak. The app's own CPU peaked at 17% and its heap at 227 MB, so the app isn't the bottleneck yet.
- **Latency stayed flat.** The booking request's p99 peaked at 44 ms over any one-minute window.
- **The search cache answered 99.9% of searches.** A hold doesn't change what search shows unless a night sells out or reopens, so bookings don't evict cached searches.

## Finding: repricing drops recomputes under load

At 1,500 holds a second, the app logged `Repricing queue full; dropped a recompute` 51,501 times in about a minute. Each committed hold queues a recompute of its hotel's cheapest price (`hotel_min_price`) on a 2-thread executor with a 10,000-task queue. Every recompute in this test was for the same hotel and waits on that hotel's advisory lock, so the queue filled within seconds and the rest were dropped, as designed: the booking has committed, and a dropped recompute leaves the hotel's "from" price stale until its nights next change. The same task also evicts the city's cached searches when a sale sells out or frees up a night. A dropped one leaves those results, which can show availability that has changed, until they expire (60 seconds). Search reads availability from inventory, and holds are checked under lock, so nothing is oversold; a guest may just see a stale result for up to a minute.

Two things this shows:

1. **Almost all the queued work was redundant.** A recompute already waiting for a hotel covers any that arrive behind it. Coalescing them (at most one pending recompute per hotel, which reads the latest state when it runs) would keep the queue tiny and leave nothing to drop at any booking rate.
2. **A log line per dropped task floods the log.** That's 51,501 WARN lines in a minute. A counter (`roomkeeper.repricing.dropped`) plus a rate-limited warning would say the same thing.

Neither is fixed yet. A coalesced recompute would cover the union of the pending nights and evict searches if any of the sales it replaces would have.

## After the runs: releasing 152,425 abandoned holds

The four runs left 152,425 holds, none paid for, which ran out ten minutes after they were made. Their bookings read `EXPIRED` straight away, but until the expiry job reaches a hold, its rooms stay counted as reserved in inventory. Two short runs released the first 2,130; the next run released the other 150,295 and logged it 45 minutes later, at 13:43.

Most of those 45 minutes were the machine, not the job. Twice the Docker VM stalled: Prometheus recorded no scrapes at all from 13:02 to 13:18 and from 13:23 to 13:37 (the laptop had under 1 GB of memory free, and the full test suite ran during the second stall). Outside the stalls, holds were released at 200 to 480 a second.

Each hold is released in its own transaction, one after another, so that one failure can't keep the others' rooms off sale, and each commit waits for its flush to disk (a sample taken as the VM recovered showed the release waiting on `WalSync` at `COMMIT`). Bookings don't pay that wait one by one, because concurrent commits share a flush. A few hundred releases a second is plenty at this scale; if it ever isn't, the options are to commit releases with `synchronous_commit = off` (a release lost in a crash is redone by the next run), to release a small batch per transaction, or to run several release workers with `SKIP LOCKED`.

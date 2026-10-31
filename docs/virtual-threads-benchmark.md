# Virtual threads benchmark: before and after

Tomcat serves each request on one of its 200 platform threads; a request beyond that waits
for one to come free. With `spring.threads.virtual.enabled` (`VIRTUAL_THREADS` in Compose)
every request gets a virtual thread instead, and so do Spring's own async tasks and
scheduling, so a request blocked on I/O costs almost nothing while it waits. Java 25 no
longer pins a virtual thread inside `synchronized` (JEP 491), the old reason to be careful.
Both are in the code, switched by `VIRTUAL_THREADS`, so they can be measured on the same
machine and data. Platform threads stay the default; this is why.

`VirtualThreadsIT` checks the switch: with `VIRTUAL_THREADS=true` a request runs on a
virtual thread. The executors the app sizes on purpose stay as they are: repricing (2
threads, a bounded queue), closing checkout sessions (1 thread), and the outbox relay and
readers, which already run on virtual threads of their own.

## How it was run

[`loadtest/threads.js`](../loadtest/threads.js), k6 in Docker against the Compose stack. A
number of guests (VUs) each send their next request as soon as the last is answered, for 20
seconds, so the app always has that many requests in hand: 50, under Tomcat's 200 threads,
and 400, over them. Two kinds of request:

- **info**: a hotel's details, served from the Redis cache. No database, quick, CPU and
  Redis round trips.
- **holds**: `POST /bookings/init` on one night out of 270, 10,000 rooms each, so holds don't
  contend for rows. Each needs a database connection, and the pool has 10.

```bash
VIRTUAL_THREADS=false docker compose up -d --wait app
docker compose --profile loadtest run --rm --no-deps -e THREADS=platform -e MODE=holds -e VUS=400 k6 run /loadtest/threads.js
VIRTUAL_THREADS=true docker compose up -d --wait app
docker compose --profile loadtest run --rm --no-deps -e THREADS=virtual -e MODE=holds -e VUS=400 k6 run /loadtest/threads.js
```

Each round started the app in one mode, warmed it up (10 s of each kind), then ran all four.
Rounds alternated (platform, virtual, platform, virtual, platform) so neither mode always
ran on the busier database: every hold run adds tens of thousands of holds, and the expiry
job starts releasing them ten minutes later. The script also reads the JVM's peak thread
count and the connection pool's timed-out waits from the management port.

Same laptop as the [load test](load-test.md) (Windows 11, 15.4 GB RAM, Docker Desktop with
the app, Postgres 18, Redis 8, Prometheus, Grafana and k6 on one machine), 2026-10-31. The
numbers are for comparing the two modes with each other, not a capacity figure.

## Results

Requests answered per second, averaged over the rounds (platform 3, virtual 2), with the
spread between rounds and latency:

| Load | Platform threads | Virtual threads | Change |
| --- | --- | --- | --- |
| info, 50 guests | 14,414/s (14,121 to 14,762), p95 5.3 ms | 16,470/s (16,229 to 16,710), p95 4.5 ms | +14% |
| info, 400 guests | 17,967/s (17,660 to 18,517), p95 31 ms | 21,346/s (20,947 to 21,745), p95 31 ms | +19% |
| holds, 50 guests | 2,213/s (2,067 to 2,316), p95 37 ms | 1,691/s (1,531 to 1,850), p95 52 ms | -24% |
| holds, 400 guests | 2,427/s (2,355 to 2,474), p95 218 ms, p99 255 ms | 2,230/s (2,156 to 2,304), p95 207 ms, p99 352 ms | -8% |

- Peak JVM threads: about 248 on platform threads (Tomcat's 200 and the rest), 51 on virtual
  threads (the JVM counts only platform threads; virtual ones run on a few carriers).
- No run had a 503 or a timed-out connection wait, in either mode: at 400 guests, holds queue
  for the pool's 10 connections but get one well within its 3 s.

## What it means

- **Cached reads got faster.** Answering from Redis is CPU and round trips; virtual threads
  serve 14 to 19% more of them with fewer threads to switch between. Past 200 guests they
  stop queueing for a Tomcat thread, though the latency barely moves: the CPU is the limit.
- **Holds got slower.** The database is the limit, 10 connections, and virtual threads don't
  add any. They lost even at 50 guests, where Tomcat's threads weren't short, and had worse
  tails at 400. The likeliest reason, not proven here: Hikari hands each thread the
  connection it used last, from a thread-local list, and a new virtual thread per request
  never has one, so every borrow takes the slower shared path.
- **Where virtual threads would win** is many requests blocked on something slow outside
  the app, such as a payment provider taking hundreds of milliseconds: 200 platform threads
  would cap those at 200 at a time. Here that's opening a Stripe checkout, a small share of
  requests, and test-mode Stripe allows 25 requests a second, too few to measure it.

## Decision

Platform threads stay the default. The path that matters, holding and paying for rooms, is
database-bound, and virtual threads made it slower; the reads they speed up already run at
over 14,000 a second, far beyond what the app needs. `VIRTUAL_THREADS=true` stays, to run
this again if the mix changes: more time spent waiting on outside services, or a connection
pool that doesn't depend on thread-local reuse.

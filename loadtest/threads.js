// Virtual threads benchmark: the same load against the app on Tomcat's platform threads and
// on virtual threads, run with k6 in Docker against the Compose stack. Start the app one way,
// run, then the other:
//
//   VIRTUAL_THREADS=false docker compose up -d --wait app
//   docker compose --profile loadtest run --rm --no-deps -e THREADS=platform -e MODE=info -e VUS=400 k6 run /loadtest/threads.js
//   VIRTUAL_THREADS=true docker compose up -d --wait app
//   docker compose --profile loadtest run --rm --no-deps -e THREADS=virtual -e MODE=info -e VUS=400 k6 run /loadtest/threads.js
//
// --no-deps matters: without it, a shell whose VIRTUAL_THREADS differs from the running app's
// makes Compose recreate the app as k6 starts, and the run measures a restart. The first run
// makes a hotel and prints its ids; pass them as HOTEL_ID and ROOM_ID to reuse it, as logins
// are rate limited (10 a minute per IP).
//
// VUS guests (default 50) each send their next request as soon as the last is answered, for
// DURATION (default 20s), so the app always has VUS requests in hand; Tomcat's platform
// threads stop at 200. MODE picks what they send:
//   info   a hotel's details, served from the Redis cache: no database, quick, many at once
//   holds  holds on nights spread over 270 days, so they don't contend for rows: each needs a
//          database connection, and the pool has 10
// Reported: answers by outcome (200/201, 503 to retry, anything else), latency, and from the
// management port the JVM's peak thread count and connection waits that timed out.
// THREADS only labels the output; the app's mode is what VIRTUAL_THREADS started it with.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const METRICS_URL = __ENV.METRICS_URL || 'http://app:8081/actuator/prometheus';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@roomkeeper.local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'local-admin-password';
const PASSWORD = 'load-test-password';
const THREADS = __ENV.THREADS || 'unlabelled';
const MODE = __ENV.MODE || 'info';
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || '20s';
const ROOMS = 10000;
// One guest is enough: holds don't contend, and logins are rate limited (10 a minute per IP)
const GUESTS = 1;
// Set both to reuse the hotel an earlier run made (its teardown prints them), skipping its logins
const HOTEL_ID = __ENV.HOTEL_ID;
const ROOM_ID = __ENV.ROOM_ID;
const DAY = 24 * 60 * 60 * 1000;

const answered = new Counter('answered');
const retryLater = new Counter('answered_503');
const failed = new Counter('answered_other');
const latency = new Trend('answer_duration', true);

if (!['info', 'holds'].includes(MODE)) {
  throw new Error(`MODE must be info or holds, not ${MODE}`);
}

export const options = {
  scenarios: {
    load: { executor: 'constant-vus', exec: MODE, vus: VUS, duration: DURATION },
  },
  thresholds: {
    // A 503 to retry is a measured outcome; anything else is a failed run
    checks: ['rate==1'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function call(method, path, token, body, expected) {
  const params = { headers: { 'Content-Type': 'application/json' } };
  if (token) {
    params.headers.Authorization = `Bearer ${token}`;
  }
  const res = http.request(method, `${BASE_URL}${path}`, body ? JSON.stringify(body) : null, params);
  if (res.status !== expected) {
    fail(`${method} ${path}: expected ${expected}, got ${res.status}: ${res.body}`);
  }
  return res;
}

function logIn(email, password) {
  return http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } });
}

/** An access token for the account, signing it up first if it doesn't exist yet. */
function account(email, role) {
  let res = logIn(email, PASSWORD);
  if (res.status === 401) {
    call('POST', '/api/v1/auth/signup', null, { email, password: PASSWORD, name: 'Load Test', role }, 202);
    res = logIn(email, PASSWORD);
  }
  if (res.status !== 200) {
    fail(`login ${email}: ${res.status} ${res.body}`);
  }
  return res.json('accessToken');
}

function isoDate(daysFromToday) {
  return new Date(Date.now() + daysFromToday * DAY).toISOString().slice(0, 10);
}

/** A value from the app's Prometheus scrape, summed over its series; 0 if absent. */
function metric(name) {
  const body = http.get(METRICS_URL).body;
  return body.split('\n')
    .filter((line) => line.startsWith(`${name} `) || line.startsWith(`${name}{`))
    .reduce((sum, line) => sum + Number(line.split(' ').pop()), 0);
}

/** A hotel with one room type of ROOMS rooms, approved and on sale: two logins. */
function newHotel() {
  const login = logIn(ADMIN_EMAIL, ADMIN_PASSWORD);
  if (login.status !== 200) {
    fail(`admin login: ${login.status} ${login.body}`);
  }
  const admin = login.json('accessToken');
  const manager = account('loadtest-manager@example.com', 'HOTEL_MANAGER');
  const hotelId = call('POST', '/api/v1/admin/hotels', manager,
    { name: 'Threads Bench Inn', city: `Threadbench-${Date.now()}`, timeZone: 'UTC', amenities: ['wifi'] }, 201).json('id');
  const roomId = call('POST', `/api/v1/admin/hotels/${hotelId}/rooms`, manager,
    { type: 'Standard', basePrice: 4000, capacity: 2, totalCount: ROOMS }, 201).json('id');
  call('PATCH', `/api/v1/moderation/hotels/${hotelId}/approve`, admin, null, 200);
  call('PATCH', `/api/v1/admin/hotels/${hotelId}/activate`, manager, null, 200);
  return { hotelId, roomId };
}

export function setup() {
  const { hotelId, roomId } = HOTEL_ID && ROOM_ID
    ? { hotelId: Number(HOTEL_ID), roomId: Number(ROOM_ID) }
    : newHotel();
  // Fills the cache, so the info run measures cached reads
  call('GET', `/api/v1/hotels/${hotelId}/info`, null, null, 200);

  const guests = [];
  for (let i = 1; MODE === 'holds' && i <= GUESTS; i++) {
    guests.push(account(`loadtest-guest-${i}@example.com`, 'GUEST'));
  }
  return { hotelId, roomId, guests, timeoutsBefore: metric('hikaricp_connections_timeout_total') };
}

function record(res, ok) {
  latency.add(res.timings.duration, { outcome: String(res.status) });
  check(res, { [`${ok}, or 503 to retry`]: (r) => r.status === ok || r.status === 503 });
  if (res.status === ok) {
    answered.add(1);
  }
  else if (res.status === 503) {
    retryLater.add(1);
  }
  else {
    failed.add(1);
  }
}

export function info(data) {
  const res = http.get(`${BASE_URL}/api/v1/hotels/${data.hotelId}/info`, {
    tags: { name: 'GET /api/v1/hotels/{id}/info' },
    responseCallback: http.expectedStatuses(200, 503),
  });
  record(res, 200);
}

export function holds(data) {
  const checkIn = isoDate(30 + Math.floor(Math.random() * 270));
  const checkOut = new Date(Date.parse(checkIn) + DAY).toISOString().slice(0, 10);
  const res = http.post(`${BASE_URL}/api/v1/bookings/init`,
    JSON.stringify({ roomId: data.roomId, checkIn, checkOut, rooms: 1 }), {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${data.guests[__ITER % data.guests.length]}`,
        'Idempotency-Key': `threadbench-${__VU}-${__ITER}-${Date.now()}-${Math.random()}`,
      },
      tags: { name: 'POST /api/v1/bookings/init' },
      responseCallback: http.expectedStatuses(201, 503),
    });
  record(res, 201);
}

export function teardown(data) {
  const timeouts = metric('hikaricp_connections_timeout_total') - data.timeoutsBefore;
  console.log(`threads=${THREADS} mode=${MODE} vus=${VUS} duration=${DURATION}`
    + ` peakJvmThreads=${metric('jvm_threads_peak_threads')} liveJvmThreads=${metric('jvm_threads_live_threads')}`
    + ` connectionWaitsTimedOut=${timeouts} hotelId=${data.hotelId} roomId=${data.roomId}`);
}

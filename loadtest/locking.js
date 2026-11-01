// Locking benchmark: pessimistic against optimistic holds on the same few nights, run with
// k6 in Docker against the Compose stack. Start the app in one mode, run, then the other:
//
//   BOOKING_LOCKING=pessimistic docker compose up -d --wait app
//   docker compose --profile loadtest run --rm -e LOCKING=pessimistic -e RATE=100 k6 run /loadtest/locking.js
//   BOOKING_LOCKING=optimistic docker compose up -d --wait app
//   docker compose --profile loadtest run --rm -e LOCKING=optimistic -e RATE=100 k6 run /loadtest/locking.js
//
// Start the app with MAX_HOLDS_PER_GUEST=1000000 HOLD_RATE_LIMIT=1000000 set as well: a guest
// may normally hold 3 stays at once and ask for 20 holds a minute, and this run's three
// guests hold thousands of times.
//
// Every hold is for one of HOT_NIGHTS nights (default 3) of one room type, at RATE holds a
// second (default 100) for DURATION (default 30s), so holds keep colliding on the same rows.
// The room type has more rooms than the run can sell, so what's measured is the contention,
// not running out. Reported: holds made, given up (503 booking-busy, optimistic only),
// latency, and the app's count of optimistic retries. A night oversold fails the run.
// LOCKING only labels the output; the app's mode is what BOOKING_LOCKING started it with.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const METRICS_URL = __ENV.METRICS_URL || 'http://app:8081/actuator/prometheus';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@roomkeeper.local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'local-admin-password';
const PASSWORD = 'load-test-password';
const LOCKING = __ENV.LOCKING || 'unlabelled';
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '30s';
const HOT_NIGHTS = Number(__ENV.HOT_NIGHTS || 3);
const ROOMS = 10000;
const GUESTS = 3;
const DAY = 24 * 60 * 60 * 1000;

const held = new Counter('holds_made');
const busy = new Counter('holds_given_up');
const holdTime = new Trend('hold_duration', true);
// With VUS set, that many guests instead hold back to back (each sends its next hold as soon
// as the last is answered): the most contention the app can be given, usually with HOT_NIGHTS=1
const VUS = Number(__ENV.VUS || 0);

const scenario = VUS > 0
  ? { executor: 'constant-vus', exec: 'holdAHotNight', vus: VUS, duration: DURATION }
  : {
    executor: 'constant-arrival-rate',
    exec: 'holdAHotNight',
    rate: RATE,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: Math.max(10, Math.ceil(RATE / 5)),
    maxVUs: RATE * 4,
  };

export const options = {
  scenarios: { contended: scenario },
  thresholds: {
    // A hold that gives up is a measured outcome; anything else failing isn't
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

/** The app's count of optimistic holds that started over, from the management port. */
function retriesSoFar() {
  const res = http.get(METRICS_URL);
  const line = res.body.split('\n').find((l) => l.startsWith('roomkeeper_bookings_optimistic_retries_total'));
  return line ? Number(line.split(' ').pop()) : 0;
}

export function setup() {
  const login = logIn(ADMIN_EMAIL, ADMIN_PASSWORD);
  if (login.status !== 200) {
    fail(`admin login: ${login.status} ${login.body}`);
  }
  const admin = login.json('accessToken');
  const manager = account('loadtest-manager@example.com', 'HOTEL_MANAGER');
  const hotelId = call('POST', '/api/v1/admin/hotels', manager,
    { name: 'Locking Bench Inn', city: `Lockbench-${Date.now()}`, timeZone: 'UTC', amenities: [] }, 201).json('id');
  const roomId = call('POST', `/api/v1/admin/hotels/${hotelId}/rooms`, manager,
    { type: 'Standard', basePrice: 4000, capacity: 2, totalCount: ROOMS }, 201).json('id');
  call('PATCH', `/api/v1/moderation/hotels/${hotelId}/approve`, admin, null, 200);
  call('PATCH', `/api/v1/admin/hotels/${hotelId}/activate`, manager, null, 200);

  const guests = [];
  for (let i = 1; i <= GUESTS; i++) {
    guests.push(account(`loadtest-guest-${i}@example.com`, 'GUEST'));
  }
  const nights = [];
  for (let i = 0; i < HOT_NIGHTS; i++) {
    nights.push(isoDate(100 + i));
  }
  return { manager, roomId, guests, nights, retriesBefore: retriesSoFar() };
}

export function holdAHotNight(data) {
  const checkIn = data.nights[Math.floor(Math.random() * data.nights.length)];
  const checkOut = new Date(Date.parse(checkIn) + DAY).toISOString().slice(0, 10);
  const res = http.post(`${BASE_URL}/api/v1/bookings/init`,
    JSON.stringify({ roomId: data.roomId, checkIn, checkOut, rooms: 1 }), {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${data.guests[__ITER % data.guests.length]}`,
        'Idempotency-Key': `lockbench-${__VU}-${__ITER}-${Date.now()}-${Math.random()}`,
      },
      tags: { name: 'POST /api/v1/bookings/init' },
      responseCallback: http.expectedStatuses(201, 503),
    });
  holdTime.add(res.timings.duration, { outcome: String(res.status) });
  check(res, {
    'held (201), or given up after colliding (503 booking-busy)': (r) => r.status === 201
      || (r.status === 503 && r.json('type') === 'urn:roomkeeper:problem:booking-busy'),
  });
  if (res.status === 201) {
    held.add(1);
  }
  else if (res.status === 503) {
    busy.add(1);
  }
}

export function teardown(data) {
  const from = data.nights[0];
  const to = data.nights[data.nights.length - 1];
  const nights = call('GET', `/api/v1/admin/inventory/rooms/${data.roomId}?from=${from}&to=${to}`,
    data.manager, null, 200).json();
  check(nights, {
    'no hot night oversold': (all) => all.every((n) => n.reservedCount + n.bookedCount <= n.totalCount),
  });
  const retries = retriesSoFar() - data.retriesBefore;
  const load = VUS > 0 ? `vus=${VUS} back to back` : `rate=${RATE}/s`;
  console.log(`locking=${LOCKING} ${load} duration=${DURATION} hotNights=${HOT_NIGHTS}`
    + ` held=${nights.reduce((sum, n) => sum + n.reservedCount, 0)} optimisticRetries=${retries}`);
}

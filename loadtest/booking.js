// Load test for the booking endpoint, run with k6 in Docker against the Compose stack:
//
//   docker compose --profile metrics up -d --build --wait
//   docker compose --profile loadtest run --rm k6
//
// and watched on the RoomKeeper dashboard (http://localhost:3000). Three scenarios run
// together for two minutes (DURATION):
//   spread     20 holds a second (SPREAD_RATE) on dates across the year: every one gets its
//              rooms, fast
//   browse     4 searches a second (BROWSE_RATE) in the same city: mostly from the cache.
//              Search allows 5 a second per client IP; above that, start the app with a
//              higher limit: SEARCH_RATE_LIMIT=1000000 docker compose up -d app
//   lastRooms  50 guests at once for the last 5 rooms of one night: exactly 5 win, the
//              rest are told it's sold out, and the night isn't oversold
// Thresholds make k6 exit non-zero if any of that doesn't hold. To push harder:
//   docker compose --profile loadtest run --rm -e SPREAD_RATE=200 -e BROWSE_RATE=100 k6
//
// Setup makes a new hotel each run. Its accounts are reused across runs (login first,
// signup only if that fails), so repeated runs stay inside the login and signup rate limits.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@roomkeeper.local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'local-admin-password';
const PASSWORD = 'load-test-password';
const GUESTS = 3;
const LAST_ROOMS = 5;
const RACERS = 50;
const DAY = 24 * 60 * 60 * 1000;
const SPREAD_RATE = Number(__ENV.SPREAD_RATE || 20);
const BROWSE_RATE = Number(__ENV.BROWSE_RATE || 4);
const DURATION = __ENV.DURATION || '2m';

const lastRoomsWon = new Counter('last_rooms_won');

export const options = {
  scenarios: {
    spread: {
      executor: 'constant-arrival-rate',
      exec: 'bookSpread',
      rate: SPREAD_RATE,
      timeUnit: '1s',
      duration: DURATION,
      // Requests take milliseconds, so a few VUs keep up; more are added only if they don't
      preAllocatedVUs: Math.max(5, Math.ceil(SPREAD_RATE / 10)),
      maxVUs: SPREAD_RATE * 2,
    },
    browse: {
      executor: 'constant-arrival-rate',
      exec: 'browse',
      rate: BROWSE_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(5, Math.ceil(BROWSE_RATE / 10)),
      maxVUs: BROWSE_RATE * 2,
    },
    lastRooms: {
      executor: 'per-vu-iterations',
      exec: 'raceForLastRooms',
      vus: RACERS,
      iterations: 1,
      startTime: '30s',
      maxDuration: '1m',
    },
  },
  thresholds: {
    'http_req_failed{scenario:spread}': ['rate<0.01'],
    'http_req_duration{scenario:spread}': ['p(95)<500'],
    'http_req_failed{scenario:browse}': ['rate<0.01'],
    // A 409 sold out is an expected answer there; anything else isn't
    'http_req_failed{scenario:lastRooms}': ['rate==0'],
    last_rooms_won: [`count==${LAST_ROOMS}`],
    checks: ['rate==1'],
  },
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

function hold(token, roomId, checkIn, nights, extra) {
  const params = Object.assign({
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      'Idempotency-Key': `load-${__VU}-${__ITER}-${Date.now()}-${Math.random()}`,
    },
    tags: { name: 'POST /api/v1/bookings/init' },
  }, extra);
  const checkOut = new Date(Date.parse(checkIn) + nights * DAY).toISOString().slice(0, 10);
  return http.post(`${BASE_URL}/api/v1/bookings/init`,
    JSON.stringify({ roomId, checkIn, checkOut, rooms: 1 }), params);
}

export function setup() {
  const login = logIn(ADMIN_EMAIL, ADMIN_PASSWORD);
  if (login.status !== 200) {
    fail(`admin login: ${login.status} ${login.body}`);
  }
  const admin = login.json('accessToken');
  const manager = account('loadtest-manager@example.com', 'HOTEL_MANAGER');
  const city = `Loadtest-${Date.now()}`;
  const hotelId = call('POST', '/api/v1/admin/hotels', manager,
    { name: 'Load Test Inn', city, timeZone: 'UTC', amenities: [] }, 201).json('id');
  // Enough rooms that spread bookings never sell a night out, even at a few hundred a second
  const spreadRoom = call('POST', `/api/v1/admin/hotels/${hotelId}/rooms`, manager,
    { type: 'Standard', basePrice: 4000, capacity: 2, totalCount: 2000 }, 201).json('id');
  const lastRoom = call('POST', `/api/v1/admin/hotels/${hotelId}/rooms`, manager,
    { type: 'Suite', basePrice: 9000, capacity: 2, totalCount: LAST_ROOMS }, 201).json('id');
  call('PATCH', `/api/v1/moderation/hotels/${hotelId}/approve`, admin, null, 200);
  call('PATCH', `/api/v1/admin/hotels/${hotelId}/activate`, manager, null, 200);

  const guests = [];
  for (let i = 1; i <= GUESTS; i++) {
    guests.push(account(`loadtest-guest-${i}@example.com`, 'GUEST'));
  }
  return { manager, city, spreadRoom, lastRoom, guests, contestedNight: isoDate(200) };
}

export function bookSpread(data) {
  const guest = data.guests[__ITER % data.guests.length];
  const checkIn = isoDate(1 + Math.floor(Math.random() * 330));
  const res = hold(guest, data.spreadRoom, checkIn, 1 + Math.floor(Math.random() * 3));
  check(res, { 'spread: rooms held (201)': (r) => r.status === 201 });
}

export function browse(data) {
  const checkIn = isoDate(1 + Math.floor(Math.random() * 5) * 7);
  const checkOut = new Date(Date.parse(checkIn) + 2 * DAY).toISOString().slice(0, 10);
  const res = http.get(`${BASE_URL}/api/v1/hotels/search?city=${encodeURIComponent(data.city)}`
    + `&checkIn=${checkIn}&checkOut=${checkOut}`, { tags: { name: 'GET /api/v1/hotels/search' } });
  check(res, { 'browse: hotel found (200)': (r) => r.status === 200 && r.json('totalHotels') === 1 });
}

export function raceForLastRooms(data) {
  const guest = data.guests[__VU % data.guests.length];
  const res = hold(guest, data.lastRoom, data.contestedNight, 1,
    { responseCallback: http.expectedStatuses(201, 409) });
  check(res, {
    'last rooms: held (201) or sold out (409)': (r) => r.status === 201
      || (r.status === 409 && r.json('type') === 'urn:roomkeeper:problem:sold-out'),
  });
  if (res.status === 201) {
    lastRoomsWon.add(1);
  }
}

export function teardown(data) {
  const night = call('GET', `/api/v1/admin/inventory/rooms/${data.lastRoom}?from=${data.contestedNight}`
    + `&to=${data.contestedNight}`, data.manager, null, 200).json('0');
  check(night, {
    'contested night: all 5 rooms held, none oversold': (n) => n.reservedCount + n.bookedCount === LAST_ROOMS
      && n.available === 0 && n.totalCount === LAST_ROOMS,
  });
}

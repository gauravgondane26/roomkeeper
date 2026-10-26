#!/usr/bin/env bash
# Smoke test against a running stack, using the real Docker image:
#   docker compose up -d --build --wait && scripts/smoke.sh
# BASE_URL overrides the target (default http://localhost:8080).
# Each week adds checks for the endpoints that week delivers.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
WAIT_SECONDS="${WAIT_SECONDS:-60}"
failures=0
# When set, check() sends it as a bearer token
TOKEN=""
# The response body of the last check(), for pulling out ids
last_body=""

# check <description> <method> <path> <expected status> [expected body substring] [JSON request body]
check() {
  local description=$1 method=$2 path=$3 expected_status=$4 expected_body=${5:-} request_body=${6:-}
  local body status
  local -a data=()
  if [[ -n "$request_body" ]]; then
    data=(-H 'Content-Type: application/json' -d "$request_body")
  fi
  if [[ -n "$TOKEN" ]]; then
    data+=(-H "Authorization: Bearer $TOKEN")
  fi
  body=$(curl -s --max-time 10 -X "$method" "${data[@]}" -w '\n%{http_code}' "$BASE_URL$path") || true
  status=${body##*$'\n'}
  body=${body%$'\n'*}
  last_body=$body
  if [[ "$status" != "$expected_status" ]]; then
    echo "FAIL $description: expected HTTP $expected_status, got $status"
    failures=$((failures + 1))
  elif [[ -n "$expected_body" && "$body" != *"$expected_body"* ]]; then
    echo "FAIL $description: body missing '$expected_body': $body"
    failures=$((failures + 1))
  else
    echo "ok   $description"
  fi
}

echo "Waiting up to ${WAIT_SECONDS}s for $BASE_URL ..."
deadline=$((SECONDS + WAIT_SECONDS))
until curl -sf --max-time 5 "$BASE_URL/actuator/health" >/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "FAIL app did not become reachable within ${WAIT_SECONDS}s"
    exit 1
  fi
  sleep 1
done

# Week 1: the app is up and healthy
check "health reports UP" GET /actuator/health 200 '"status":"UP"'

# Week 2: API docs and auth
check "API docs are public" GET /v3/api-docs 200 '"title":"RoomKeeper API"'
check "Swagger UI is public" GET /swagger-ui/index.html 200 "Swagger UI"
check "anonymous request is a 401 problem" GET /api/v1/does-not-exist 401 "urn:roomkeeper:problem:unauthorized"
email="smoke-$(date +%s)-$RANDOM@example.com"
signup_body="{\"email\":\"$email\",\"password\":\"smoke-test-password\",\"name\":\"Smoke Test\"}"
check "signup creates a guest" POST /api/v1/auth/signup 201 '"role":"GUEST"' "$signup_body"
check "duplicate signup is a 409 problem" POST /api/v1/auth/signup 409 \
  "urn:roomkeeper:problem:email-already-registered" "$signup_body"
check "login returns a bearer token" POST /api/v1/auth/login 200 '"tokenType":"Bearer"' \
  "{\"email\":\"$email\",\"password\":\"smoke-test-password\"}"
check "wrong password is a 401 problem" POST /api/v1/auth/login 401 \
  "urn:roomkeeper:problem:invalid-credentials" "{\"email\":\"$email\",\"password\":\"wrong-password\"}"

# access_token <email>: logs in with the smoke password and prints the access token
access_token() {
  curl -s -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"smoke-test-password\"}" "$BASE_URL/api/v1/auth/login" \
    | sed -nE 's/.*"accessToken":"([^"]+)".*/\1/p'
}
guest_token=$(access_token "$email")
TOKEN=$guest_token
check "profile with the token" GET /api/v1/users/profile 200 "\"email\":\"$email\""
check "profile name update" PATCH /api/v1/users/profile 200 '"name":"Smoke Renamed"' '{"name":"Smoke Renamed"}'
TOKEN=""

refresh_token() {
  curl -s -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"smoke-test-password\"}" "$BASE_URL/api/v1/auth/login" \
    | sed -nE 's/.*"refreshToken":"([^"]+)".*/\1/p'
}
rotated="{\"refreshToken\":\"$(refresh_token)\"}"
check "refresh issues a new token pair" POST /api/v1/auth/refresh 200 '"refreshToken"' "$rotated"
check "a used refresh token is rejected" POST /api/v1/auth/refresh 401 \
  "urn:roomkeeper:problem:invalid-refresh-token" "$rotated"
logged_out="{\"refreshToken\":\"$(refresh_token)\"}"
check "logout succeeds" POST /api/v1/auth/logout 204 "" "$logged_out"
check "refresh after logout is rejected" POST /api/v1/auth/refresh 401 \
  "urn:roomkeeper:problem:invalid-refresh-token" "$logged_out"

# Week 3: hotel admin
TOKEN=$guest_token
check "guests can't manage hotels" GET /api/v1/admin/hotels 403 "urn:roomkeeper:problem:forbidden"
manager_email="smoke-manager-$(date +%s)-$RANDOM@example.com"
check "signup creates a hotel manager" POST /api/v1/auth/signup 201 '"role":"HOTEL_MANAGER"' \
  "{\"email\":\"$manager_email\",\"password\":\"smoke-test-password\",\"name\":\"Smoke Manager\",\"role\":\"HOTEL_MANAGER\"}"
TOKEN=$(access_token "$manager_email")
# A city of its own, so searches only find this run's hotel
city="SmokeCity$(date +%s)$RANDOM"
check "manager creates a hotel" POST /api/v1/admin/hotels 201 '"timeZone":"Asia/Kolkata"' \
  "{\"name\":\"Smoke Hotel\",\"city\":\"$city\",\"timeZone\":\"Asia/Kolkata\",\"amenities\":[\"wifi\"]}"
hotel_id=$(sed -nE 's/^\{"id":([0-9]+).*/\1/p' <<<"$last_body")
check "manager lists their hotels" GET /api/v1/admin/hotels 200 '"name":"Smoke Hotel"'
check "manager reads their hotel" GET "/api/v1/admin/hotels/$hotel_id" 200 "\"id\":$hotel_id"
check "manager adds a room type" POST "/api/v1/admin/hotels/$hotel_id/rooms" 201 '"basePrice":4500.00' \
  '{"type":"Deluxe","basePrice":4500,"capacity":2,"totalCount":5}'
room_id=$(sed -nE 's/^\{"id":([0-9]+).*/\1/p' <<<"$last_body")
check "manager lists the hotel's rooms" GET "/api/v1/admin/hotels/$hotel_id/rooms" 200 "\"id\":$room_id"
check "a hotel can't go on sale before review" PATCH "/api/v1/admin/hotels/$hotel_id/activate" 409 \
  "urn:roomkeeper:problem:hotel-not-approved"
manager_token=$TOKEN
# The development admin from compose.yaml; override with ADMIN_EMAIL / ADMIN_PASSWORD
TOKEN=$(curl -s -H 'Content-Type: application/json' \
  -d "{\"email\":\"${ADMIN_EMAIL:-admin@roomkeeper.local}\",\"password\":\"${ADMIN_PASSWORD:-local-admin-password}\"}" \
  "$BASE_URL/api/v1/auth/login" | sed -nE 's/.*"accessToken":"([^"]+)".*/\1/p')
check "the hotel is in the review queue" GET /api/v1/moderation/hotels 200 "\"id\":$hotel_id"
check "admin approves the hotel" PATCH "/api/v1/moderation/hotels/$hotel_id/approve" 200 '"approvalStatus":"APPROVED"'
TOKEN=$manager_token
check "manager activates the hotel" PATCH "/api/v1/admin/hotels/$hotel_id/activate" 200 '"active":true'
night=$(date -u -d '+2 days' +%F)
check "manager sees the room's inventory" GET "/api/v1/admin/inventory/rooms/$room_id?from=$night&to=$night" 200 \
  "\"date\":\"$night\",\"totalCount\":5"
check "manager closes a night" PATCH "/api/v1/admin/inventory/rooms/$room_id" 200 '"closed":true' \
  "{\"from\":\"$night\",\"to\":\"$night\",\"closed\":true,\"surgeFactor\":1.5}"
manager_token=$TOKEN
TOKEN=""

# Week 4: browsing, no login
check_in=$(date -u -d '+3 days' +%F)
check_out=$(date -u -d '+5 days' +%F)
check "guests find the hotel" GET "/api/v1/hotels/search?city=$city&checkIn=$check_in&checkOut=$check_out" 200 \
  "\"id\":$hotel_id"
check "a stay over the closed night finds nothing" GET \
  "/api/v1/hotels/search?city=$city&checkIn=$night&checkOut=$check_out" 200 '"totalHotels":0'
check "guests see the hotel's details" GET "/api/v1/hotels/$hotel_id/info" 200 '"type":"Deluxe"'
# The search above is now cached; closing a night of that stay must evict it
TOKEN=$manager_token
check "manager closes a night of the cached stay" PATCH "/api/v1/admin/inventory/rooms/$room_id" 200 '"closed":true' \
  "{\"from\":\"$check_in\",\"to\":\"$check_in\",\"closed\":true}"
TOKEN=""
check "the cached search no longer finds the hotel" GET \
  "/api/v1/hotels/search?city=$city&checkIn=$check_in&checkOut=$check_out" 200 '"totalHotels":0'
TOKEN=""

# Last, since it uses up this client's login attempts: within 11 tries the limit must hit
limited=""
for _ in $(seq 1 11); do
  status=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"wrong-password\"}" "$BASE_URL/api/v1/auth/login")
  if [[ "$status" == "429" ]]; then limited=yes; break; fi
done
if [[ -n "$limited" ]]; then
  echo "ok   login is rate limited"
else
  echo "FAIL login is rate limited: no 429 within 11 attempts"
  failures=$((failures + 1))
fi

if (( failures > 0 )); then
  echo "$failures smoke check(s) failed"
  exit 1
fi
echo "All smoke checks passed"

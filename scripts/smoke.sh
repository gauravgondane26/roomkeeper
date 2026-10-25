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

# Week 2: auth
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

TOKEN=$(curl -s -H 'Content-Type: application/json' \
  -d "{\"email\":\"$email\",\"password\":\"smoke-test-password\"}" "$BASE_URL/api/v1/auth/login" \
  | sed -nE 's/.*"accessToken":"([^"]+)".*/\1/p')
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

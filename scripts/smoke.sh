#!/usr/bin/env bash
# Smoke test against a running stack, using the real Docker image:
#   docker compose up -d --build --wait && scripts/smoke.sh
# BASE_URL overrides the target (default http://localhost:8080).
# Each week adds checks for the endpoints that week delivers.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
WAIT_SECONDS="${WAIT_SECONDS:-60}"
failures=0

# check <description> <method> <path> <expected status> [expected body substring] [JSON request body]
check() {
  local description=$1 method=$2 path=$3 expected_status=$4 expected_body=${5:-} request_body=${6:-}
  local body status
  local -a data=()
  if [[ -n "$request_body" ]]; then
    data=(-H 'Content-Type: application/json' -d "$request_body")
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
check "signup creates a guest" POST /api/v1/auth/signup 201 '"role":"GUEST"'   "{\"email\":\"$email\",\"password\":\"smoke-test-password\",\"name\":\"Smoke Test\"}"
check "duplicate signup is a 409 problem" POST /api/v1/auth/signup 409 "urn:roomkeeper:problem:email-already-registered"   "{\"email\":\"$email\",\"password\":\"smoke-test-password\",\"name\":\"Smoke Test\"}"

if (( failures > 0 )); then
  echo "$failures smoke check(s) failed"
  exit 1
fi
echo "All smoke checks passed"

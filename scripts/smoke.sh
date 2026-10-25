#!/usr/bin/env bash
# Smoke test against a running stack, using the real Docker image:
#   docker compose up -d --build --wait && scripts/smoke.sh
# BASE_URL overrides the target (default http://localhost:8080).
# Each week adds checks for the endpoints that week delivers.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
WAIT_SECONDS="${WAIT_SECONDS:-60}"
failures=0

# check <description> <method> <path> <expected status> [expected body substring]
check() {
  local description=$1 method=$2 path=$3 expected_status=$4 expected_body=${5:-}
  local body status
  body=$(curl -s --max-time 10 -X "$method" -w '\n%{http_code}' "$BASE_URL$path") || true
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
check "unknown route is a 404 problem" GET /does-not-exist 404 "urn:roomkeeper:problem:not-found"

if (( failures > 0 )); then
  echo "$failures smoke check(s) failed"
  exit 1
fi
echo "All smoke checks passed"

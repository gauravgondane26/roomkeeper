#!/usr/bin/env bash
# Checks the metrics stack (docker compose --profile metrics up -d --wait): Prometheus
# scrapes the app, Grafana serves the RoomKeeper dashboard through its Prometheus data
# source, and every query on the dashboard returns data. A query naming a metric that
# doesn't exist still succeeds, with nothing in it, so each one must return a series.
# Run scripts/smoke.sh first, so the requests the dashboard charts have happened.
set -euo pipefail

PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
WAIT_SECONDS="${WAIT_SECONDS:-60}"
DASHBOARD="$(dirname "$0")/../monitoring/grafana/dashboards/roomkeeper.json"
failures=0

ok() { echo "ok   $1"; }
fail() { echo "FAIL $1"; failures=$((failures + 1)); }

# expect <description> <url> <expected body substring>
expect() {
  local body
  body=$(curl -s --max-time 10 "$2") || true
  if [[ "$body" == *"$3"* ]]; then ok "$1"; else fail "$1: no '$3' in ${body:0:300}"; fi
}

# query <PromQL>: Prometheus's answer to an instant query, as JSON
query() {
  curl -s --max-time 10 -G "$PROMETHEUS_URL/api/v1/query" --data-urlencode "query=$1" || true
}

echo "Waiting up to ${WAIT_SECONDS}s for Prometheus to scrape the app twice ..."
deadline=$((SECONDS + WAIT_SECONDS))
# Two samples in the last minute, so rate() has something to work with
until [[ "$(query 'count_over_time(up{job="roomkeeper"}[1m]) >= 2')" == *'"result":[{'* ]]; do
  if (( SECONDS >= deadline )); then
    echo "FAIL Prometheus at $PROMETHEUS_URL has no two scrapes of the app within ${WAIT_SECONDS}s"
    exit 1
  fi
  sleep 2
done

expect "Prometheus scrapes the app's management port" "$PROMETHEUS_URL/api/v1/targets?state=active" \
  '"health":"up"'
expect "Grafana is up" "$GRAFANA_URL/api/health" '"database"'
expect "Grafana serves the dashboard without a login" "$GRAFANA_URL/api/dashboards/uid/roomkeeper" \
  '"title":"RoomKeeper"'
expect "Grafana reaches Prometheus" \
  "$GRAFANA_URL/api/datasources/proxy/uid/prometheus/api/v1/query?query=up" '"status":"success"'

# Every panel query, with Grafana's interval variables filled in as a short range would
queries=$(grep -oE '"expr": *"([^"\\]|\\.)*"' "$DASHBOARD" \
  | sed -E 's/^"expr": *"//; s/"$//; s/\\"/"/g; s/\$__rate_interval/1m/g; s/\$__range/5m/g; s/\$__interval/15s/g')
if [[ -z "$queries" ]]; then
  fail "no queries found in $DASHBOARD"
fi
while IFS= read -r promql; do
  [[ -z "$promql" ]] && continue
  answer=$(query "$promql")
  if [[ "$answer" != *'"status":"success"'* ]]; then
    fail "dashboard query fails: $promql: ${answer:0:200}"
  elif [[ "$answer" != *'"result":[{'* ]]; then
    fail "dashboard query returns nothing: $promql"
  else
    ok "dashboard query returns data: $promql"
  fi
done <<< "$queries"

if (( failures > 0 )); then
  echo "$failures metrics check(s) failed"
  exit 1
fi
echo "All metrics checks passed"

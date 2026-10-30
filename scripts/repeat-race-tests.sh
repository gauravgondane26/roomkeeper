#!/usr/bin/env bash
# Runs every @RaceTest (JUnit tag "race") ROUNDS times, default 10, as the nightly
# workflow does. A race that loses only now and then can pass a single CI run, so every
# round runs even after one fails: the count of failed rounds says how rare it is.
# Each failed round's Failsafe reports are kept in target/race-rounds/<round>/.
# Needs Docker running (Testcontainers).
set -euo pipefail
cd "$(dirname "$0")/.."

rounds="${1:-10}"
failed=0
rm -rf target/race-rounds

for round in $(seq 1 "$rounds"); do
  echo "::group::Round $round of $rounds"
  if ./mvnw -B -ntp verify -DskipUnitTests -Djacoco.skip=true -Dgroups=race; then
    echo "::endgroup::"
    echo "Round $round passed"
  else
    echo "::endgroup::"
    echo "Round $round FAILED"
    failed=$((failed + 1))
    mkdir -p "target/race-rounds/$round"
    cp -r target/failsafe-reports/. "target/race-rounds/$round/"
  fi
done

echo "$failed of $rounds rounds failed"
[ "$failed" -eq 0 ]

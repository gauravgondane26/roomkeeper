#!/usr/bin/env bash
# Fails if the commits since <base> modify, rename, or delete an existing versioned
# Flyway migration. Once a migration is on main it may have run against a database,
# and Flyway refuses to start when an applied file's checksum changes: add a new
# V<n>__ file instead. CI passes the commit the push or pull request starts from.
#   scripts/check-migrations.sh <base commit>
set -euo pipefail

base=${1:?usage: scripts/check-migrations.sh <base commit>}
dir=src/main/resources/db/migration

# A new branch or a rewritten history has no base to compare against
if [[ "$base" =~ ^0+$ ]] || ! git cat-file -e "$base^{commit}" 2>/dev/null; then
  echo "::warning::No base commit $base to compare migrations against; skipping the check"
  exit 0
fi

# --no-renames shows a rename as a delete and an add, so it is caught too
changed=$(git diff --no-renames --name-status --diff-filter=MD "$base"...HEAD -- "$dir" \
  | awk -F'\t' '$2 ~ /\/V[^\/]*$/')
if [[ -n "$changed" ]]; then
  echo "Applied migrations must not change. Add a new migration instead:"
  while IFS=$'\t' read -r status file; do
    echo "::error file=$file::$([[ $status == M ]] && echo modified || echo deleted) after it was merged"
  done <<<"$changed"
  exit 1
fi
echo "No existing migration changed since ${base:0:12}"

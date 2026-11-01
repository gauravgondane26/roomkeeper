#!/usr/bin/env bash
# Creates .env for Docker Compose, or adds what it's missing: a random JWT secret, Postgres
# password, Redis password, admin password and Grafana admin password. A value already set is never changed,
# and no value is ever printed, only the names of the ones added. Run it once before the
# first `docker compose up`; running it again changes nothing.
#
# An existing Postgres volume keeps the password it was created with. If .env gets a new
# POSTGRES_PASSWORD, either recreate the volume (docker compose down -v: deletes local data)
# or set the new password in the running database:
#   docker compose exec postgres sh -c 'psql -U roomkeeper -c "alter user roomkeeper password '"'"'$NEW'"'"'"'
set -euo pipefail

env_file="${ENV_FILE:-$(dirname "$0")/../.env}"
touch "$env_file"

# 32 random bytes, base64: the JWT secret's key
base64_secret() { head -c 32 /dev/urandom | base64 | tr -d '\n'; }
# 24 random bytes as hex: passwords that need no quoting anywhere
hex_password() { od -An -tx1 -N24 /dev/urandom | tr -d ' \n'; }

# add <name> <value>: sets the variable unless it already has a value
add() {
  if grep -qE "^$1=.+" "$env_file"; then
    return
  fi
  # An empty "NAME=" line is replaced, not duplicated
  sed -i "/^$1=[[:space:]]*\$/d" "$env_file"
  [[ -s "$env_file" && -n "$(tail -c1 "$env_file")" ]] && echo >> "$env_file"
  printf '%s=%s\n' "$1" "$2" >> "$env_file"
  echo "added $1"
}

add JWT_SECRET "$(base64_secret)"
add POSTGRES_PASSWORD "$(hex_password)"
add REDIS_PASSWORD "$(hex_password)"
add ADMIN_PASSWORD "$(hex_password)"
add GRAFANA_ADMIN_PASSWORD "$(hex_password)"

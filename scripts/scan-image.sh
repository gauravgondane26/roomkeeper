#!/usr/bin/env bash
# Scans the app image (roomkeeper:local, built by `docker compose up --build` or
# `docker compose build app`) for known vulnerabilities with Grype, as CI does after its
# smoke test. Fails on a high or critical vulnerability that has a fix; .grype.yaml holds
# the settings and any accepted vulnerability, with its reason.
# The image is handed over as a tarball, so the scanner never gets the Docker socket.
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p target
docker save roomkeeper:local -o target/roomkeeper-image.tar
docker compose --profile scan run --rm grype

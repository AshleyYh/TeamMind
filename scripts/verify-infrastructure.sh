#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is required for real infrastructure integration tests." >&2
    exit 1
fi

cleanup() {
    docker compose -f docker-compose.test.yml down --volumes --remove-orphans
}
trap cleanup EXIT

docker compose -f docker-compose.test.yml up -d --wait
./mvnw -pl team-mind-web -am test -Dgroups=integration -Dsurefire.excludedGroups=

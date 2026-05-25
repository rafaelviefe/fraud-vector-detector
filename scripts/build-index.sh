#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
OUT="${1:-resources/index.bin}"

./gradlew buildIndex --no-daemon -q

ls -lh "$OUT"
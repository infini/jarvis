#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-/tmp/jarvis-activation-captures-$(date +%Y%m%d-%H%M%S)}"
PACKAGE="com.personal.jarvis"
CAPTURE_DIR="jarvis-activation-attempts"

mkdir -p "$OUT_DIR"

if ! adb exec-out run-as "$PACKAGE" sh -c "cd cache && test -d '$CAPTURE_DIR' && tar cf - '$CAPTURE_DIR'" |
  tar -xf - -C "$OUT_DIR"; then
  echo "FAIL: could not pull activation captures. Make sure a debug APK is installed and at least one activation attempt was saved." >&2
  exit 1
fi

echo "Saved activation captures to $OUT_DIR/$CAPTURE_DIR"
find "$OUT_DIR/$CAPTURE_DIR" -maxdepth 1 -type f | sort

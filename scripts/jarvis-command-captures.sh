#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-/tmp/jarvis-command-captures-$(date +%Y%m%d-%H%M%S)}"
PACKAGE="com.personal.jarvis"
CAPTURE_DIR="command-recognition-attempts"

mkdir -p "$OUT_DIR"

if ! adb exec-out run-as "$PACKAGE" sh -c "cd cache && test -d '$CAPTURE_DIR' && tar cf - '$CAPTURE_DIR'" |
  tar -xf - -C "$OUT_DIR"; then
  echo "FAIL: could not pull command captures. Install a debug APK and produce at least one local command no-command or voice_sample_match attempt." >&2
  exit 1
fi

echo "Saved command captures to $OUT_DIR/$CAPTURE_DIR"
find "$OUT_DIR/$CAPTURE_DIR" -maxdepth 1 -type f | sort

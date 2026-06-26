#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="${GRADLEW:-$ROOT_DIR/gradlew}"

if [ ! -x "$GRADLEW" ]; then
  echo "FAIL: Gradle wrapper is not executable: $GRADLEW" >&2
  exit 1
fi

"$GRADLEW" -p "$ROOT_DIR" testDebugUnitTest assembleDebug lintDebug
git -C "$ROOT_DIR" diff --check
git -C "$ROOT_DIR" diff --cached --check
bash -n "$ROOT_DIR"/scripts/*.sh

echo "PASS: Jarvis quality checks completed."

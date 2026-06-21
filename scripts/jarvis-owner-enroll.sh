#!/usr/bin/env bash
set -euo pipefail

DURATION_SECONDS="${1:-6}"
ACTIVITY="com.personal.jarvis/.debug.JarvisDebugOwnerEnrollActivity"
LOG_TAG="JarvisDebugEnroll"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATUS_SCRIPT="$SCRIPT_DIR/jarvis-profile-status.sh"
REQUEST_ID="$(date +%s)-$$"

case "$DURATION_SECONDS" in
  ''|*[!0-9]*)
    echo "usage: $0 [duration_seconds]" >&2
    exit 2
    ;;
esac

DURATION_MS=$((DURATION_SECONDS * 1000))
WAIT_DEADLINE=$((DURATION_SECONDS + 30))

echo "Speak now: say '자비스 실행' repeatedly for ${DURATION_SECONDS}s."
adb shell am start \
  -n "$ACTIVITY" \
  --el duration_ms "$DURATION_MS" \
  --es request_id "$REQUEST_ID" >/dev/null

for ((elapsed = 0; elapsed < WAIT_DEADLINE; elapsed++)); do
  LOG_OUTPUT="$(adb logcat -d -v time -s "$LOG_TAG" | grep "request_id=${REQUEST_ID}" || true)"
  COMPLETED_LINE="$(printf '%s\n' "$LOG_OUTPUT" | grep "status=completed" | tail -1 || true)"
  FAILED_LINE="$(printf '%s\n' "$LOG_OUTPUT" | grep "status=failed" | tail -1 || true)"

  if [[ -n "$COMPLETED_LINE" ]]; then
    printf '%s\n' "$LOG_OUTPUT"
    "$STATUS_SCRIPT"
    exit 0
  fi

  if [[ -n "$FAILED_LINE" ]]; then
    printf '%s\n' "$LOG_OUTPUT"
    echo "FAIL: owner voice enrollment failed." >&2
    exit 1
  fi

  sleep 1
done

adb logcat -d -v time -s "$LOG_TAG" | grep "request_id=${REQUEST_ID}" || true
echo "FAIL: no completion status from JarvisDebugEnroll." >&2
exit 1

#!/usr/bin/env bash
set -euo pipefail

ACTIVITY="com.personal.jarvis/.debug.JarvisDebugActivationReplayActivity"
LOG_TAG="JarvisDebugReplay"
REQUEST_ID="$(date +%s)-$$"
WAIT_DEADLINE=30

adb shell am start -n "$ACTIVITY" --es request_id "$REQUEST_ID" >/dev/null

for ((elapsed = 0; elapsed < WAIT_DEADLINE; elapsed++)); do
  LOG_OUTPUT="$(adb logcat -d -v time -s "$LOG_TAG" | grep "request_id=${REQUEST_ID}" || true)"
  COMPLETED_LINE="$(printf '%s\n' "$LOG_OUTPUT" | grep "status=completed" | tail -1 || true)"
  FAILED_LINE="$(printf '%s\n' "$LOG_OUTPUT" | grep "status=failed" | tail -1 || true)"

  if [[ -n "$COMPLETED_LINE" ]]; then
    printf '%s\n' "$LOG_OUTPUT"
    exit 0
  fi

  if [[ -n "$FAILED_LINE" ]]; then
    printf '%s\n' "$LOG_OUTPUT"
    echo "FAIL: activation replay failed." >&2
    exit 1
  fi

  sleep 1
done

adb logcat -d -v time -s "$LOG_TAG" | grep "request_id=${REQUEST_ID}" || true
echo "FAIL: no completion status from JarvisDebugReplay." >&2
exit 1

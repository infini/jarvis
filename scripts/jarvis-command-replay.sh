#!/usr/bin/env bash
set -euo pipefail

ACTIVITY="com.personal.jarvis/.debug.JarvisDebugCommandReplayActivity"
LOG_TAG="JarvisDebugCommandReplay"
REQUEST_ID="command-replay-$(date +%s)-$$"
WAIT_DEADLINE="${JARVIS_COMMAND_REPLAY_WAIT_SECONDS:-15}"
LOG_FILE="${JARVIS_COMMAND_REPLAY_LOG_FILE:-/tmp/jarvis-command-replay-${REQUEST_ID}.log}"
PRINT_LINES="${JARVIS_COMMAND_REPLAY_PRINT_LINES:-80}"

adb logcat -c
adb shell am start -n "$ACTIVITY" --es request_id "$REQUEST_ID" >/dev/null

COMPLETED_LINE=""
for ((elapsed = 0; elapsed < WAIT_DEADLINE; elapsed++)); do
  COMPLETED_LINE="$(
    adb logcat -d -v time -s "$LOG_TAG" |
      grep "request_id=${REQUEST_ID}" |
      grep "status=completed\\|status=failed" |
      tail -1 || true
  )"
  if [[ -n "$COMPLETED_LINE" ]]; then
    break
  fi
  sleep 1
done

adb logcat -d -v time -s "$LOG_TAG" > "$LOG_FILE"
grep "request_id=${REQUEST_ID}" "$LOG_FILE" | tail -n "$PRINT_LINES" || true
echo "log_file=$LOG_FILE"

if [[ -z "$COMPLETED_LINE" ]]; then
  echo "FAIL: no command replay completion log found." >&2
  exit 1
fi

if [[ "$COMPLETED_LINE" == *"status=failed"* ]]; then
  echo "FAIL: command replay failed: $COMPLETED_LINE" >&2
  exit 1
fi

echo "PASS: command replay completed."

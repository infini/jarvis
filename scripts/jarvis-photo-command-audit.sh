#!/usr/bin/env bash
set -euo pipefail

ACTIVITY="com.personal.jarvis/.debug.JarvisDebugCommandWindowActivity"
REQUEST_ID="photo-audit-$(date +%s)-$$"
LOG_FILE="/tmp/jarvis-photo-command-audit-${REQUEST_ID}.log"
LOG_TAGS="JarvisLatency JarvisVoiceService JarvisAccessibility CameraAccessibility"

adb logcat -c
adb shell am start \
  -n "$ACTIVITY" \
  --el window_ms 1000 \
  --es request_id "$REQUEST_ID" \
  --es command take_photo >/dev/null

sleep 2
adb logcat -d -v time -s $LOG_TAGS > "$LOG_FILE"
adb shell am start \
  -n "$ACTIVITY" \
  --el window_ms 500 \
  --es command stop_service >/dev/null || true

COMMAND_LINE="$(grep "event=command_execute_start" "$LOG_FILE" | grep "command=take_photo" | tail -1 || true)"
ACCESS_LINE="$(grep "event=accessibility_command_received" "$LOG_FILE" | grep "command=take_photo" | tail -1 || true)"
SHUTTER_LINE="$(grep "Tapping fallback target=SHUTTER" "$LOG_FILE" | tail -1 || true)"
COMPLETE_LINE="$(grep "event=command_complete" "$LOG_FILE" | grep "keepWindow=true" | tail -1 || true)"
READY_LINE="$(grep "event=ready_for_speech" "$LOG_FILE" | tail -1 || true)"

cat "$LOG_FILE"
echo "log_file=$LOG_FILE"

if [[ -z "$COMMAND_LINE" ]]; then
  echo "FAIL: take_photo command execution was not observed." >&2
  exit 1
fi

if [[ -z "$ACCESS_LINE" ]]; then
  echo "FAIL: take_photo did not reach JarvisAccessibilityService." >&2
  exit 1
fi

if [[ -z "$SHUTTER_LINE" ]]; then
  echo "FAIL: shutter coordinate fast path was not observed." >&2
  exit 1
fi

if [[ -z "$COMPLETE_LINE" ]]; then
  echo "FAIL: take_photo did not complete while keeping the command window open." >&2
  exit 1
fi

if [[ -z "$READY_LINE" ]]; then
  echo "FAIL: next command listening readiness was not observed." >&2
  exit 1
fi

echo "PASS: take_photo reached accessibility, used shutter coordinate fast path, and reopened command listening."

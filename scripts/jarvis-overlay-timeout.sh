#!/usr/bin/env bash
set -euo pipefail

WINDOW_SECONDS="${1:-30}"
COMMAND="${2:-open_camera}"
ACTIVITY="com.personal.jarvis/.debug.JarvisDebugCommandWindowActivity"
LOG_TAGS="JarvisLatency JarvisVoiceService JarvisStateIndicator"
REQUEST_ID="$(date +%s)-$$"

case "$WINDOW_SECONDS" in
  ''|*[!0-9]*)
    echo "usage: $0 [window_seconds] [command]" >&2
    exit 2
    ;;
esac

WINDOW_MS=$((WINDOW_SECONDS * 1000))
WAIT_SECONDS=$((WINDOW_SECONDS + 8))
LOG_FILE="/tmp/jarvis-overlay-timeout-${REQUEST_ID}.log"

adb logcat -c
adb shell am start \
  -n "$ACTIVITY" \
  --el window_ms "$WINDOW_MS" \
  --es request_id "$REQUEST_ID" \
  --es command "$COMMAND" >/dev/null

sleep "$WAIT_SECONDS"
adb logcat -d -v time -s $LOG_TAGS > "$LOG_FILE"

OPEN_LINE="$(grep "debug_command_window_open" "$LOG_FILE" | grep "request_id=${REQUEST_ID}" | tail -1 || true)"
VISIBLE_LINE="$(grep "JarvisStateIndicator" "$LOG_FILE" | grep "overlay_visible" | tail -1 || true)"
CLOSE_LINE="$(grep -E "event=command_window_timeout|event=command_window_expired_after_speech_grace" "$LOG_FILE" | tail -1 || true)"
HIDDEN_AFTER_TIMEOUT="$(
  awk '
    /event=command_window_timeout|event=command_window_expired_after_speech_grace/ { seen_timeout=1; next }
    seen_timeout && /JarvisStateIndicator.*overlay_hidden/ { print }
  ' "$LOG_FILE" | tail -1 || true
)"
READY_AFTER_TIMEOUT="$(
  awk '
    /event=command_window_timeout|event=command_window_expired_after_speech_grace/ { seen_timeout=1; next }
    seen_timeout && /event=ready_for_speech/ { print }
  ' "$LOG_FILE" | tail -1 || true
)"

echo "log_file=$LOG_FILE"
[[ -n "$VISIBLE_LINE" ]] && echo "visible_line=$VISIBLE_LINE"
[[ -n "$HIDDEN_AFTER_TIMEOUT" ]] && echo "hidden_line=$HIDDEN_AFTER_TIMEOUT"

if [[ -z "$OPEN_LINE" ]]; then
  echo "FAIL: debug command window did not open." >&2
  exit 1
fi

if [[ -z "$VISIBLE_LINE" ]]; then
  echo "FAIL: JARVIS overlay was not shown during command window." >&2
  exit 1
fi

if [[ -z "$CLOSE_LINE" ]]; then
  echo "FAIL: command window close event was not observed." >&2
  exit 1
fi

if [[ -z "$HIDDEN_AFTER_TIMEOUT" ]]; then
  echo "FAIL: JARVIS overlay was not hidden after command window close." >&2
  exit 1
fi

if [[ -n "$READY_AFTER_TIMEOUT" ]]; then
  echo "FAIL: ready_for_speech appeared after command window close." >&2
  exit 1
fi

echo "PASS: JARVIS overlay was hidden after ${WINDOW_SECONDS}s timeout for command '$COMMAND'."

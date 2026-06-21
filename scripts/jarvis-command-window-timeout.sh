#!/usr/bin/env bash
set -euo pipefail

WINDOW_SECONDS="${1:-30}"
COMMAND="${2:-}"
ACTIVITY="com.personal.jarvis/.debug.JarvisDebugCommandWindowActivity"
LOG_TAG="JarvisLatency JarvisVoiceService"
REQUEST_ID="$(date +%s)-$$"
QUIET="${JARVIS_TIMEOUT_QUIET:-0}"

case "$WINDOW_SECONDS" in
  ''|*[!0-9]*)
    echo "usage: $0 [window_seconds]" >&2
    exit 2
    ;;
esac

WINDOW_MS=$((WINDOW_SECONDS * 1000))
WAIT_SECONDS=$((WINDOW_SECONDS + 8))
LOG_FILE="/tmp/jarvis-command-window-timeout-${REQUEST_ID}.log"
CLOSE_EVENT_REGEX="event=command_window_timeout|event=command_window_expired_after_speech_grace|event=command_window_expired_on_listen_timeout|event=command_window_expired_after_local_no_command|event=command_window_expired_after_speech_error|event=command_window_expired_after_final_no_command"

adb logcat -c
AM_ARGS=(
  -n "$ACTIVITY"
  --el window_ms "$WINDOW_MS"
  --es request_id "$REQUEST_ID"
)
if [[ -n "$COMMAND" ]]; then
  AM_ARGS+=(--es command "$COMMAND")
fi

adb shell am start "${AM_ARGS[@]}" >/dev/null

sleep "$WAIT_SECONDS"
adb logcat -d -v time -s $LOG_TAG > "$LOG_FILE"

OPEN_LINE="$(grep "debug_command_window_open" "$LOG_FILE" | grep "request_id=${REQUEST_ID}" | tail -1 || true)"
CLOSE_LINE="$(grep -E "$CLOSE_EVENT_REGEX" "$LOG_FILE" | tail -1 || true)"
COMMAND_LINE=""
if [[ -n "$COMMAND" ]]; then
  COMMAND_LINE="$(grep "event=command_complete" "$LOG_FILE" | grep "keepWindow=true" | tail -1 || true)"
fi
LAST_READY_AFTER_TIMEOUT="$(
  awk '
    /event=command_window_timeout|event=command_window_expired_after_speech_grace|event=command_window_expired_on_listen_timeout|event=command_window_expired_after_local_no_command|event=command_window_expired_after_speech_error|event=command_window_expired_after_final_no_command/ { seen_timeout=1; next }
    seen_timeout && /event=ready_for_speech/ { print }
  ' "$LOG_FILE" | tail -1 || true
)"

if [[ "$QUIET" != "1" ]]; then
  cat "$LOG_FILE"
fi
echo "log_file=$LOG_FILE"

if [[ -z "$OPEN_LINE" ]]; then
  echo "FAIL: debug command window did not open." >&2
  exit 1
fi

if [[ -z "$CLOSE_LINE" ]]; then
  echo "FAIL: command window close event was not observed." >&2
  exit 1
fi

if [[ -n "$COMMAND" && -z "$COMMAND_LINE" ]]; then
  echo "FAIL: debug command did not complete with keepWindow=true." >&2
  exit 1
fi

if [[ -n "$LAST_READY_AFTER_TIMEOUT" ]]; then
  echo "FAIL: ready_for_speech appeared after command window close." >&2
  exit 1
fi

if [[ -n "$COMMAND" ]]; then
  echo "PASS: command '$COMMAND' kept the window open, then returned to idle after ${WINDOW_SECONDS}s without restarting command STT."
else
  echo "PASS: command window returned to idle after ${WINDOW_SECONDS}s without restarting command STT."
fi

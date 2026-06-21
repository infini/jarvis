#!/usr/bin/env bash
set -euo pipefail

WINDOW_SECONDS="${1:-30}"
ACTIVITY="com.personal.jarvis/.debug.JarvisDebugCommandWindowActivity"
LOG_TAGS="JarvisLatency JarvisVoiceService JarvisFeedback JarvisStateIndicator"
REQUEST_ID="$(date +%s)-$$"

case "$WINDOW_SECONDS" in
  ''|*[!0-9]*)
    echo "usage: $0 [window_seconds]" >&2
    exit 2
    ;;
esac

WINDOW_MS=$((WINDOW_SECONDS * 1000))
WAIT_SECONDS=$((WINDOW_SECONDS + 8))
LOG_FILE="/tmp/jarvis-ready-feedback-once-${REQUEST_ID}.log"
CLOSE_EVENT_REGEX="event=command_window_timeout|event=command_window_expired_after_speech_grace|event=command_window_expired_on_listen_timeout|event=command_window_expired_after_local_no_command|event=command_window_expired_after_speech_error|event=command_window_expired_after_final_no_command"

adb logcat -c
adb shell am start \
  -n "$ACTIVITY" \
  --el window_ms "$WINDOW_MS" \
  --es request_id "$REQUEST_ID" >/dev/null

sleep "$WAIT_SECONDS"
adb logcat -d -v time -s $LOG_TAGS > "$LOG_FILE"

READY_COUNT="$(grep -c "event=ready_for_speech" "$LOG_FILE" || true)"
READY_FEEDBACK_COUNT="$(grep -c "feedback=command_ready" "$LOG_FILE" || true)"
CLOSE_LINE="$(grep -E "$CLOSE_EVENT_REGEX" "$LOG_FILE" | tail -1 || true)"
READY_AFTER_CLOSE="$(
  awk '
    /event=command_window_timeout|event=command_window_expired_after_speech_grace|event=command_window_expired_on_listen_timeout|event=command_window_expired_after_local_no_command|event=command_window_expired_after_speech_error|event=command_window_expired_after_final_no_command/ { seen_close=1; next }
    seen_close && /event=ready_for_speech/ { print }
  ' "$LOG_FILE" | tail -1 || true
)"

echo "log_file=$LOG_FILE"
echo "ready_for_speech=$READY_COUNT command_ready_feedback=$READY_FEEDBACK_COUNT"

if [[ -z "$CLOSE_LINE" ]]; then
  echo "FAIL: command window close event was not observed." >&2
  exit 1
fi

if [[ "$READY_COUNT" -lt 1 ]]; then
  echo "FAIL: ready_for_speech was not observed." >&2
  exit 1
fi

if [[ "$READY_FEEDBACK_COUNT" -ne 1 ]]; then
  echo "FAIL: expected exactly one command_ready feedback, observed $READY_FEEDBACK_COUNT." >&2
  exit 1
fi

if [[ -n "$READY_AFTER_CLOSE" ]]; then
  echo "FAIL: ready_for_speech appeared after command window close." >&2
  exit 1
fi

echo "PASS: command ready feedback played once while command STT retried until ${WINDOW_SECONDS}s close."

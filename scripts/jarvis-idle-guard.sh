#!/usr/bin/env bash
set -euo pipefail

DURATION_SECONDS="${1:-20}"
START_ACTIVITY="com.personal.jarvis/.debug.JarvisDebugStartActivity"
LOG_TAGS="JarvisLatency JarvisVoiceService OwnerVoiceGate"
REQUEST_ID="$(date +%s)-$$"

case "$DURATION_SECONDS" in
  ''|*[!0-9]*)
    echo "usage: $0 [duration_seconds]" >&2
    exit 2
    ;;
esac

LOG_FILE="/tmp/jarvis-idle-guard-${REQUEST_ID}.log"

adb shell input keyevent HOME >/dev/null
adb logcat -c
adb shell am start \
  -n "$START_ACTIVITY" \
  --es request_id "$REQUEST_ID" >/dev/null

sleep "$DURATION_SECONDS"
adb logcat -d -v time -s $LOG_TAGS > "$LOG_FILE"

READY_LINE="$(grep "event=ready_for_speech" "$LOG_FILE" | tail -1 || true)"
LISTEN_LINE="$(grep "event=listen_start" "$LOG_FILE" | tail -1 || true)"
ACTIVATION_LINE="$(grep -E "event=owner_audio_activation( |$)" "$LOG_FILE" | tail -1 || true)"
COMMAND_READY_LINE="$(grep "event=activation_complete\\|event=partial_activation_complete" "$LOG_FILE" | tail -1 || true)"
ACTIVATION_ASR_COUNT="$(grep -c "event=activation_asr_complete" "$LOG_FILE" || true)"
ACTIVATION_REJECTED_SEGMENT_COUNT="$(grep -c "event=activation_asr_rejected_segment" "$LOG_FILE" || true)"
ACTIVATION_OWNER_COUNT="$(grep -c "event=activation_owner_verified" "$LOG_FILE" || true)"
ACTIVATION_MISSING_COUNT="$(grep -c "event=activation_phrase_missing" "$LOG_FILE" || true)"
ACTIVATION_OWNER_REJECTED_COUNT="$(grep -c "event=activation_owner_rejected" "$LOG_FILE" || true)"

echo "log_file=$LOG_FILE"
echo "summary activation_asr_complete=$ACTIVATION_ASR_COUNT activation_asr_rejected_segment=$ACTIVATION_REJECTED_SEGMENT_COUNT activation_owner_verified=$ACTIVATION_OWNER_COUNT activation_phrase_missing=$ACTIVATION_MISSING_COUNT activation_owner_rejected=$ACTIVATION_OWNER_REJECTED_COUNT"

if [[ -n "$ACTIVATION_LINE" || -n "$COMMAND_READY_LINE" || -n "$READY_LINE" || -n "$LISTEN_LINE" ]]; then
  echo "FAIL: idle guard observed accepted activation or command STT." >&2
  [[ -n "$ACTIVATION_LINE" ]] && echo "activation_line=$ACTIVATION_LINE" >&2
  [[ -n "$COMMAND_READY_LINE" ]] && echo "command_ready_line=$COMMAND_READY_LINE" >&2
  [[ -n "$LISTEN_LINE" ]] && echo "listen_line=$LISTEN_LINE" >&2
  [[ -n "$READY_LINE" ]] && echo "ready_line=$READY_LINE" >&2
  grep -E "event=(owner_audio_activation( |$)|activation_complete|partial_activation_complete|listen_start|ready_for_speech)" "$LOG_FILE" >&2 || true
  exit 1
fi

echo "PASS: idle stayed out of command STT for ${DURATION_SECONDS}s."

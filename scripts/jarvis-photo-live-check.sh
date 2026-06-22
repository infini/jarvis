#!/usr/bin/env bash
set -euo pipefail

DURATION_SECONDS="${1:-12}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_SCRIPT="$SCRIPT_DIR/jarvis-latency-report.sh"
ACTIVITY="com.personal.jarvis/.debug.JarvisDebugCommandWindowActivity"
REQUEST_ID="photo-live-$(date +%s)-$$"
LOG_FILE="${JARVIS_PHOTO_LIVE_LOG_FILE:-/tmp/jarvis-photo-live-${REQUEST_ID}.log}"
DIAGNOSTIC_LOG_FILE="${LOG_FILE}.diagnostic"
LOG_TAGS="JarvisLatency JarvisVoiceService JarvisAccessibility CameraAccessibility"
OPEN_CAMERA="${JARVIS_PHOTO_LIVE_OPEN_CAMERA:-1}"
INJECT_COMMAND="${JARVIS_PHOTO_LIVE_INJECT_COMMAND:-}"
MAX_PARSED_MS="${JARVIS_PHOTO_MAX_PARSED_MS:-2500}"
MAX_SPEECH_PARSE_MS="${JARVIS_PHOTO_MAX_SPEECH_PARSE_MS:-1500}"
MAX_ACCESS_MS="${JARVIS_PHOTO_MAX_ACCESS_MS:-3000}"
MAX_COMMAND_ACCESS_MS="${JARVIS_PHOTO_MAX_COMMAND_ACCESS_MS:-800}"

case "$DURATION_SECONDS" in
  ''|*[!0-9]*)
    echo "usage: $0 [duration_seconds]" >&2
    exit 2
    ;;
esac

WINDOW_MS=$(((DURATION_SECONDS + 3) * 1000))

if [[ "$OPEN_CAMERA" == "1" ]]; then
  adb shell am start -a android.media.action.STILL_IMAGE_CAMERA >/dev/null 2>&1 || \
    adb shell monkey -p com.android.camera 1 >/dev/null 2>&1 || true
  sleep 1
fi

adb logcat -c
AM_ARGS=(
  -n "$ACTIVITY"
  --el window_ms "$WINDOW_MS"
  --es request_id "$REQUEST_ID"
)
if [[ -n "$INJECT_COMMAND" ]]; then
  AM_ARGS+=(--es command "$INJECT_COMMAND")
fi

adb shell am start "${AM_ARGS[@]}" >/dev/null

if [[ -z "$INJECT_COMMAND" ]]; then
  echo "Speak now: say '자비스 사진 찍어' once after the JARVIS LISTENING indicator or ready vibration."
fi
sleep "$DURATION_SECONDS"

adb logcat -d -v time -s $LOG_TAGS > "$LOG_FILE"
adb logcat -d -v time -s JarvisLatency JarvisVoiceService OwnerVoiceGate > "$DIAGNOSTIC_LOG_FILE" || true
adb shell am start \
  -n "$ACTIVITY" \
  --el window_ms 500 \
  --es command stop_service >/dev/null || true

REPORT_OUTPUT="$("$REPORT_SCRIPT" "$LOG_FILE")"
printf '%s\n' "$REPORT_OUTPUT"

count_event() {
  local event="$1"
  grep -Ec "event=${event}([[:space:]]|$)" "$LOG_FILE" || true
}

one_line() {
  tr '\n' ' ' | sed 's/[[:space:]]\+/ /g; s/^ //; s/ $//'
}

result_line() {
  local status="$1"
  local failure_type="$2"
  local parsed_source="$3"
  local parsed_ms="$4"
  local speech_parse_ms="$5"
  local access_ms="$6"
  local speech_access_ms="$7"
  local command_access_ms="$8"
  local stt_text="$9"
  printf 'result status=%s failure_type=%s parsed_source=%s parsed_ms=%s speech_parse_ms=%s access_ms=%s speech_access_ms=%s command_access_ms=%s stt_text=%s\n' \
    "$status" \
    "$failure_type" \
    "$parsed_source" \
    "$parsed_ms" \
    "$speech_parse_ms" \
    "$access_ms" \
    "$speech_access_ms" \
    "$command_access_ms" \
    "$stt_text"
}

report_field() {
  local line="$1"
  local key="$2"
  awk -v key="$key" '
    {
      marker = key "="
      start = index($0, marker)
      if (start == 0) exit
      rest = substr($0, start + length(marker))
      split(rest, parts, " ")
      value = parts[1]
      sub(/ms$/, "", value)
      print value
    }
  ' <<< "$line"
}

READY_COUNT="$(count_event ready_for_speech)"
SPEECH_BEGIN_COUNT="$(count_event speech_begin)"
PARTIAL_COUNT="$(count_event partial_results)"
FINAL_COUNT="$(count_event final_results)"
if [[ -n "$INJECT_COMMAND" ]]; then
  PHOTO_PARSED_LINE="$(grep "event=command_injected" "$LOG_FILE" | grep "command=take_photo" | tail -1 || true)"
else
  PHOTO_PARSED_LINE="$(grep "event=command_parsed" "$LOG_FILE" | grep "command=take_photo" | tail -1 || true)"
fi
ANY_COMMAND_LINE="$(grep "event=command_parsed" "$LOG_FILE" | tail -1 || true)"
ACCESS_LINE="$(grep "event=accessibility_command_received" "$LOG_FILE" | grep "command=take_photo" | tail -1 || true)"
SHUTTER_LINE="$(grep "Tapping fallback target=SHUTTER" "$LOG_FILE" | tail -1 || true)"
COMPLETE_LINE="$(grep "event=command_complete" "$LOG_FILE" | grep "keepWindow=true" | tail -1 || true)"
PHOTO_REPORT_LINE="$(printf '%s\n' "$REPORT_OUTPUT" | awk '/^trace=/ && /command=take_photo/ && /status=command_complete/ { line=$0 } END { print line }')"
PARTIAL_TEXT="$(grep "event=partial_results" "$LOG_FILE" | tail -3 || true)"
FINAL_TEXT="$(grep "event=final_results" "$LOG_FILE" | tail -3 || true)"
STT_TEXT_SAMPLE="$(printf '%s\n%s\n' "$PARTIAL_TEXT" "$FINAL_TEXT" | one_line)"
if [[ -z "$STT_TEXT_SAMPLE" ]]; then
  STT_TEXT_SAMPLE="-"
fi
PARSED_SOURCE="-"
if [[ "$PHOTO_PARSED_LINE" == *"source=partial"* ]]; then
  PARSED_SOURCE="partial"
elif [[ "$PHOTO_PARSED_LINE" == *"source=final"* ]]; then
  PARSED_SOURCE="final"
elif [[ "$PHOTO_PARSED_LINE" == *"source=local"* ]]; then
  PARSED_SOURCE="local"
elif [[ "$PHOTO_PARSED_LINE" == *"event=command_injected"* ]]; then
  PARSED_SOURCE="injected"
fi
PARSED_MS="$(report_field "$PHOTO_REPORT_LINE" parsed)"
SPEECH_PARSE_MS="$(report_field "$PHOTO_REPORT_LINE" speech_parse)"
ACCESS_MS="$(report_field "$PHOTO_REPORT_LINE" access)"
SPEECH_ACCESS_MS="$(report_field "$PHOTO_REPORT_LINE" speech_access)"
COMMAND_ACCESS_MS="$(report_field "$PHOTO_REPORT_LINE" command_access)"
PARSED_MS="${PARSED_MS:-0}"
SPEECH_PARSE_MS="${SPEECH_PARSE_MS:-0}"
ACCESS_MS="${ACCESS_MS:-0}"
SPEECH_ACCESS_MS="${SPEECH_ACCESS_MS:-0}"
COMMAND_ACCESS_MS="${COMMAND_ACCESS_MS:-0}"

echo "log_file=$LOG_FILE"
echo "diagnostic_log_file=$DIAGNOSTIC_LOG_FILE"
echo "events ready_for_speech=$READY_COUNT speech_begin=$SPEECH_BEGIN_COUNT partial_results=$PARTIAL_COUNT final_results=$FINAL_COUNT"

if [[ -z "$PHOTO_PARSED_LINE" ]]; then
  FAILURE_TYPE="no_take_photo_parse"
  if [[ "$READY_COUNT" == "0" ]]; then
    FAILURE_TYPE="no_ready"
  elif [[ "$SPEECH_BEGIN_COUNT" == "0" && "$PARTIAL_COUNT" == "0" && "$FINAL_COUNT" == "0" ]]; then
    FAILURE_TYPE="no_speech"
  elif [[ -n "$ANY_COMMAND_LINE" ]]; then
    FAILURE_TYPE="wrong_command"
  fi
  result_line "FAIL" "$FAILURE_TYPE" "-" "$PARSED_MS" "$SPEECH_PARSE_MS" "$ACCESS_MS" "$SPEECH_ACCESS_MS" "$COMMAND_ACCESS_MS" "$STT_TEXT_SAMPLE"
  echo "FAIL: '자비스 사진 찍어' was not parsed as take_photo." >&2
  if [[ -n "$ANY_COMMAND_LINE" ]]; then
    echo "Last parsed command: $ANY_COMMAND_LINE" >&2
  fi
  if [[ "$READY_COUNT" == "0" ]]; then
    echo "Jarvis command window did not reach ready_for_speech. Check microphone permission and service startup." >&2
  elif [[ "$SPEECH_BEGIN_COUNT" == "0" && "$PARTIAL_COUNT" == "0" && "$FINAL_COUNT" == "0" ]]; then
    echo "Jarvis was ready, but Android STT did not detect speech. Say the phrase after the ready indicator/vibration." >&2
  else
    echo "Speech was detected, but the phrase did not map to take_photo. Recent STT text:" >&2
    printf '%s\n%s\n' "$PARTIAL_TEXT" "$FINAL_TEXT" >&2
  fi
  exit 1
fi

if [[ -z "$ACCESS_LINE" ]]; then
  result_line "FAIL" "no_accessibility" "$PARSED_SOURCE" "$PARSED_MS" "$SPEECH_PARSE_MS" "$ACCESS_MS" "$SPEECH_ACCESS_MS" "$COMMAND_ACCESS_MS" "$STT_TEXT_SAMPLE"
  echo "FAIL: take_photo parsed, but did not reach JarvisAccessibilityService." >&2
  exit 1
fi

if [[ -z "$SHUTTER_LINE" ]]; then
  result_line "FAIL" "no_shutter_fast_path" "$PARSED_SOURCE" "$PARSED_MS" "$SPEECH_PARSE_MS" "$ACCESS_MS" "$SPEECH_ACCESS_MS" "$COMMAND_ACCESS_MS" "$STT_TEXT_SAMPLE"
  echo "FAIL: take_photo reached accessibility, but shutter fast path was not observed." >&2
  exit 1
fi

if [[ -z "$COMPLETE_LINE" ]]; then
  result_line "FAIL" "no_command_complete" "$PARSED_SOURCE" "$PARSED_MS" "$SPEECH_PARSE_MS" "$ACCESS_MS" "$SPEECH_ACCESS_MS" "$COMMAND_ACCESS_MS" "$STT_TEXT_SAMPLE"
  echo "FAIL: take_photo did not complete while keeping the command window open." >&2
  exit 1
fi

if [[ "$SPEECH_PARSE_MS" -gt 0 && "$SPEECH_PARSE_MS" -gt "$MAX_SPEECH_PARSE_MS" ]]; then
  result_line "FAIL" "slow_speech_parse" "$PARSED_SOURCE" "$PARSED_MS" "$SPEECH_PARSE_MS" "$ACCESS_MS" "$SPEECH_ACCESS_MS" "$COMMAND_ACCESS_MS" "$STT_TEXT_SAMPLE"
  echo "FAIL: take_photo parsed too slowly after speech_begin: ${SPEECH_PARSE_MS}ms > ${MAX_SPEECH_PARSE_MS}ms." >&2
  exit 1
fi

if [[ "$SPEECH_PARSE_MS" -eq 0 && "$PARSED_MS" -gt "$MAX_PARSED_MS" ]]; then
  result_line "FAIL" "slow_parse" "$PARSED_SOURCE" "$PARSED_MS" "$SPEECH_PARSE_MS" "$ACCESS_MS" "$SPEECH_ACCESS_MS" "$COMMAND_ACCESS_MS" "$STT_TEXT_SAMPLE"
  echo "FAIL: take_photo parsed too slowly: ${PARSED_MS}ms > ${MAX_PARSED_MS}ms." >&2
  exit 1
fi

if [[ "$SPEECH_ACCESS_MS" -gt 0 && "$SPEECH_ACCESS_MS" -gt "$MAX_ACCESS_MS" ]]; then
  result_line "FAIL" "slow_speech_access" "$PARSED_SOURCE" "$PARSED_MS" "$SPEECH_PARSE_MS" "$ACCESS_MS" "$SPEECH_ACCESS_MS" "$COMMAND_ACCESS_MS" "$STT_TEXT_SAMPLE"
  echo "FAIL: take_photo reached accessibility too slowly after speech_begin: ${SPEECH_ACCESS_MS}ms > ${MAX_ACCESS_MS}ms." >&2
  exit 1
fi

if [[ "$COMMAND_ACCESS_MS" -gt "$MAX_COMMAND_ACCESS_MS" ]]; then
  result_line "FAIL" "slow_command_access" "$PARSED_SOURCE" "$PARSED_MS" "$SPEECH_PARSE_MS" "$ACCESS_MS" "$SPEECH_ACCESS_MS" "$COMMAND_ACCESS_MS" "$STT_TEXT_SAMPLE"
  echo "FAIL: take_photo reached accessibility too slowly after parsing: ${COMMAND_ACCESS_MS}ms > ${MAX_COMMAND_ACCESS_MS}ms." >&2
  exit 1
fi

result_line "PASS" "none" "$PARSED_SOURCE" "$PARSED_MS" "$SPEECH_PARSE_MS" "$ACCESS_MS" "$SPEECH_ACCESS_MS" "$COMMAND_ACCESS_MS" "$STT_TEXT_SAMPLE"
if [[ -n "$INJECT_COMMAND" ]]; then
  echo "PASS: injected take_photo reached accessibility, used shutter fast path, and reopened command listening."
else
  echo "PASS: '자비스 사진 찍어' parsed as take_photo, reached accessibility, used shutter fast path, and reopened command listening."
fi

#!/usr/bin/env bash
set -euo pipefail

DURATION_SECONDS="${1:-45}"
LOG_FILE="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_SCRIPT="$SCRIPT_DIR/jarvis-latency-report.sh"
TEMP_LOG_FILE=""
DIAGNOSTIC_LOG_FILE=""
MAX_PARSED_MS="${JARVIS_MAX_PARSED_MS:-2500}"
MAX_SPEECH_PARSED_MS="${JARVIS_MAX_SPEECH_PARSED_MS:-2500}"
MAX_ACCESS_MS="${JARVIS_MAX_ACCESS_MS:-4000}"
MAX_COMMAND_ACCESS_MS="${JARVIS_MAX_COMMAND_ACCESS_MS:-1200}"
START_DEBUG_ACTIVITY="${JARVIS_START_DEBUG_ACTIVITY:-1}"
SKIP_PROFILE_CHECK="${JARVIS_SKIP_PROFILE_CHECK:-0}"

case "$DURATION_SECONDS" in
  ''|*[!0-9]*)
    echo "usage: $0 [duration_seconds] [optional_log_file]" >&2
    exit 2
    ;;
esac

case "$MAX_PARSED_MS" in
  ''|*[!0-9]*)
    echo "JARVIS_MAX_PARSED_MS must be an integer" >&2
    exit 2
    ;;
esac

case "$MAX_ACCESS_MS" in
  ''|*[!0-9]*)
    echo "JARVIS_MAX_ACCESS_MS must be an integer" >&2
    exit 2
    ;;
esac

case "$MAX_COMMAND_ACCESS_MS" in
  ''|*[!0-9]*)
    echo "JARVIS_MAX_COMMAND_ACCESS_MS must be an integer" >&2
    exit 2
    ;;
esac

case "$MAX_SPEECH_PARSED_MS" in
  ''|*[!0-9]*)
    echo "JARVIS_MAX_SPEECH_PARSED_MS must be an integer" >&2
    exit 2
    ;;
esac

if [[ "$SKIP_PROFILE_CHECK" != "1" ]]; then
  "$SCRIPT_DIR/jarvis-profile-status.sh"
fi

echo "Clearing Jarvis logcat and recording for ${DURATION_SECONDS}s."
adb logcat -c
if [[ "$START_DEBUG_ACTIVITY" == "1" ]]; then
  adb shell am start \
    -n com.personal.jarvis/.debug.JarvisDebugStartActivity \
    --ez reset_voice_service true >/dev/null 2>&1 || true
  sleep 1
fi
echo "Speak now: say '자비스 깨어나'. When the green JARVIS indicator appears or the ready tone/vibration plays, say one command such as '카메라 실행', then wait for the handled tone before the next command: '후면', '전면', '찍어', '종료'."
sleep "$DURATION_SECONDS"

if [[ -z "$LOG_FILE" ]]; then
  TEMP_LOG_FILE="$(mktemp -t jarvis-latency.XXXXXX.log)"
  LOG_FILE="$TEMP_LOG_FILE"
  trap '[[ -n "$TEMP_LOG_FILE" ]] && rm -f "$TEMP_LOG_FILE"' EXIT
fi
DIAGNOSTIC_LOG_FILE="${JARVIS_DIAGNOSTIC_LOG_FILE:-${LOG_FILE}.diagnostic}"

adb logcat -d -v time -s JarvisLatency > "$LOG_FILE"
adb logcat -d -v time -s JarvisLatency OwnerVoiceGate JarvisVoiceService > "$DIAGNOSTIC_LOG_FILE" || true
REPORT_OUTPUT="$("$REPORT_SCRIPT" "$LOG_FILE")"
printf '%s\n' "$REPORT_OUTPUT"

count_event() {
  local event="$1"
  grep -Ec "event=${event}([[:space:]]|$)" "$LOG_FILE" || true
}

ACTIVATION_ASR_COMPLETE_COUNT="$(count_event activation_asr_complete)"
ACTIVATION_REJECTED_SEGMENT_COUNT="$(count_event activation_asr_rejected_segment)"
ACTIVATION_OWNER_VERIFIED_COUNT="$(count_event activation_owner_verified)"
READY_FOR_SPEECH_COUNT="$(count_event ready_for_speech)"
SPEECH_BEGIN_COUNT="$(count_event speech_begin)"
PARTIAL_RESULTS_COUNT="$(count_event partial_results)"
COMMAND_PARSED_COUNT="$(count_event command_parsed)"
ACTIVATION_PARTIAL_COUNT="$(count_event activation_partial)"
OWNER_AUDIO_ACTIVATION_COUNT="$(count_event owner_audio_activation)"
FALLBACK_TO_LOCAL_COUNT="$(count_event fallback_to_local)"
OWNER_REJECTED_COUNT="$(grep -c "Owner voice rejected" "$DIAGNOSTIC_LOG_FILE" || true)"
OWNER_ACCEPTED_COUNT="$(grep -c "Owner voice accepted" "$DIAGNOSTIC_LOG_FILE" || true)"
OWNER_SUPPRESSED_COUNT="$(grep -c "Owner voice suppressed" "$DIAGNOSTIC_LOG_FILE" || true)"

COMMAND_COMPLETE_LINES="$(
  printf '%s\n' "$REPORT_OUTPUT" | awk '/^trace=/ && /status=command_complete/'
)"
if [[ -z "$COMMAND_COMPLETE_LINES" ]]; then
  echo "FAIL: no command_complete trace found in $LOG_FILE." >&2
  echo "Diagnostic log: $DIAGNOSTIC_LOG_FILE" >&2
  echo "Diagnostics: activation_asr_complete=${ACTIVATION_ASR_COMPLETE_COUNT}, activation_asr_rejected_segment=${ACTIVATION_REJECTED_SEGMENT_COUNT}, activation_owner_verified=${ACTIVATION_OWNER_VERIFIED_COUNT}, owner_audio_activation=${OWNER_AUDIO_ACTIVATION_COUNT}, ready_for_speech=${READY_FOR_SPEECH_COUNT}, speech_begin=${SPEECH_BEGIN_COUNT}, partial_results=${PARTIAL_RESULTS_COUNT}, command_parsed=${COMMAND_PARSED_COUNT}, activation_partial=${ACTIVATION_PARTIAL_COUNT}, fallback_to_local=${FALLBACK_TO_LOCAL_COUNT}" >&2
  echo "OwnerVoiceGate: accepted=${OWNER_ACCEPTED_COUNT}, rejected=${OWNER_REJECTED_COUNT}, suppressed=${OWNER_SUPPRESSED_COUNT}" >&2
  if [[ "$ACTIVATION_ASR_COMPLETE_COUNT" == "0" && "$ACTIVATION_REJECTED_SEGMENT_COUNT" == "0" ]]; then
    echo "Local activation ASR did not produce any segment. Check microphone permission, foreground service state, and local ASR assets." >&2
  elif [[ "$OWNER_AUDIO_ACTIVATION_COUNT" == "0" ]]; then
    echo "Activation did not open a command window. Check activation_asr_complete or activation_asr_rejected_segment text and activation_owner_verified owner score in the diagnostic log." >&2
  elif [[ "$SPEECH_BEGIN_COUNT" == "0" && "$PARTIAL_RESULTS_COUNT" == "0" ]]; then
    echo "Jarvis opened a command window, but Android STT did not detect a spoken command. Say the command after the green JARVIS indicator appears or the ready tone/vibration plays." >&2
  elif [[ "$COMMAND_PARSED_COUNT" == "0" ]]; then
    echo "Speech was detected, but no supported command was parsed. Try a shorter command such as '카메라 실행', '후면', '전면', '찍어', or '종료'." >&2
  fi
  exit 1
fi

SLOW_LINES="$(
  printf '%s\n' "$COMMAND_COMPLETE_LINES" | awk \
    -v maxParsed="$MAX_PARSED_MS" \
    -v maxSpeechParsed="$MAX_SPEECH_PARSED_MS" \
    -v maxAccess="$MAX_ACCESS_MS" \
    -v maxCommandAccess="$MAX_COMMAND_ACCESS_MS" '
      function fieldValue(key, i, pair) {
        for (i = 1; i <= NF; i++) {
          split($i, pair, "=")
          if (pair[1] == key) return pair[2] + 0
        }
        return 0
      }
      {
        parsed = fieldValue("parsed")
        speechParsed = fieldValue("speech_parse")
        access = fieldValue("access")
        speechAccess = fieldValue("speech_access")
        commandAccess = fieldValue("command_access")
        parseBudget = speechParsed > 0 ? maxSpeechParsed : maxParsed
        parseLatency = speechParsed > 0 ? speechParsed : parsed
        accessLatency = speechAccess > 0 ? speechAccess : access
        slow = parseLatency <= 0
        slow = slow || parseLatency > parseBudget
        slow = slow || (accessLatency > 0 && accessLatency > maxAccess)
        slow = slow || (commandAccess > 0 && commandAccess > maxCommandAccess)
        if (slow) print
      }
    '
)"

if [[ -n "$SLOW_LINES" ]]; then
  echo "FAIL: command_complete trace exceeded latency threshold." >&2
  echo "Thresholds: speech_parse<=${MAX_SPEECH_PARSED_MS}ms when speech_begin is present; otherwise parsed<=${MAX_PARSED_MS}ms. speech_access<=${MAX_ACCESS_MS}ms and command_access<=${MAX_COMMAND_ACCESS_MS}ms when access is present." >&2
  printf '%s\n' "$SLOW_LINES" >&2
  exit 1
fi

echo "PASS: command_complete trace found within latency thresholds."

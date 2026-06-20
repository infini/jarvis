#!/usr/bin/env bash
set -euo pipefail

DURATION_SECONDS="${1:-45}"
LOG_FILE="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_SCRIPT="$SCRIPT_DIR/jarvis-latency-report.sh"
TEMP_LOG_FILE=""
MAX_PARSED_MS="${JARVIS_MAX_PARSED_MS:-2500}"
MAX_SPEECH_PARSED_MS="${JARVIS_MAX_SPEECH_PARSED_MS:-2500}"
MAX_ACCESS_MS="${JARVIS_MAX_ACCESS_MS:-4000}"

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

case "$MAX_SPEECH_PARSED_MS" in
  ''|*[!0-9]*)
    echo "JARVIS_MAX_SPEECH_PARSED_MS must be an integer" >&2
    exit 2
    ;;
esac

echo "Clearing Jarvis logcat and recording for ${DURATION_SECONDS}s."
adb logcat -c
echo "Speak now: say '자비스', wait for the ready tone, say one command such as '카메라 실행', then wait for the handled tone before the next command: '후면', '전면', '찍어', '종료'."
sleep "$DURATION_SECONDS"

if [[ -z "$LOG_FILE" ]]; then
  TEMP_LOG_FILE="$(mktemp -t jarvis-latency.XXXXXX.log)"
  LOG_FILE="$TEMP_LOG_FILE"
  trap '[[ -n "$TEMP_LOG_FILE" ]] && rm -f "$TEMP_LOG_FILE"' EXIT
fi

adb logcat -d -v time -s JarvisLatency > "$LOG_FILE"
REPORT_OUTPUT="$("$REPORT_SCRIPT" "$LOG_FILE")"
printf '%s\n' "$REPORT_OUTPUT"

COMMAND_COMPLETE_LINES="$(
  printf '%s\n' "$REPORT_OUTPUT" | awk '/^trace=/ && /status=command_complete/'
)"
if [[ -z "$COMMAND_COMPLETE_LINES" ]]; then
  echo "FAIL: no command_complete trace found. Speak the command cycle during the capture window." >&2
  exit 1
fi

SLOW_LINES="$(
  printf '%s\n' "$COMMAND_COMPLETE_LINES" | awk \
    -v maxParsed="$MAX_PARSED_MS" \
    -v maxSpeechParsed="$MAX_SPEECH_PARSED_MS" \
    -v maxAccess="$MAX_ACCESS_MS" '
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
        parseBudget = speechParsed > 0 ? maxSpeechParsed : maxParsed
        parseLatency = speechParsed > 0 ? speechParsed : parsed
        if (parseLatency <= 0 || parseLatency > parseBudget || (access > 0 && access > maxAccess)) print
      }
    '
)"

if [[ -n "$SLOW_LINES" ]]; then
  echo "FAIL: command_complete trace exceeded latency threshold." >&2
  echo "Thresholds: speech_parse<=${MAX_SPEECH_PARSED_MS}ms when speech_begin is present; otherwise parsed<=${MAX_PARSED_MS}ms. access<=${MAX_ACCESS_MS}ms when access is present." >&2
  printf '%s\n' "$SLOW_LINES" >&2
  exit 1
fi

echo "PASS: command_complete trace found within latency thresholds."

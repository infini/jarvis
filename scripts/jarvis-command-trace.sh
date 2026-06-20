#!/usr/bin/env bash
set -euo pipefail

DURATION_SECONDS="${1:-45}"
LOG_FILE="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_SCRIPT="$SCRIPT_DIR/jarvis-latency-report.sh"
TEMP_LOG_FILE=""

case "$DURATION_SECONDS" in
  ''|*[!0-9]*)
    echo "usage: $0 [duration_seconds] [optional_log_file]" >&2
    exit 2
    ;;
esac

echo "Clearing Jarvis logcat and recording for ${DURATION_SECONDS}s."
adb logcat -c
echo "Speak the test cycle now: 자비스 카메라 실행, 후면, 전면, 찍어, 종료"
sleep "$DURATION_SECONDS"

if [[ -z "$LOG_FILE" ]]; then
  TEMP_LOG_FILE="$(mktemp -t jarvis-latency.XXXXXX.log)"
  LOG_FILE="$TEMP_LOG_FILE"
  trap '[[ -n "$TEMP_LOG_FILE" ]] && rm -f "$TEMP_LOG_FILE"' EXIT
fi

adb logcat -d -v time -s JarvisLatency > "$LOG_FILE"
REPORT_OUTPUT="$("$REPORT_SCRIPT" "$LOG_FILE")"
printf '%s\n' "$REPORT_OUTPUT"

if printf '%s\n' "$REPORT_OUTPUT" | grep -q 'status=command_complete'; then
  echo "PASS: command_complete trace found."
  exit 0
fi

echo "FAIL: no command_complete trace found. Speak the command cycle during the capture window." >&2
exit 1

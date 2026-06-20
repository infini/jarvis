#!/usr/bin/env bash
set -euo pipefail

DURATION_SECONDS="${1:-45}"
LOG_FILE="${2:-}"

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

if [[ -n "$LOG_FILE" ]]; then
  adb logcat -d -v time -s JarvisLatency > "$LOG_FILE"
  "$(dirname "$0")/jarvis-latency-report.sh" "$LOG_FILE"
else
  "$(dirname "$0")/jarvis-latency-report.sh"
fi

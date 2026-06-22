#!/usr/bin/env bash
set -euo pipefail

TRIALS="${1:-5}"
DURATION_SECONDS="${2:-12}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIVE_CHECK_SCRIPT="$SCRIPT_DIR/jarvis-photo-live-check.sh"
REQUEST_ID="photo-series-$(date +%s)-$$"
SUMMARY_FILE="${JARVIS_PHOTO_SERIES_SUMMARY_FILE:-/tmp/jarvis-photo-live-series-${REQUEST_ID}.summary}"
OPEN_CAMERA_FIRST="${JARVIS_PHOTO_SERIES_OPEN_CAMERA_FIRST:-1}"
OPEN_CAMERA_EACH="${JARVIS_PHOTO_SERIES_OPEN_CAMERA_EACH:-0}"

usage() {
  echo "usage: $0 [trials] [duration_seconds]" >&2
}

require_positive_integer() {
  local name="$1"
  local value="$2"
  case "$value" in
    ''|*[!0-9]*)
      echo "$name must be a positive integer" >&2
      usage
      exit 2
      ;;
  esac
  if [[ "$value" -le 0 ]]; then
    echo "$name must be a positive integer" >&2
    usage
    exit 2
  fi
}

require_positive_integer "trials" "$TRIALS"
require_positive_integer "duration_seconds" "$DURATION_SECONDS"

SUCCESS_COUNT=0
FAIL_COUNT=0
mkdir -p "$(dirname "$SUMMARY_FILE")"
: > "$SUMMARY_FILE"

echo "photo_live_series request_id=$REQUEST_ID trials=$TRIALS duration_seconds=$DURATION_SECONDS" | tee -a "$SUMMARY_FILE"
echo "Say '자비스 사진 찍어' once per trial when prompted." | tee -a "$SUMMARY_FILE"

for trial in $(seq 1 "$TRIALS"); do
  TRIAL_LOG="/tmp/jarvis-photo-live-${REQUEST_ID}-trial-${trial}.out"
  if [[ "$trial" -eq 1 ]]; then
    OPEN_CAMERA="$OPEN_CAMERA_FIRST"
  else
    OPEN_CAMERA="$OPEN_CAMERA_EACH"
  fi

  echo "== trial $trial/$TRIALS ==" | tee -a "$SUMMARY_FILE"
  set +e
  JARVIS_PHOTO_LIVE_OPEN_CAMERA="$OPEN_CAMERA" \
    "$LIVE_CHECK_SCRIPT" "$DURATION_SECONDS" > "$TRIAL_LOG" 2>&1
  STATUS=$?
  set -e

  cat "$TRIAL_LOG"
  LOG_FILE="$(awk -F= '/^log_file=/ { value=$2 } END { print value }' "$TRIAL_LOG")"
  DIAGNOSTIC_LOG_FILE="$(awk -F= '/^diagnostic_log_file=/ { value=$2 } END { print value }' "$TRIAL_LOG")"
  EVENTS_LINE="$(grep '^events ' "$TRIAL_LOG" | tail -1 || true)"
  PASS_LINE="$(grep '^PASS:' "$TRIAL_LOG" | tail -1 || true)"
  FAIL_LINE="$(grep '^FAIL:' "$TRIAL_LOG" | tail -1 || true)"

  if [[ "$STATUS" -eq 0 ]]; then
    SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
    echo "trial=$trial status=PASS ${EVENTS_LINE} log_file=${LOG_FILE:-unknown} diagnostic_log_file=${DIAGNOSTIC_LOG_FILE:-unknown}" | tee -a "$SUMMARY_FILE"
    if [[ -n "$PASS_LINE" ]]; then
      echo "  $PASS_LINE" | tee -a "$SUMMARY_FILE"
    fi
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
    echo "trial=$trial status=FAIL exit_code=$STATUS ${EVENTS_LINE} log_file=${LOG_FILE:-unknown} diagnostic_log_file=${DIAGNOSTIC_LOG_FILE:-unknown} output_file=$TRIAL_LOG" | tee -a "$SUMMARY_FILE"
    if [[ -n "$FAIL_LINE" ]]; then
      echo "  $FAIL_LINE" | tee -a "$SUMMARY_FILE"
    fi
  fi

  if [[ "$trial" -lt "$TRIALS" ]]; then
    sleep 1
  fi
done

RATE=$((SUCCESS_COUNT * 100 / TRIALS))
echo "summary trials=$TRIALS pass=$SUCCESS_COUNT fail=$FAIL_COUNT success_rate=${RATE}% summary_file=$SUMMARY_FILE" | tee -a "$SUMMARY_FILE"

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi

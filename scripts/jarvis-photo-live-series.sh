#!/usr/bin/env bash
set -euo pipefail

TRIALS="${1:-5}"
DURATION_SECONDS="${2:-12}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIVE_CHECK_SCRIPT="$SCRIPT_DIR/jarvis-photo-live-check.sh"
REQUEST_ID="photo-series-$(date +%s)-$$"
SUMMARY_FILE="${JARVIS_PHOTO_SERIES_SUMMARY_FILE:-/tmp/jarvis-photo-live-series-${REQUEST_ID}.summary}"
if [[ "$SUMMARY_FILE" == *.summary ]]; then
  TSV_FILE="${JARVIS_PHOTO_SERIES_TSV_FILE:-${SUMMARY_FILE%.summary}.tsv}"
else
  TSV_FILE="${JARVIS_PHOTO_SERIES_TSV_FILE:-${SUMMARY_FILE}.tsv}"
fi
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

extract_field() {
  local line="$1"
  local key="$2"
  awk -v key="$key" '
    {
      marker = key "="
      start = index($0, marker)
      if (start == 0) exit
      rest = substr($0, start + length(marker))
      if (key == "stt_text") {
        print rest
        exit
      }
      split(rest, parts, " ")
      print parts[1]
    }
  ' <<< "$line"
}

sanitize_tsv() {
  tr '\t\r\n' ' ' <<< "${1:-}" | sed 's/[[:space:]]\+/ /g; s/^ //; s/ $//'
}

SUCCESS_COUNT=0
FAIL_COUNT=0
mkdir -p "$(dirname "$SUMMARY_FILE")"
mkdir -p "$(dirname "$TSV_FILE")"
: > "$SUMMARY_FILE"
: > "$TSV_FILE"
printf 'trial\tstatus\tfailure_type\tparsed_source\tparsed_ms\tspeech_parse_ms\taccess_ms\tspeech_access_ms\tcommand_access_ms\tstt_text\tready_for_speech\tspeech_begin\tpartial_results\tfinal_results\tparse_no_command\tlog_file\tdiagnostic_log_file\toutput_file\n' > "$TSV_FILE"

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
  RESULT_LINE="$(grep '^result ' "$TRIAL_LOG" | tail -1 || true)"
  PASS_LINE="$(grep '^PASS:' "$TRIAL_LOG" | tail -1 || true)"
  FAIL_LINE="$(grep '^FAIL:' "$TRIAL_LOG" | tail -1 || true)"
  RESULT_STATUS="$(extract_field "$RESULT_LINE" status)"
  FAILURE_TYPE="$(extract_field "$RESULT_LINE" failure_type)"
  PARSED_SOURCE="$(extract_field "$RESULT_LINE" parsed_source)"
  PARSED_MS="$(extract_field "$RESULT_LINE" parsed_ms)"
  SPEECH_PARSE_MS="$(extract_field "$RESULT_LINE" speech_parse_ms)"
  ACCESS_MS="$(extract_field "$RESULT_LINE" access_ms)"
  SPEECH_ACCESS_MS="$(extract_field "$RESULT_LINE" speech_access_ms)"
  COMMAND_ACCESS_MS="$(extract_field "$RESULT_LINE" command_access_ms)"
  STT_TEXT="$(extract_field "$RESULT_LINE" stt_text)"
  READY_COUNT="$(extract_field "$EVENTS_LINE" ready_for_speech)"
  SPEECH_BEGIN_COUNT="$(extract_field "$EVENTS_LINE" speech_begin)"
  PARTIAL_COUNT="$(extract_field "$EVENTS_LINE" partial_results)"
  FINAL_COUNT="$(extract_field "$EVENTS_LINE" final_results)"
  PARSE_NO_COMMAND_COUNT="$(extract_field "$EVENTS_LINE" parse_no_command)"
  RESULT_STATUS="${RESULT_STATUS:-unknown}"
  FAILURE_TYPE="${FAILURE_TYPE:-unknown}"
  PARSED_SOURCE="${PARSED_SOURCE:-unknown}"
  PARSED_MS="${PARSED_MS:-0}"
  SPEECH_PARSE_MS="${SPEECH_PARSE_MS:-0}"
  ACCESS_MS="${ACCESS_MS:-0}"
  SPEECH_ACCESS_MS="${SPEECH_ACCESS_MS:-0}"
  COMMAND_ACCESS_MS="${COMMAND_ACCESS_MS:-0}"
  STT_TEXT="${STT_TEXT:-unknown}"
  READY_COUNT="${READY_COUNT:-0}"
  SPEECH_BEGIN_COUNT="${SPEECH_BEGIN_COUNT:-0}"
  PARTIAL_COUNT="${PARTIAL_COUNT:-0}"
  FINAL_COUNT="${FINAL_COUNT:-0}"
  PARSE_NO_COMMAND_COUNT="${PARSE_NO_COMMAND_COUNT:-0}"

  if [[ "$STATUS" -eq 0 ]]; then
    SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
    echo "trial=$trial status=PASS ${RESULT_LINE} ${EVENTS_LINE} log_file=${LOG_FILE:-unknown} diagnostic_log_file=${DIAGNOSTIC_LOG_FILE:-unknown}" | tee -a "$SUMMARY_FILE"
    if [[ -n "$PASS_LINE" ]]; then
      echo "  $PASS_LINE" | tee -a "$SUMMARY_FILE"
    fi
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
    echo "trial=$trial status=FAIL exit_code=$STATUS ${RESULT_LINE} ${EVENTS_LINE} log_file=${LOG_FILE:-unknown} diagnostic_log_file=${DIAGNOSTIC_LOG_FILE:-unknown} output_file=$TRIAL_LOG" | tee -a "$SUMMARY_FILE"
    if [[ -n "$FAIL_LINE" ]]; then
      echo "  $FAIL_LINE" | tee -a "$SUMMARY_FILE"
    fi
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$trial" \
    "$RESULT_STATUS" \
    "$FAILURE_TYPE" \
    "$PARSED_SOURCE" \
    "$PARSED_MS" \
    "$SPEECH_PARSE_MS" \
    "$ACCESS_MS" \
    "$SPEECH_ACCESS_MS" \
    "$COMMAND_ACCESS_MS" \
    "$(sanitize_tsv "$STT_TEXT")" \
    "$READY_COUNT" \
    "$SPEECH_BEGIN_COUNT" \
    "$PARTIAL_COUNT" \
    "$FINAL_COUNT" \
    "$PARSE_NO_COMMAND_COUNT" \
    "${LOG_FILE:-unknown}" \
    "${DIAGNOSTIC_LOG_FILE:-unknown}" \
    "$TRIAL_LOG" >> "$TSV_FILE"

  if [[ "$trial" -lt "$TRIALS" ]]; then
    sleep 1
  fi
done

RATE=$((SUCCESS_COUNT * 100 / TRIALS))
echo "summary trials=$TRIALS pass=$SUCCESS_COUNT fail=$FAIL_COUNT success_rate=${RATE}% summary_file=$SUMMARY_FILE tsv_file=$TSV_FILE" | tee -a "$SUMMARY_FILE"
awk '
  /^trial=/ && /status=FAIL/ {
    for (i = 1; i <= NF; i++) {
      split($i, pair, "=")
      if (pair[1] == "failure_type") failures[pair[2]]++
    }
  }
  END {
    for (failure in failures) {
      printf "failure_type=%s count=%d\n", failure, failures[failure]
    }
  }
' "$SUMMARY_FILE" | sort | tee -a "$SUMMARY_FILE"

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi

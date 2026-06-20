#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_SCRIPT="$SCRIPT_DIR/jarvis-latency-report.sh"
MAX_PARSED_MS="${JARVIS_MAX_PARSED_MS:-2500}"
MAX_SPEECH_PARSED_MS="${JARVIS_MAX_SPEECH_PARSED_MS:-2500}"
MAX_ACCESS_MS="${JARVIS_MAX_ACCESS_MS:-4000}"

usage() {
  echo "usage: $0 <jarvis_latency_log> [...]" >&2
}

require_integer() {
  local name="$1"
  local value="$2"
  case "$value" in
    ''|*[!0-9]*)
      echo "${name} must be an integer" >&2
      exit 2
      ;;
  esac
}

count_pattern() {
  local file="$1"
  local pattern="$2"
  if [[ ! -f "$file" ]]; then
    echo 0
    return
  fi
  grep -c "$pattern" "$file" || true
}

count_event() {
  local file="$1"
  local event="$2"
  count_pattern "$file" "event=${event}"
}

slow_command_lines() {
  local lines="$1"
  printf '%s\n' "$lines" | awk \
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
}

print_owner_reject_reasons() {
  local diagnostic_file="$1"
  if [[ ! -f "$diagnostic_file" ]]; then
    return
  fi

  awk '
    /Owner voice rejected/ {
      marker = "reason="
      start = index($0, marker)
      if (start > 0) {
        reason = substr($0, start + length(marker))
        sub(/[ ,].*/, "", reason)
        if (reason != "") reasons[reason]++
      }
    }
    END {
      for (reason in reasons) print reason "=" reasons[reason]
    }
  ' "$diagnostic_file" | sort -t= -k2,2nr | head -5
}

status_hint() {
  local status="$1"
  case "$status" in
    PASS)
      echo "hint=command_complete trace is within latency thresholds."
      ;;
    FAIL_SLOW)
      echo "hint=command_complete exists, but one or more latency thresholds were exceeded."
      ;;
    FAIL_NO_OWNER_WAKE)
      echo "hint=Owner voice gate did not authorize wake. Check peak RMS, reject reasons, distance, and owner voice enrollment."
      ;;
    FAIL_NO_SPEECH)
      echo "hint=Jarvis opened a command window, but command STT did not detect speech."
      ;;
    FAIL_NO_COMMAND)
      echo "hint=Speech was detected, but no supported command was parsed."
      ;;
    FAIL_NO_COMMAND_COMPLETE)
      echo "hint=Command parsing started, but execution did not complete."
      ;;
    *)
      echo "hint=No actionable JarvisLatency progress was captured."
      ;;
  esac
}

audit_file() {
  local log_file="$1"
  local diagnostic_file="${JARVIS_DIAGNOSTIC_LOG_FILE:-${log_file}.diagnostic}"
  local report_output
  local command_complete_lines
  local slow_lines
  local status

  if [[ ! -f "$log_file" ]]; then
    echo "== $log_file =="
    echo "status=FAIL_MISSING_LOG"
    echo "hint=log file does not exist."
    return 1
  fi

  report_output="$("$REPORT_SCRIPT" "$log_file")"
  command_complete_lines="$(
    printf '%s\n' "$report_output" | awk '/^trace=/ && /status=command_complete/'
  )"
  slow_lines="$(slow_command_lines "$command_complete_lines")"

  local owner_authorized
  local ready_for_speech
  local speech_begin
  local partial_results
  local command_parsed
  local wake_only_partial
  local owner_audio_wake_only
  local non_strict_idle_suppressed
  local fallback_to_local
  local command_complete_count
  local owner_accepted
  local owner_rejected
  local owner_suppressed

  owner_authorized="$(count_event "$log_file" owner_authorized)"
  ready_for_speech="$(count_event "$log_file" ready_for_speech)"
  speech_begin="$(count_event "$log_file" speech_begin)"
  partial_results="$(count_event "$log_file" partial_results)"
  command_parsed="$(count_event "$log_file" command_parsed)"
  wake_only_partial="$(count_event "$log_file" wake_only_partial)"
  owner_audio_wake_only="$(count_event "$log_file" owner_audio_wake_only)"
  non_strict_idle_suppressed="$(count_event "$log_file" non_strict_wake_idle_suppressed)"
  fallback_to_local="$(count_event "$log_file" fallback_to_local)"
  command_complete_count="$(
    printf '%s\n' "$command_complete_lines" | awk 'NF > 0 { count++ } END { print count + 0 }'
  )"
  owner_accepted="$(count_pattern "$diagnostic_file" "Owner voice accepted")"
  owner_rejected="$(count_pattern "$diagnostic_file" "Owner voice rejected")"
  owner_suppressed="$(count_pattern "$diagnostic_file" "Owner voice suppressed")"

  if [[ "$command_complete_count" -gt 0 ]]; then
    if [[ -n "$slow_lines" ]]; then
      status="FAIL_SLOW"
    else
      status="PASS"
    fi
  elif [[ "$owner_authorized" -eq 0 ]]; then
    status="FAIL_NO_OWNER_WAKE"
  elif [[ "$speech_begin" -eq 0 && "$partial_results" -eq 0 ]]; then
    status="FAIL_NO_SPEECH"
  elif [[ "$command_parsed" -eq 0 ]]; then
    status="FAIL_NO_COMMAND"
  else
    status="FAIL_NO_COMMAND_COMPLETE"
  fi

  echo "== $log_file =="
  printf '%s\n' "$report_output"
  echo "events: owner_authorized=${owner_authorized}, ready_for_speech=${ready_for_speech}, speech_begin=${speech_begin}, partial_results=${partial_results}, command_parsed=${command_parsed}, wake_only_partial=${wake_only_partial}, owner_audio_wake_only=${owner_audio_wake_only}, non_strict_wake_idle_suppressed=${non_strict_idle_suppressed}, fallback_to_local=${fallback_to_local}"
  echo "owner_gate: accepted=${owner_accepted}, rejected=${owner_rejected}, suppressed=${owner_suppressed}"
  if [[ -f "$diagnostic_file" ]]; then
    echo "diagnostic=$diagnostic_file"
    local reject_reasons
    reject_reasons="$(print_owner_reject_reasons "$diagnostic_file")"
    if [[ -n "$reject_reasons" ]]; then
      echo "owner_reject_reasons:"
      printf '%s\n' "$reject_reasons" | sed 's/^/  /'
    fi
  else
    echo "diagnostic=missing"
  fi
  echo "status=$status"
  status_hint "$status"
  if [[ -n "$slow_lines" ]]; then
    echo "slow_traces:"
    printf '%s\n' "$slow_lines" | sed 's/^/  /'
  fi

  [[ "$status" == "PASS" ]]
}

require_integer JARVIS_MAX_PARSED_MS "$MAX_PARSED_MS"
require_integer JARVIS_MAX_SPEECH_PARSED_MS "$MAX_SPEECH_PARSED_MS"
require_integer JARVIS_MAX_ACCESS_MS "$MAX_ACCESS_MS"

if [[ "$#" -eq 0 ]]; then
  usage
  exit 2
fi

exit_code=0
for log_file in "$@"; do
  if ! audit_file "$log_file"; then
    exit_code=1
  fi
done

exit "$exit_code"

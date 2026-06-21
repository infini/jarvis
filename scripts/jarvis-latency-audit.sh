#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_SCRIPT="$SCRIPT_DIR/jarvis-latency-report.sh"
MAX_PARSED_MS="${JARVIS_MAX_PARSED_MS:-2500}"
MAX_SPEECH_PARSED_MS="${JARVIS_MAX_SPEECH_PARSED_MS:-2500}"
MAX_ACCESS_MS="${JARVIS_MAX_ACCESS_MS:-4000}"
MAX_OWNER_GATE_MS="${JARVIS_MAX_OWNER_GATE_MS:-2500}"
MAX_COMMAND_ACCESS_MS="${JARVIS_MAX_COMMAND_ACCESS_MS:-1200}"

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
  if [[ ! -f "$file" ]]; then
    echo 0
    return
  fi
  grep -Ec "event=${event}([[:space:]]|$)" "$file" || true
}

slow_command_lines() {
  local lines="$1"
  printf '%s\n' "$lines" | awk \
    -v maxParsed="$MAX_PARSED_MS" \
    -v maxSpeechParsed="$MAX_SPEECH_PARSED_MS" \
    -v maxAccess="$MAX_ACCESS_MS" \
    -v maxOwnerGate="$MAX_OWNER_GATE_MS" \
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
        ownerGate = fieldValue("owner_gate")
        parseBudget = speechParsed > 0 ? maxSpeechParsed : maxParsed
        parseLatency = speechParsed > 0 ? speechParsed : parsed
        accessLatency = speechAccess > 0 ? speechAccess : access
        slow = parseLatency <= 0
        slow = slow || parseLatency > parseBudget
        slow = slow || (accessLatency > 0 && accessLatency > maxAccess)
        slow = slow || (commandAccess > 0 && commandAccess > maxCommandAccess)
        slow = slow || (ownerGate > 0 && ownerGate > maxOwnerGate)
        if (slow) print
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

owner_gate_stats() {
  local diagnostic_file="$1"
  if [[ ! -f "$diagnostic_file" ]]; then
    return
  fi

  awk '
    /Owner voice/ {
      score = ""
      peak = ""
      noise = ""
      threshold = ""
      profileEmbeddings = ""
      for (i = 1; i <= NF; i++) {
        split($i, pair, "=")
        if (pair[1] == "score") score = pair[2] + 0
        if (pair[1] == "peakRms") peak = pair[2] + 0
        if (pair[1] == "noiseRms") noise = pair[2] + 0
        if (pair[1] == "thresholdRms") threshold = pair[2] + 0
        if (pair[1] == "profileEmbeddings") profileEmbeddings = pair[2] + 0
      }
      count++
      if (score != "" && (!hasScore || score > maxScore)) {
        hasScore = 1
        maxScore = score
      }
      if (peak != "" && peak > maxPeak) maxPeak = peak
      if (noise != "" && noise > maxNoise) maxNoise = noise
      if (threshold != "" && threshold > maxThreshold) maxThreshold = threshold
      if (profileEmbeddings != "" && profileEmbeddings > maxProfileEmbeddings) maxProfileEmbeddings = profileEmbeddings
    }
    END {
      if (count > 0) {
        printf "samples=%d max_score=%.6g max_peak_rms=%.6g max_noise_rms=%.6g max_threshold_rms=%.6g profile_embeddings=%d\n", count, maxScore, maxPeak, maxNoise, maxThreshold, maxProfileEmbeddings
      }
    }
  ' "$diagnostic_file"
}

max_profile_embeddings() {
  local log_file="$1"
  local diagnostic_file="$2"
  local files=()

  [[ -f "$log_file" ]] && files+=("$log_file")
  [[ -f "$diagnostic_file" ]] && files+=("$diagnostic_file")
  if [[ "${#files[@]}" -eq 0 ]]; then
    return
  fi

  awk '
    {
      for (i = 1; i <= NF; i++) {
        split($i, pair, "=")
        if (pair[1] == "profileEmbeddings" || pair[1] == "profile_embeddings") {
          value = pair[2] + 0
          seen = 1
          if (value > maxValue) maxValue = value
        }
      }
    }
    END {
      if (seen) print maxValue + 0
    }
  ' "${files[@]}"
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
    FAIL_NO_OWNER_AUTH)
      echo "hint=Owner voice gate did not authorize the speaker. Check peak RMS, reject reasons, distance, and owner voice enrollment."
      ;;
    FAIL_NO_ACTIVATION)
      echo "hint=Owner voice was authorized, but local ASR did not recognize the activation phrase '자비스 실행'."
      ;;
    FAIL_LEGACY_PROFILE)
      echo "hint=Owner profile has fewer than 2 embeddings. Re-register owner voice before judging wake or command latency."
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
  local activation_partial
  local owner_audio_activation
  local fallback_to_local
  local command_complete_count
  local owner_accepted
  local owner_rejected
  local owner_suppressed
  local profile_embeddings

  owner_authorized="$(count_event "$log_file" owner_authorized)"
  ready_for_speech="$(count_event "$log_file" ready_for_speech)"
  speech_begin="$(count_event "$log_file" speech_begin)"
  partial_results="$(count_event "$log_file" partial_results)"
  command_parsed="$(count_event "$log_file" command_parsed)"
  activation_partial="$(count_event "$log_file" activation_partial)"
  owner_audio_activation="$(count_event "$log_file" owner_audio_activation)"
  fallback_to_local="$(count_event "$log_file" fallback_to_local)"
  command_complete_count="$(
    printf '%s\n' "$command_complete_lines" | awk 'NF > 0 { count++ } END { print count + 0 }'
  )"
  owner_accepted="$(count_pattern "$diagnostic_file" "Owner voice accepted")"
  owner_rejected="$(count_pattern "$diagnostic_file" "Owner voice rejected")"
  owner_suppressed="$(count_pattern "$diagnostic_file" "Owner voice suppressed")"
  profile_embeddings="$(max_profile_embeddings "$log_file" "$diagnostic_file")"

  if [[ -n "$profile_embeddings" && "$profile_embeddings" -gt 0 && "$profile_embeddings" -lt 2 ]]; then
    status="FAIL_LEGACY_PROFILE"
  elif [[ "$command_complete_count" -gt 0 ]]; then
    if [[ -n "$slow_lines" ]]; then
      status="FAIL_SLOW"
    else
      status="PASS"
    fi
  elif [[ "$owner_authorized" -eq 0 ]]; then
    status="FAIL_NO_OWNER_AUTH"
  elif [[ "$owner_audio_activation" -eq 0 ]]; then
    status="FAIL_NO_ACTIVATION"
  elif [[ "$speech_begin" -eq 0 && "$partial_results" -eq 0 ]]; then
    status="FAIL_NO_SPEECH"
  elif [[ "$command_parsed" -eq 0 ]]; then
    status="FAIL_NO_COMMAND"
  else
    status="FAIL_NO_COMMAND_COMPLETE"
  fi

  echo "== $log_file =="
  printf '%s\n' "$report_output"
  echo "events: owner_authorized=${owner_authorized}, owner_audio_activation=${owner_audio_activation}, ready_for_speech=${ready_for_speech}, speech_begin=${speech_begin}, partial_results=${partial_results}, command_parsed=${command_parsed}, activation_partial=${activation_partial}, fallback_to_local=${fallback_to_local}"
  if [[ -n "$profile_embeddings" ]]; then
    echo "profile_embeddings=${profile_embeddings}"
  fi
  echo "owner_gate: accepted=${owner_accepted}, rejected=${owner_rejected}, suppressed=${owner_suppressed}"
  if [[ -f "$diagnostic_file" ]]; then
    echo "diagnostic=$diagnostic_file"
    local gate_stats
    gate_stats="$(owner_gate_stats "$diagnostic_file")"
    if [[ -n "$gate_stats" ]]; then
      echo "owner_gate_stats: $gate_stats"
    fi
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
require_integer JARVIS_MAX_OWNER_GATE_MS "$MAX_OWNER_GATE_MS"
require_integer JARVIS_MAX_COMMAND_ACCESS_MS "$MAX_COMMAND_ACCESS_MS"

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

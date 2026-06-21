#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${1:-/tmp/jarvis-wake-diagnose-$(date +%Y%m%d-%H%M%S)}"
PROFILE_LOG="$OUT_DIR/profile.log"
REPLAY_LOG="$OUT_DIR/activation-replay.log"
CAPTURE_LOG="$OUT_DIR/activation-captures.log"
DEVICE_LOG="$OUT_DIR/jarvis-logcat.log"
CAPTURE_DIR="$OUT_DIR/jarvis-activation-attempts"

mkdir -p "$OUT_DIR"

run_with_status() {
  local output_file="$1"
  shift

  set +e
  "$@" | tee "$output_file"
  local status="${PIPESTATUS[0]}"
  set -e
  return "$status"
}

field_from_line() {
  local key="$1"
  local line="$2"
  printf '%s\n' "$line" | sed -n "s/.*${key}=\\([^[:space:]]*\\).*/\\1/p"
}

echo "== profile =="
if run_with_status "$PROFILE_LOG" "$SCRIPT_DIR/jarvis-profile-status.sh"; then
  profile_status="PASS"
else
  profile_status="FAIL"
fi

echo
echo "== activation replay =="
if run_with_status "$REPLAY_LOG" "$SCRIPT_DIR/jarvis-activation-replay.sh"; then
  replay_status="PASS"
else
  replay_status="FAIL"
fi

echo
echo "== activation captures =="
if run_with_status "$CAPTURE_LOG" "$SCRIPT_DIR/jarvis-activation-captures.sh" "$OUT_DIR"; then
  captures_status="PASS"
else
  captures_status="FAIL"
fi

adb logcat -d -v time -s JarvisLatency OwnerVoiceGate JarvisVoiceService JarvisDebugReplay > "$DEVICE_LOG" || true

completed_line="$(grep "status=completed" "$REPLAY_LOG" | tail -1 || true)"
replay_total="$(field_from_line total "$completed_line")"
replay_accepted="$(field_from_line accepted "$completed_line")"
replay_total="${replay_total:-0}"
replay_accepted="${replay_accepted:-0}"

echo
echo "== summary =="
echo "profile=${profile_status}"
echo "activation_replay=${replay_status} total=${replay_total} accepted=${replay_accepted}"
echo "activation_captures=${captures_status}"

if [[ -d "$CAPTURE_DIR" ]]; then
  json_count="$(find "$CAPTURE_DIR" -maxdepth 1 -type f -name '*.json' | wc -l | tr -d ' ')"
  accepted_json_count="$(find "$CAPTURE_DIR" -maxdepth 1 -type f -name '*-accepted.json' | wc -l | tr -d ' ')"
  rejected_json_count="$(find "$CAPTURE_DIR" -maxdepth 1 -type f -name '*-rejected.json' | wc -l | tr -d ' ')"
  echo "saved_captures json=${json_count} accepted=${accepted_json_count} rejected=${rejected_json_count}"

  if command -v jq >/dev/null 2>&1; then
    echo "latest_capture_metadata:"
    find "$CAPTURE_DIR" -maxdepth 1 -type f -name '*.json' | sort | tail -8 | while read -r metadata_file; do
      jq -r '[
        (input_filename | split("/")[-1]),
        "accepted=\(.accepted)",
        "text=\(.text)",
        "elapsedMs=\(.elapsedMs)",
        "activeSpeechMs=\(.activeSpeechMs)",
        "peakRms=\(.peakRms)",
        "meanRms=\(.meanRms)",
        "asrGain=\(.asrGain)",
        "durationMs=\((.sampleCount * 1000 / .sampleRateHz) | floor)"
      ] | @tsv' "$metadata_file"
    done
  fi
fi

echo "logs=$OUT_DIR"

if [[ "$profile_status" != "PASS" ]]; then
  echo "status=FAIL_PROFILE"
  echo "hint=Owner profile is not ready. Re-register owner voice before judging wake recognition."
  exit 1
fi

if [[ "$replay_status" != "PASS" ]]; then
  echo "status=FAIL_REPLAY_UNAVAILABLE"
  echo "hint=No saved activation captures could be replayed. One real wake attempt is needed to create a capture."
  exit 1
fi

if [[ "$replay_total" -gt 0 && "$replay_accepted" -eq 0 ]]; then
  echo "status=FAIL_NO_ACCEPTED_ACTIVATION_REPLAY"
  echo "hint=Owner voice reached activation ASR, but saved wake audio is not recognized as '자비스 깨어나'. Check capture duration/text/RMS before asking for another live attempt."
  exit 1
fi

echo "status=PASS"

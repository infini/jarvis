#!/usr/bin/env bash
set -euo pipefail

DURATION_SECONDS="${1:-20}"
LOG_FILE="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMP_LOG_FILE=""
START_DEBUG_ACTIVITY="${JARVIS_START_DEBUG_ACTIVITY:-0}"
START_SERVICE="${JARVIS_START_SERVICE:-1}"
SKIP_PROFILE_CHECK="${JARVIS_SKIP_PROFILE_CHECK:-0}"
PROMPT_DELAY_SECONDS="${JARVIS_WAKE_PROMPT_DELAY_SECONDS:-2}"
PROMPT_VIBRATE_MS="${JARVIS_WAKE_PROMPT_VIBRATE_MS:-250}"
SERVICE_COMPONENT="com.personal.jarvis/.JarvisVoiceService"

case "$DURATION_SECONDS" in
  ''|*[!0-9]*)
    echo "usage: $0 [duration_seconds] [optional_log_file]" >&2
    exit 2
    ;;
esac

if [[ "$SKIP_PROFILE_CHECK" != "1" ]]; then
  "$SCRIPT_DIR/jarvis-profile-status.sh"
fi

if [[ -z "$LOG_FILE" ]]; then
  TEMP_LOG_FILE="$(mktemp -t jarvis-wake-live.XXXXXX.log)"
  LOG_FILE="$TEMP_LOG_FILE"
fi

cleanup() {
  if [[ -n "$TEMP_LOG_FILE" && "${JARVIS_KEEP_WAKE_LOG:-0}" != "1" ]]; then
    rm -f "$TEMP_LOG_FILE"
  fi
}
trap cleanup EXIT

echo "Preparing Jarvis idle activation state."
adb logcat -c
if [[ "$START_DEBUG_ACTIVITY" == "1" ]]; then
  adb shell am start \
    -n com.personal.jarvis/.debug.JarvisDebugStartActivity \
    --ez reset_voice_service true >/dev/null 2>&1 || true
elif [[ "$START_SERVICE" == "1" ]]; then
  adb shell am start-foreground-service \
    -n "$SERVICE_COMPONENT" \
    --es start_source wake_live_check >/dev/null 2>&1 ||
    adb shell am startservice \
      -n "$SERVICE_COMPONENT" \
      --es start_source wake_live_check >/dev/null 2>&1 || true
fi

IDLE_READY=""
for _ in {1..15}; do
  IDLE_READY="$(
    adb logcat -d -v time -s JarvisLatency |
      grep -E "event=activation_listen_start .*engine=local_activation_asr" |
      tail -1 || true
  )"
  if [[ -z "$IDLE_READY" ]]; then
    IDLE_READY="$(
      adb shell dumpsys activity services 2>/dev/null |
        grep -A35 "$SERVICE_COMPONENT" |
        grep -m1 "isForeground=true" || true
    )"
  fi
  if [[ -n "$IDLE_READY" ]]; then
    break
  fi
  sleep 1
done

if [[ -z "$IDLE_READY" ]]; then
  echo "FAIL: Jarvis did not enter idle activation listening before live wake check." >&2
  adb logcat -d -v time -s JarvisLatency JarvisVoiceService JarvisVoiceStarter | tail -80 >&2 || true
  adb shell dumpsys activity services 2>/dev/null | grep -A45 "$SERVICE_COMPONENT" >&2 || true
  exit 1
fi

if [[ "$PROMPT_DELAY_SECONDS" != "0" ]]; then
  echo "Get ready: phone will vibrate in ${PROMPT_DELAY_SECONDS}s. Speak after the vibration."
  sleep "$PROMPT_DELAY_SECONDS"
fi
if [[ "$PROMPT_VIBRATE_MS" != "0" ]]; then
  adb shell cmd vibrator_manager synced -f oneshot -a "$PROMPT_VIBRATE_MS" 180 >/dev/null 2>&1 || true
  sleep 0.4
fi
adb logcat -c
echo "Speak now: say '자비스 깨어나' within ${DURATION_SECONDS}s, then wait for the center JARVIS indicator."
sleep "$DURATION_SECONDS"

adb logcat -d -v time \
  -s JarvisLatency JarvisVoiceService JarvisLocalCommand JarvisStateIndicator JarvisFeedback \
  > "$LOG_FILE"

count_event() {
  local event="$1"
  grep -Ec "event=${event}([[:space:]]|$)" "$LOG_FILE" || true
}

max_metric() {
  local key="$1"
  awk -v key="$key" '
    {
      for (i = 1; i <= NF; i += 1) {
        if ($i ~ ("^" key "=")) {
          split($i, parts, "=")
          value = parts[2] + 0
          if (!seen || value > max) max = value
          seen = 1
        }
      }
    }
    END {
      if (seen) {
        printf "%.6f", max
      } else {
        printf "0"
      }
    }
  ' "$LOG_FILE"
}

ACTIVATION_COMPLETE_COUNT="$(count_event activation_asr_complete)"
ACTIVATION_REJECTED_SEGMENT_COUNT="$(count_event activation_asr_rejected_segment)"
ACTIVATION_OWNER_VERIFIED_COUNT="$(count_event activation_owner_verified)"
OWNER_AUDIO_ACTIVATION_COUNT="$(count_event owner_audio_activation)"
READY_FOR_SPEECH_COUNT="$(count_event ready_for_speech)"
COMMAND_READY_FEEDBACK_COUNT="$(grep -c "feedback=command_ready" "$LOG_FILE" || true)"
OVERLAY_READY_COUNT="$(grep -c "JarvisStateIndicator.*overlay_visible state=COMMAND_READY" "$LOG_FILE" || true)"
OWNER_REJECTED_COUNT="$(grep -Ec "event=activation_owner_rejected([[:space:]]|$)" "$LOG_FILE" || true)"
PHRASE_MISSING_COUNT="$(grep -Ec "event=activation_phrase_missing([[:space:]]|$)" "$LOG_FILE" || true)"
MAX_PEAK_RMS="$(max_metric peakRms)"
MAX_SPEECH_MS="$(max_metric speechMs)"

echo "log_file=$LOG_FILE"
echo "summary activation_asr_complete=$ACTIVATION_COMPLETE_COUNT activation_asr_rejected_segment=$ACTIVATION_REJECTED_SEGMENT_COUNT activation_owner_verified=$ACTIVATION_OWNER_VERIFIED_COUNT owner_audio_activation=$OWNER_AUDIO_ACTIVATION_COUNT ready_for_speech=$READY_FOR_SPEECH_COUNT command_ready_feedback=$COMMAND_READY_FEEDBACK_COUNT overlay_ready=$OVERLAY_READY_COUNT activation_owner_rejected=$OWNER_REJECTED_COUNT activation_phrase_missing=$PHRASE_MISSING_COUNT"
echo "audio max_peak_rms=$MAX_PEAK_RMS max_speech_ms=$MAX_SPEECH_MS"

echo "recent_activation_events:"
grep -E "event=(activation_asr_complete|activation_asr_rejected_segment|activation_owner_verified|owner_audio_activation|activation_owner_rejected|activation_phrase_missing|ready_for_speech)" "$LOG_FILE" | tail -20 || true

echo "recent_overlay_events:"
grep "JarvisStateIndicator" "$LOG_FILE" | tail -10 || true

if [[ "$OWNER_AUDIO_ACTIVATION_COUNT" -gt 0 && "$OVERLAY_READY_COUNT" -gt 0 ]]; then
  echo "PASS: wake phrase opened command window and center JARVIS overlay was shown."
  exit 0
fi

if [[ "$OWNER_AUDIO_ACTIVATION_COUNT" -gt 0 ]]; then
  echo "FAIL: wake opened command window, but center JARVIS overlay was not observed." >&2
  echo "hint=Check accessibility service binding and JarvisStateIndicator logs." >&2
  exit 1
fi

if [[ "$ACTIVATION_OWNER_VERIFIED_COUNT" -gt 0 && "$OWNER_REJECTED_COUNT" -gt 0 ]]; then
  echo "FAIL: activation phrase was recognized, but owner voice verification rejected it." >&2
  echo "hint=Check activation_owner_verified score/reason above and consider re-enrolling owner voice." >&2
  exit 1
fi

if [[ "$ACTIVATION_COMPLETE_COUNT" -eq 0 && "$ACTIVATION_REJECTED_SEGMENT_COUNT" -eq 0 ]]; then
  echo "FAIL: local activation ASR did not produce any segment." >&2
  echo "hint=If no one spoke during the window, rerun and say '자비스 깨어나'. If you did speak, check microphone permission, foreground service state, and local ASR assets." >&2
  exit 1
fi

echo "FAIL: wake phrase did not open command window." >&2
if awk -v value="$MAX_PEAK_RMS" 'BEGIN { exit !(value > 0 && value < 0.02) }'; then
  echo "hint=Captured audio is very quiet. Hold the phone close to the speaker side and retry after the vibration cue." >&2
fi
echo "hint=If you spoke during the window, inspect the latest activation_asr_rejected_segment text/RMS above or run scripts/jarvis-wake-diagnose.sh." >&2
exit 1

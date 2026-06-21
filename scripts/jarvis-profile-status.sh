#!/usr/bin/env bash
set -euo pipefail

STATUS_ACTIVITY="com.personal.jarvis/.debug.JarvisDebugProfileStatusActivity"
LOG_TAG="JarvisDebugStatus"
REQUEST_ID="$(date +%s)-$$"
WAIT_DEADLINE=5

adb shell am start -n "$STATUS_ACTIVITY" --es request_id "$REQUEST_ID" >/dev/null

STATUS_LINE=""
for ((elapsed = 0; elapsed < WAIT_DEADLINE; elapsed++)); do
  STATUS_LINE="$(
    adb logcat -d -v time -s "$LOG_TAG" |
      grep "request_id=${REQUEST_ID}" |
      tail -1 || true
  )"
  if [[ -n "$STATUS_LINE" ]]; then
    break
  fi
  sleep 1
done

if [[ -z "$STATUS_LINE" ]]; then
  echo "FAIL: no Jarvis profile status log found." >&2
  exit 1
fi

printf '%s\n' "$STATUS_LINE"

EMBEDDING_COUNT="$(
  printf '%s\n' "$STATUS_LINE" | awk '
    {
      for (i = 1; i <= NF; i++) {
        split($i, pair, "=")
        if (pair[1] == "profile_embeddings") {
          print pair[2] + 0
          exit
        }
      }
      print 0
    }
  '
)"

PROFILE_CONFIGURED="$(
  printf '%s\n' "$STATUS_LINE" | awk '
    {
      for (i = 1; i <= NF; i++) {
        split($i, pair, "=")
        if (pair[1] == "profile_configured") {
          print pair[2]
          exit
        }
      }
      print "false"
    }
  '
)"

PROFILE_PHRASE_ID="$(
  printf '%s\n' "$STATUS_LINE" | awk '
    {
      for (i = 1; i <= NF; i++) {
        split($i, pair, "=")
        if (pair[1] == "profile_phrase_id") {
          print pair[2]
          exit
        }
      }
      print "unknown"
    }
  '
)"

if [[ "$PROFILE_CONFIGURED" != "true" ]]; then
  echo "WARN: profile_configured=${PROFILE_CONFIGURED}, profile_embeddings=${EMBEDDING_COUNT}, profile_phrase_id=${PROFILE_PHRASE_ID}. Re-register owner voice with repeated '자비스 실행' before latency verification." >&2
  exit 1
fi

echo "PASS: owner profile has ${EMBEDDING_COUNT} embeddings for ${PROFILE_PHRASE_ID}."

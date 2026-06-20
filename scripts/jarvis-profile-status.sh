#!/usr/bin/env bash
set -euo pipefail

STATUS_ACTIVITY="com.personal.jarvis/.debug.JarvisDebugProfileStatusActivity"
LOG_TAG="JarvisDebugStatus"

adb logcat -c
adb shell am start -n "$STATUS_ACTIVITY" >/dev/null
sleep 1

STATUS_LINE="$(adb logcat -d -v time -s "$LOG_TAG" | grep "$LOG_TAG" | tail -1 || true)"
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

if [[ "$EMBEDDING_COUNT" -lt 2 ]]; then
  echo "WARN: profile_embeddings=${EMBEDDING_COUNT}. Re-register owner voice with repeated '자비스' before latency verification." >&2
  exit 1
fi

echo "PASS: owner profile has ${EMBEDDING_COUNT} embeddings."

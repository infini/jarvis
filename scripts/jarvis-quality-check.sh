#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="${GRADLEW:-$ROOT_DIR/gradlew}"

if [ ! -x "$GRADLEW" ]; then
  echo "FAIL: Gradle wrapper is not executable: $GRADLEW" >&2
  exit 1
fi

"$GRADLEW" -p "$ROOT_DIR" testDebugUnitTest assembleDebug assembleRelease lintDebug

RELEASE_MANIFEST="$ROOT_DIR/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"
DEBUG_MANIFEST="$ROOT_DIR/app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
if [ ! -f "$RELEASE_MANIFEST" ] || [ ! -f "$DEBUG_MANIFEST" ]; then
  echo "FAIL: merged manifest outputs are missing." >&2
  exit 1
fi
if grep -q 'com.personal.jarvis.debug' "$RELEASE_MANIFEST"; then
  echo "FAIL: release manifest contains debug-only components." >&2
  exit 1
fi
if grep -Eq 'ACTION_COMMAND|sendBroadcast|BroadcastReceiver' \
  "$ROOT_DIR/app/src/main/java/com/personal/jarvis/CommandBus.kt" \
  "$ROOT_DIR/app/src/main/java/com/personal/jarvis/JarvisAccessibilityService.kt"; then
  echo "FAIL: accessibility commands must remain process-internal; broadcast transport was found." >&2
    exit 1
fi
if ! awk '
  /JarvisAssistantActivity/ { found=1; remaining=10 }
  found && /android:permission="android.permission.BIND_VOICE_INTERACTION"/ { protected=1 }
  found && remaining-- <= 0 { exit }
  END { exit !(found && protected) }
' "$RELEASE_MANIFEST"; then
  echo "FAIL: JarvisAssistantActivity must require the system voice-interaction permission." >&2
  exit 1
fi
if grep -q 'endpoint = "partial_command"' \
  "$ROOT_DIR/app/src/main/java/com/personal/jarvis/LocalCommandRecognizer.kt"; then
  echo "FAIL: local command partials must remain diagnostic-only until final decoding." >&2
    exit 1
fi
DATA_EXTRACTION_RULES="$ROOT_DIR/app/src/main/res/xml/data_extraction_rules.xml"
LEGACY_BACKUP_RULES="$ROOT_DIR/app/src/main/res/xml/backup_rules.xml"
for domain in root file database sharedpref external device_root device_file device_database device_sharedpref; do
  if [ "$(grep -c "domain=\"$domain\"" "$DATA_EXTRACTION_RULES")" -ne 2 ]; then
    echo "FAIL: cloud and device-transfer rules must both exclude backup domain: $domain" >&2
    exit 1
  fi
  if [ "$(grep -c "domain=\"$domain\"" "$LEGACY_BACKUP_RULES")" -ne 1 ]; then
    echo "FAIL: legacy backup rules must exclude domain: $domain" >&2
    exit 1
  fi
done
if ! grep -q 'JarvisDeveloperMenuActivity' "$DEBUG_MANIFEST"; then
  echo "FAIL: debug manifest is missing JarvisDeveloperMenuActivity." >&2
  exit 1
fi
if ! awk '
  /JarvisDeveloperMenuActivity/ { found=1; remaining=8 }
  found && /android:exported="false"/ { safe=1 }
  found && remaining-- <= 0 { exit }
  END { exit !(found && safe) }
' "$DEBUG_MANIFEST"; then
  echo "FAIL: JarvisDeveloperMenuActivity must remain non-exported." >&2
  exit 1
fi
for activity in \
  JarvisDebugStartActivity \
  JarvisDebugProfileStatusActivity \
  JarvisDebugOwnerEnrollActivity \
  JarvisDebugActivationReplayActivity \
  JarvisDebugCommandWindowActivity \
  JarvisDebugCommandReplayActivity; do
  if ! awk -v component="$activity" '
    index($0, component) { found=1; remaining=8 }
    found && /android:exported="true"/ { exported=1 }
    found && remaining-- <= 0 { exit }
    END { exit !(found && exported) }
  ' "$DEBUG_MANIFEST"; then
    echo "FAIL: debug ADB trampoline missing or not exported: $activity" >&2
    exit 1
  fi
done

git -C "$ROOT_DIR" diff --check
git -C "$ROOT_DIR" diff --cached --check
bash -n "$ROOT_DIR"/scripts/*.sh

echo "PASS: Jarvis quality checks completed."

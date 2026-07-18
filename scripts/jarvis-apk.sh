#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTION="${1:-build}"
VARIANT="${2:-debug}"
REQUESTED_SERIAL="${3:-${ANDROID_SERIAL:-}}"
PACKAGE_NAME="com.personal.jarvis"
EXPECTED_MIN_SDK="26"
EXPECTED_TARGET_SDK="35"
EXPECTED_BUILD_TOOLS="35.0.0"
GRADLEW="$ROOT_DIR/gradlew"

usage() {
  echo "Usage: $0 build|install [debug|release] [adb-serial]" >&2
}

case "$ACTION" in
  build|install) ;;
  *) usage; exit 2 ;;
esac

case "$VARIANT" in
  debug)
    GRADLE_TASK="assembleDebug"
    OUTPUT_DIR="$ROOT_DIR/app/build/outputs/apk/debug"
    ;;
  release)
    GRADLE_TASK="assembleRelease"
    OUTPUT_DIR="$ROOT_DIR/app/build/outputs/apk/release"
    ;;
  *) usage; exit 2 ;;
esac

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK_DIR" ] && [ -f "$ROOT_DIR/local.properties" ]; then
  SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | head -1 | sed 's/\\:/:/g; s/\\\\/\\/g')"
fi
if [ -z "$SDK_DIR" ] || [ ! -d "$SDK_DIR" ]; then
  echo "FAIL: Android SDK를 찾을 수 없습니다. ANDROID_HOME 또는 local.properties를 확인하세요." >&2
  exit 1
fi

if [ -d "$SDK_DIR/build-tools/$EXPECTED_BUILD_TOOLS" ]; then
  BUILD_TOOLS_VERSION="$EXPECTED_BUILD_TOOLS"
  BUILD_TOOLS_DIR="$SDK_DIR/build-tools/$EXPECTED_BUILD_TOOLS"
else
  BUILD_TOOL_VERSIONS=""
  for candidate in "$SDK_DIR"/build-tools/*; do
    [ -d "$candidate" ] || continue
    BUILD_TOOL_VERSIONS="${BUILD_TOOL_VERSIONS}$(basename "$candidate")\n"
  done
  BUILD_TOOLS_VERSION="$(printf '%b' "$BUILD_TOOL_VERSIONS" | sed '/^$/d' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)"
  BUILD_TOOLS_DIR="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION"
fi
if [ ! -d "$BUILD_TOOLS_DIR" ]; then
  echo "FAIL: Android build-tools를 찾을 수 없습니다." >&2
  exit 1
fi
BUILD_TOOLS_MAJOR="${BUILD_TOOLS_VERSION%%.*}"
case "$BUILD_TOOLS_MAJOR" in
  ''|*[!0-9]*)
    echo "FAIL: Android build-tools 버전을 해석할 수 없습니다: $BUILD_TOOLS_VERSION" >&2
    exit 1
    ;;
esac
if [ "$BUILD_TOOLS_MAJOR" -lt 35 ]; then
  echo "FAIL: 16 KiB APK 검증에는 Android build-tools 35 이상이 필요합니다. found=$BUILD_TOOLS_VERSION" >&2
  exit 1
fi

AAPT="$BUILD_TOOLS_DIR/aapt"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
ADB="$SDK_DIR/platform-tools/adb"
if [ ! -x "$ADB" ] && command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
fi
for tool in "$AAPT" "$APKSIGNER" "$ZIPALIGN" "$ADB"; do
  if [ ! -x "$tool" ]; then
    echo "FAIL: 필요한 Android 도구가 없습니다: $tool" >&2
    exit 1
  fi
done

EXPECTED_RELEASE_CERT=""
if [ "$VARIANT" = "release" ]; then
  SIGNING_REPORT="$("$GRADLEW" -p "$ROOT_DIR" -q :app:signingReport)"
  RELEASE_CONFIG="$(printf '%s\n' "$SIGNING_REPORT" | awk '/^Variant: release$/ { release=1; next } release && /^Config:/ { print $2; exit }')"
  RELEASE_ALIAS="$(printf '%s\n' "$SIGNING_REPORT" | awk '/^Variant: release$/ { release=1; next } release && /^Alias:/ { print $2; exit }')"
  if [ -z "$RELEASE_CONFIG" ] || [ "$RELEASE_CONFIG" = "null" ]; then
    echo "FAIL: release 서명이 설정되지 않았습니다. 빌드 전에 서명 설정을 준비하세요." >&2
    echo "docs/APK_INSTALLATION.md의 keystore.properties 또는 환경 변수 절차를 따르세요." >&2
    exit 1
  fi
  if [ "$RELEASE_ALIAS" = "AndroidDebugKey" ]; then
    echo "FAIL: Android debug key는 release 서명으로 사용할 수 없습니다." >&2
    exit 1
  fi
  RELEASE_CERT_FILE="$ROOT_DIR/config/jarvis-release-cert.sha256"
  EXPECTED_RELEASE_CERT="$(grep -E '^[0-9A-Fa-f:]{64,95}$' "$RELEASE_CERT_FILE" 2>/dev/null | head -1 | tr '[:upper:]' '[:lower:]' | tr -d ':' || true)"
  if [ "${#EXPECTED_RELEASE_CERT}" -ne 64 ]; then
    echo "FAIL: 고정 release 인증서 SHA-256이 설정되지 않았습니다: $RELEASE_CERT_FILE" >&2
    echo "release key 인증서 fingerprint를 이 공개 설정 파일에 기록한 뒤 커밋하세요." >&2
    exit 1
  fi
  RELEASE_REPORT_CERT="$(printf '%s\n' "$SIGNING_REPORT" | awk '
    /^Variant: release$/ { release=1; next }
    release && /^SHA-256:/ { sub(/^SHA-256:[[:space:]]*/, ""); print; exit }
    release && /^----------$/ { exit }
  ' | tr '[:upper:]' '[:lower:]' | tr -d ':')"
  if [ "${#RELEASE_REPORT_CERT}" -ne 64 ]; then
    echo "FAIL: release signingReport에서 인증서 SHA-256을 읽지 못했습니다." >&2
    exit 1
  fi
  if [ "$RELEASE_REPORT_CERT" != "$EXPECTED_RELEASE_CERT" ]; then
    echo "FAIL: 설정된 release key가 고정 fingerprint와 다릅니다. 빌드를 시작하지 않았습니다." >&2
    echo "expected=$EXPECTED_RELEASE_CERT actual=$RELEASE_REPORT_CERT" >&2
    exit 1
  fi
fi

"$GRADLEW" -p "$ROOT_DIR" "$GRADLE_TASK"

OUTPUT_METADATA="$OUTPUT_DIR/output-metadata.json"
if [ ! -f "$OUTPUT_METADATA" ]; then
  echo "FAIL: 현재 $VARIANT 빌드의 output-metadata.json이 없습니다." >&2
  exit 1
fi
OUTPUT_FILES="$(sed -n 's/.*"outputFile"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$OUTPUT_METADATA")"
OUTPUT_COUNT="$(printf '%s\n' "$OUTPUT_FILES" | sed '/^$/d' | wc -l | tr -d ' ')"
if [ "$OUTPUT_COUNT" -ne 1 ]; then
  echo "FAIL: $VARIANT APK 산출물이 정확히 하나가 아닙니다. count=$OUTPUT_COUNT" >&2
  exit 1
fi
APK_PATH="$OUTPUT_DIR/$(printf '%s\n' "$OUTPUT_FILES" | head -1)"
if [ ! -f "$APK_PATH" ]; then
  echo "FAIL: metadata가 가리키는 APK가 없습니다: $APK_PATH" >&2
  exit 1
fi

if ! "$APKSIGNER" verify --verbose "$APK_PATH" >/dev/null; then
  if [ "$VARIANT" = "release" ]; then
    echo "FAIL: release APK 서명 검증에 실패했습니다." >&2
  else
    echo "FAIL: debug APK 서명 검증에 실패했습니다." >&2
  fi
  exit 1
fi
if ! "$ZIPALIGN" -c -P 16 4 "$APK_PATH" >/dev/null; then
  echo "FAIL: APK가 16 KiB page alignment 검증을 통과하지 못했습니다." >&2
  exit 1
fi

BADGING="$($AAPT dump badging "$APK_PATH")"
MANIFEST_TREE="$($AAPT dump xmltree "$APK_PATH" AndroidManifest.xml)"
if ! grep -q "package: name='$PACKAGE_NAME'" <<<"$BADGING"; then
  echo "FAIL: 예상하지 않은 APK package입니다." >&2
  exit 1
fi
if ! grep -q "sdkVersion:'$EXPECTED_MIN_SDK'" <<<"$BADGING"; then
  echo "FAIL: APK minSdk가 $EXPECTED_MIN_SDK가 아닙니다." >&2
  exit 1
fi
if ! grep -q "targetSdkVersion:'$EXPECTED_TARGET_SDK'" <<<"$BADGING"; then
  echo "FAIL: APK targetSdk가 $EXPECTED_TARGET_SDK가 아닙니다." >&2
  exit 1
fi
NATIVE_CODE_LINE="$(sed -n '/^native-code:/p' <<<"$BADGING")"
if [ "$NATIVE_CODE_LINE" != "native-code: 'arm64-v8a'" ]; then
  echo "FAIL: APK native code가 arm64-v8a 전용이 아닙니다: ${NATIVE_CODE_LINE:-missing}" >&2
  exit 1
fi
if ! grep -q "launchable-activity: name='$PACKAGE_NAME.MainActivity'" <<<"$BADGING"; then
  echo "FAIL: Jarvis MainActivity launcher가 없습니다." >&2
  exit 1
fi
if [ "$VARIANT" = "release" ]; then
  if grep -q '^application-debuggable$' <<<"$BADGING"; then
    echo "FAIL: release APK가 debuggable입니다." >&2
    exit 1
  fi
  if grep -q 'com\.personal\.jarvis\.debug\.' <<<"$MANIFEST_TREE"; then
    echo "FAIL: release APK manifest에 debug component가 포함됐습니다." >&2
    exit 1
  fi
else
  if ! grep -q '^application-debuggable$' <<<"$BADGING"; then
    echo "FAIL: debug APK에 debuggable flag가 없습니다." >&2
    exit 1
  fi
  if ! grep -q 'com\.personal\.jarvis\.debug\.JarvisDeveloperMenuActivity' <<<"$MANIFEST_TREE"; then
    echo "FAIL: debug APK에 개발자 메뉴가 없습니다." >&2
    exit 1
  fi
fi

VERSION_NAME="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING")"
VERSION_CODE="$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" <<<"$BADGING")"
METADATA_VERSION_NAME="$(sed -n 's/.*"versionName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$OUTPUT_METADATA" | head -1)"
METADATA_VERSION_CODE="$(sed -n 's/.*"versionCode"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$OUTPUT_METADATA" | head -1)"
if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ] ||
  [ "$VERSION_NAME" != "$METADATA_VERSION_NAME" ] || [ "$VERSION_CODE" != "$METADATA_VERSION_CODE" ]; then
  echo "FAIL: APK version과 현재 build metadata가 일치하지 않습니다." >&2
  exit 1
fi

CERT_SHA256="$("$APKSIGNER" verify --print-certs "$APK_PATH" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1 | tr '[:upper:]' '[:lower:]' | tr -d ':')"
if [ "$VARIANT" = "release" ] && [ "$CERT_SHA256" != "$EXPECTED_RELEASE_CERT" ]; then
  echo "FAIL: release APK 인증서가 고정 fingerprint와 다릅니다." >&2
  echo "expected=$EXPECTED_RELEASE_CERT actual=$CERT_SHA256" >&2
  exit 1
fi

DIST_DIR="$ROOT_DIR/dist"
DIST_BASENAME="Jarvis-${VERSION_NAME}-${VERSION_CODE}-${VARIANT}-arm64.apk"
DIST_APK="$DIST_DIR/$DIST_BASENAME"
mkdir -p "$DIST_DIR"
cp "$APK_PATH" "$DIST_APK"
if command -v shasum >/dev/null 2>&1; then
  (cd "$DIST_DIR" && shasum -a 256 "$DIST_BASENAME" >"$DIST_BASENAME.sha256")
elif command -v sha256sum >/dev/null 2>&1; then
  (cd "$DIST_DIR" && sha256sum "$DIST_BASENAME" >"$DIST_BASENAME.sha256")
else
  echo "FAIL: shasum 또는 sha256sum이 필요합니다." >&2
  exit 1
fi

echo "PASS: APK 빌드 및 검증 완료"
echo "  package=$PACKAGE_NAME version=$VERSION_NAME($VERSION_CODE) variant=$VARIANT"
echo "  certificate_sha256=$CERT_SHA256"
echo "  apk=$DIST_APK"
echo "  sha256=$(cut -d ' ' -f 1 "$DIST_APK.sha256")"

if [ "$ACTION" != "install" ]; then
  exit 0
fi

if ! ADB_DEVICES="$("$ADB" devices 2>&1)"; then
  echo "FAIL: ADB 서버를 시작하거나 기기 목록을 읽지 못했습니다." >&2
  printf '%s\n' "$ADB_DEVICES" >&2
  exit 1
fi
if [ -z "$REQUESTED_SERIAL" ]; then
  DEVICE_LIST="$(printf '%s\n' "$ADB_DEVICES" | awk 'NR > 1 && $2 == "device" { print $1 }')"
  DEVICE_COUNT="$(printf '%s\n' "$DEVICE_LIST" | sed '/^$/d' | wc -l | tr -d ' ')"
  if [ "$DEVICE_COUNT" -ne 1 ]; then
    echo "FAIL: 사용 가능한 ADB 기기가 ${DEVICE_COUNT}개입니다. USB 연결/승인 또는 serial을 확인하세요." >&2
    printf '%s\n' "$ADB_DEVICES" >&2
    exit 1
  fi
  REQUESTED_SERIAL="$(printf '%s\n' "$DEVICE_LIST" | head -1)"
fi
if ! "$ADB" -s "$REQUESTED_SERIAL" get-state >/dev/null 2>&1; then
  echo "FAIL: ADB 기기 '$REQUESTED_SERIAL'에 연결할 수 없습니다. USB 연결과 디버깅 승인을 확인하세요." >&2
  printf '%s\n' "$ADB_DEVICES" >&2
  exit 1
fi

if ! DEVICE_SDK="$("$ADB" -s "$REQUESTED_SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"; then
  echo "FAIL: 기기의 Android API 수준을 읽지 못했습니다. 연결 상태를 확인하세요." >&2
  exit 1
fi
if ! DEVICE_ABIS="$("$ADB" -s "$REQUESTED_SERIAL" shell getprop ro.product.cpu.abilist 2>/dev/null | tr -d '\r')"; then
  echo "FAIL: 기기의 ABI 정보를 읽지 못했습니다. 연결 상태를 확인하세요." >&2
  exit 1
fi
if [ -z "$DEVICE_SDK" ] || [ "$DEVICE_SDK" -lt "$EXPECTED_MIN_SDK" ]; then
  echo "FAIL: Android 8.0(API $EXPECTED_MIN_SDK) 이상 기기가 필요합니다. deviceSdk=${DEVICE_SDK:-unknown}" >&2
  exit 1
fi
if [[ ",$DEVICE_ABIS," != *,arm64-v8a,* ]]; then
  echo "FAIL: arm64-v8a 기기가 아닙니다. deviceAbis=$DEVICE_ABIS" >&2
  exit 1
fi

INSTALLED_DUMP="$("$ADB" -s "$REQUESTED_SERIAL" shell dumpsys package "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' || true)"
INSTALLED_VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode=\([0-9][0-9]*\).*/\1/p' <<<"$INSTALLED_DUMP" | head -1)"
if [ "$VARIANT" = "release" ] && [ -n "$INSTALLED_VERSION_CODE" ] && [ "$INSTALLED_VERSION_CODE" -ge "$VERSION_CODE" ]; then
  echo "FAIL: release 업데이트는 설치본보다 큰 versionCode가 필요합니다." >&2
  echo "installed=$INSTALLED_VERSION_CODE apk=$VERSION_CODE. versionCode를 올리세요." >&2
  exit 1
fi
if [ "$VARIANT" = "debug" ] && [ -n "$INSTALLED_VERSION_CODE" ] && [ "$INSTALLED_VERSION_CODE" -gt "$VERSION_CODE" ]; then
  echo "FAIL: 설치된 versionCode=$INSTALLED_VERSION_CODE가 APK versionCode=$VERSION_CODE보다 높습니다." >&2
  echo "업데이트용 APK의 versionCode를 올리세요. 자동 downgrade는 수행하지 않습니다." >&2
  exit 1
fi

if ! INSTALL_OUTPUT="$("$ADB" -s "$REQUESTED_SERIAL" install -r "$DIST_APK" 2>&1)"; then
  echo "$INSTALL_OUTPUT" >&2
  case "$INSTALL_OUTPUT" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      echo "FAIL: 기존 앱과 서명이 다릅니다. 기존 release 키를 사용하세요. 자동 uninstall은 수행하지 않았습니다." >&2
      ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*)
      echo "FAIL: APK versionCode가 설치본보다 낮습니다. versionCode를 올리세요." >&2
      ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
      echo "FAIL: 기기 저장 공간이 부족합니다." >&2
      ;;
    *INSTALL_FAILED_USER_RESTRICTED*|*unauthorized*)
      echo "FAIL: 폰 잠금을 해제하고 USB 디버깅/USB 설치 승인 창을 허용하세요." >&2
      ;;
    *INSTALL_PARSE_FAILED*)
      echo "FAIL: APK가 손상됐거나 호환되지 않습니다. SHA-256과 전송 파일을 확인하세요." >&2
      ;;
    *)
      echo "FAIL: APK 설치에 실패했습니다. 위 ADB 오류를 확인하세요." >&2
      ;;
  esac
  echo "데이터 보호를 위해 자동 uninstall은 수행하지 않았습니다." >&2
  exit 1
fi
echo "$INSTALL_OUTPUT"

if ! POST_DUMP="$("$ADB" -s "$REQUESTED_SERIAL" shell dumpsys package "$PACKAGE_NAME" 2>/dev/null | tr -d '\r')"; then
  echo "FAIL: 설치 후 package 정보를 읽지 못했습니다. 기기 연결을 확인하세요." >&2
  exit 1
fi
POST_VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode=\([0-9][0-9]*\).*/\1/p' <<<"$POST_DUMP" | head -1)"
POST_VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName=\(.*\)/\1/p' <<<"$POST_DUMP" | head -1)"
if [ "$POST_VERSION_CODE" != "$VERSION_CODE" ] || [ "$POST_VERSION_NAME" != "$VERSION_NAME" ]; then
  echo "FAIL: 설치 후 버전이 APK와 다릅니다. installed=$POST_VERSION_NAME($POST_VERSION_CODE)" >&2
  exit 1
fi

if ! "$ADB" -s "$REQUESTED_SERIAL" shell pm path "$PACKAGE_NAME"; then
  echo "FAIL: 설치된 APK 경로를 확인하지 못했습니다." >&2
  exit 1
fi
if ! START_OUTPUT="$("$ADB" -s "$REQUESTED_SERIAL" shell am start -W -n "$PACKAGE_NAME/.MainActivity" 2>&1)"; then
  echo "FAIL: Jarvis MainActivity 실행 명령에 실패했습니다." >&2
  printf '%s\n' "$START_OUTPUT" >&2
  exit 1
fi
echo "$START_OUTPUT"
if ! grep -q 'Status: ok' <<<"$START_OUTPUT"; then
  echo "FAIL: Jarvis MainActivity 시작 확인에 실패했습니다." >&2
  exit 1
fi
echo "PASS: $REQUESTED_SERIAL 기기에 $VERSION_NAME($VERSION_CODE)를 설치하고 Jarvis 메인 화면을 실행했습니다."

# Jarvis APK 설치 및 배포

## 지원 범위

- 앱 버전: `1.0.0` (`versionCode=2`)
- Android: 8.0(API 26) 이상
- CPU: `arm64-v8a`
- package: `com.personal.jarvis`
- APK 크기: 음성 모델과 native library 포함으로 약 178 MiB
- 도구: Android SDK Build Tools 35 이상(`zipalign -P 16` 지원)

## 개발·실기기 검증용 APK

저장소 루트에서 다음 명령으로 debug APK를 빌드한다.

```bash
./scripts/jarvis-apk.sh build debug
```

배포 파일명은 `Jarvis-<versionName>-<versionCode>-<variant>-arm64.apk` 형식이다. 같은 버전명 안에서도 빌드를 구분할 수 있도록 `versionCode`를 포함한다.

스크립트는 다음을 자동 확인한다.

1. Gradle `assembleDebug` 성공
2. APK 서명 유효성
3. package, version, `arm64-v8a` 메타데이터
4. `dist/Jarvis-1.0.0-2-debug-arm64.apk` 복사
5. minSdk/targetSdk/launcher/debuggable/debug component/16 KiB alignment 검증
6. 같은 경로의 `.sha256` 체크섬 생성

체크섬 파일에는 이식 가능한 APK 파일명만 기록된다. APK와 체크섬이 같은 폴더에 있을 때 다음처럼 확인한다.

```bash
# macOS
shasum -a 256 -c Jarvis-1.0.0-2-debug-arm64.apk.sha256

# Rocky Linux
sha256sum -c Jarvis-1.0.0-2-debug-arm64.apk.sha256
```

연결된 기기가 하나면 다음 명령으로 빌드, 설치, 설치 버전 확인과 메인 Activity 실행까지 수행한다.

```bash
./scripts/jarvis-apk.sh install debug
```

기기가 여러 개면 serial을 지정한다.

```bash
./scripts/jarvis-apk.sh install debug <adb-serial>
```

설치는 `adb install -r`만 사용한다. 서명이 다른 기존 앱 때문에 업데이트에 실패해도 자동 uninstall하지 않는다. 앱을 삭제하면 등록한 소유자 목소리와 명령별 맞춤 발음 샘플도 함께 삭제되기 때문이다.

## 폰에서 APK 파일로 직접 설치

1. APK와 `.sha256` 파일을 함께 전달하고 체크섬이 일치하는지 확인한다.
2. APK를 폰의 파일 앱에서 연다.
3. Android가 요청하면 해당 파일 앱에 대해 `알 수 없는 앱 설치`를 이번 설치에만 허용한다.
4. 설치가 끝나면 Jarvis를 실행한다.
5. 앱의 `시작하기` 화면에서 마이크, 내 목소리, 접근성 순서로 필수 설정을 완료한다.
6. 전원 버튼으로 호출하려면 Jarvis를 기본 어시스턴트로 선택한다.

Xiaomi HyperOS에서 접근성 화면에 `앱의 액세스가 거부됨`이 보이면 `앱 정보 → 우측 상단 메뉴 → 제한된 설정 허용`을 먼저 확인한다. 자동 시작과 배터리 제한은 앱의 `설정 관리 → 앱 시스템 설정`에서 확인한다.

## 사용자 배포용 release APK

debug APK에는 디버깅 플래그와 ADB 테스트 진입점이 있으므로 최종 사용자에게 배포하지 않는다. release 서명 키는 저장소 밖에 보관하고 같은 키를 모든 업데이트에 계속 사용해야 한다. 키를 잃으면 기존 설치본을 업데이트할 수 없다.

다음 값은 환경 변수, 사용자 홈의 `~/.gradle/gradle.properties`, 또는 저장소 루트의 Git 제외 파일 `keystore.properties`로 전달한다. 비밀번호를 저장소의 tracked `gradle.properties`나 문서에 기록하면 안 된다.

```text
JARVIS_RELEASE_STORE_FILE=/absolute/path/to/jarvis-release.jks
JARVIS_RELEASE_STORE_PASSWORD=...
JARVIS_RELEASE_KEY_ALIAS=...
JARVIS_RELEASE_KEY_PASSWORD=...
```

`keystore.properties`를 쓸 때도 같은 `KEY=VALUE` 형식을 사용한다. 이 파일과 keystore 원본은 암호화된 별도 저장소와 오프라인 매체에 백업한다.

최초 production key를 확정한 뒤 인증서 SHA-256을 `config/jarvis-release-cert.sha256`에 기록해 커밋한다. 인증서 fingerprint는 비밀이 아니며, 설치 업데이트 계보를 고정하는 값이다. 스크립트는 release alias가 `AndroidDebugKey`이거나 Gradle `signingReport`의 인증서가 고정값과 다르면 APK 빌드 전에 중단하고, 완성된 APK 인증서도 다시 검증한다.

서명 값이 준비된 환경에서 다음 명령을 실행한다.

```bash
./scripts/jarvis-apk.sh build release
```

서명 설정이나 고정 fingerprint가 없으면 스크립트는 큰 모델을 다시 패키징하기 전에 실패한다. 서명되지 않은 release APK도 배포 파일로 만들지 않는다. `*.jks`, `*.keystore`, `keystore.properties`, `dist/`는 Git에서 제외한다.

release 배포 전 확인 항목:

- `apksigner verify` 성공
- package/version/minSdk/targetSdk/ABI 확인
- release manifest에 `.debug.*` Activity/Service와 개발자 메뉴가 없는지 확인
- release latency 로그에서 실제 인식 문장과 후보가 `redacted` 처리되는지 확인
- 기존 release 서명 설치본에 `install -r` 업데이트 성공
- 모든 배포에서 `versionCode` 증가 확인. release 설치는 기기 설치본과 같은 `versionCode`도 자동 차단한다.
- 마이크, 접근성, 기본 어시스턴트, 배터리 설정 온보딩 실기기 확인

## 모델 무결성과 재현성

한국어 streaming ASR 모델 4개는 다운로드 시 파일 크기와 SHA-256을 모두 검증한다. 모델이 바뀌면 출처의 새 checksum을 검증한 뒤 `app/build.gradle.kts`의 크기와 SHA-256을 함께 갱신한다.

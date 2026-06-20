# Jarvis Project Spec

Jarvis는 개인 Android 폰을 음성으로 제어하기 위한 개인 비서 앱이다. 첫 번째 목표는 Xiaomi 15 Ultra의 기본 카메라 앱을 열고, 음성 명령으로 셔터/필터/전환 같은 조작을 수행하는 것이다.

이 문서는 README보다 상위의 기준 문서다. 이후 기능을 추가할 때는 이 문서를 먼저 읽고, 구현이 바뀌면 함께 갱신한다.

## 1. Current Status

작성일: 2026-06-20

현재 상태:

- Android/Kotlin 프로젝트 scaffold 완료
- 음성 서비스 1차 구현 완료
- 접근성 서비스 1차 구현 완료
- 기본 카메라 실행 helper 구현 완료
- sherpa-onnx + 3D-Speaker CAM++ 기반 소유자 목소리 등록 UI 및 embedding 저장 구현 완료
- 등록된 소유자 embedding이 있을 때 명령 인식 전 owner voice gate 1차 구현 완료
- 재부팅/앱 업데이트 후 Jarvis 시작 알림을 띄우는 boot receiver 구현 완료
- README 설치 문서 작성 완료
- 로컬 Debug 빌드는 `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` 지정 시 성공 확인
- Xiaomi 15 Ultra USB 연결 상태에서 Debug APK 재설치 성공 확인
- 앱 업데이트 후 `Jarvis 대기 준비됨` 시작 알림 표시 확인
- Xiaomi 15 Ultra에서 `JarvisVoiceService` foreground 실행, 접근성 서비스 바인딩, 배터리 최적화 예외 등록 확인
- 2026-06-20 21:26 KST 기준 약 15분 유지 테스트에서 프로세스, foreground notification, 접근성 바인딩, owner voice verification loop 유지 확인
- `헤이 자비스` wake-only 발화 후 확인음/명령 대기 알림을 제공하고, 인증 window 안에서는 호출어 없는 후속 명령을 허용하도록 보정
- wake-only 후속 명령 인식 지연을 줄이기 위해 다음 listening 예약을 25ms로 낮추고, 인증 window 안의 `SpeechRecognizer` silence timeout을 단축
- owner voice gate 대기 중 `AudioRecord`를 계속 열어 두고 rolling 2.5초 window를 500ms마다 검증하도록 변경해 Android 마이크 표시 깜빡임을 줄임

다음 우선순위:

1. Xiaomi 15 Ultra에서 내 목소리 등록 후 threshold 실측
2. owner voice gate 통과 후 명령 인식 UX 보정
3. 재부팅 후 시작 알림 및 HyperOS 자동 시작/배터리 설정 실기기 검증
4. 실제 카메라 화면에서 셔터/필터/전환 좌표 보정

## 2. Quick Context For Future Work

다음 작업자는 먼저 이 순서로 확인한다.

1. `README.md`: 설치와 실행 절차 확인
2. `docs/PROJECT_SPEC.md`: 목표, 제약, 명령 모델 확인
3. `app/src/main/java/com/personal/jarvis/CommandBus.kt`: 현재 내부 명령 확인
4. `app/src/main/java/com/personal/jarvis/CommandInterpreter.kt`: 한국어 음성 문장 매핑 확인
5. `app/src/main/java/com/personal/jarvis/JarvisAccessibilityService.kt`: 실제 화면 조작 로직 확인

작업 원칙:

- 명령을 추가하면 `CommandBus`, `CommandInterpreter`, 실행 서비스, README, 이 문서를 함께 갱신한다.
- 권한을 추가하면 `AndroidManifest.xml`, README, 이 문서의 Permissions 섹션을 함께 갱신한다.
- Xiaomi 카메라 UI 좌표나 키워드를 바꾸면 Accessibility Automation Strategy 섹션에 기록한다.
- 보안 우회, 무단 조작, 사용자 허가 없는 백그라운드 제어는 프로젝트 목표에서 제외한다.

## 3. Product Goal

### 최종 목표

사용자가 직접 화면을 누르기 어려운 상황에서 Jarvis가 음성 명령을 받아 Android 폰의 주요 앱과 시스템 동작을 대신 수행한다.

### 첫 번째 목표

기본 카메라 앱을 대상으로 다음 동작을 지원한다.

- `자비스, 카메라 열어`: 기본 카메라 앱 실행
- `헤이 자비스, 카메라 셀피 모드로 실행해`: 기본 카메라 앱을 전면 카메라 힌트와 함께 실행
- `자비스, 찍어`: 현재 화면에서 셔터 버튼 탭
- `자비스, 카메라 찍어`: 카메라 앱 실행 후 셔터 탭
- `자비스, 필터`: 카메라 앱 필터/효과 UI 열기
- `자비스, 전면 카메라`: 전후면 카메라 전환
- `자비스, 카메라 종료해`: 카메라 앱에서 홈으로 이동
- `자비스, 뒤로`: Android 뒤로가기
- `자비스, 홈`: Android 홈으로 이동
- `자비스, 멈춰`: 음성 인식 서비스 중지

### 비목표

현재 단계에서는 다음을 목표로 하지 않는다.

- Xiaomi 기본 카메라의 Leica 필터나 내부 이미지 파이프라인을 API로 직접 호출
- Google Play 공개 배포
- 은행앱/보안화면/권한화면 자동 우회
- 백그라운드에서 무제한으로 항상 마이크를 사용하는 완전 상시 대기
- Android 정책을 우회해서 부팅 직후 마이크 foreground service를 몰래 시작하는 방식
- 루팅, Shizuku, ADB 권한을 전제로 한 구현

## 4. Target Device

- 주 사용 기기: Xiaomi 15 Ultra
- OS: Android 계열 HyperOS
- 주요 대상 앱: Xiaomi 기본 카메라 앱
- 패키지 추정값: `com.android.camera`

기기별 UI와 좌표는 달라질 수 있다. 접근성 노드 탐색이 실패하면 좌표 fallback을 사용하므로, 실기기 테스트 후 보정이 필요하다.

## 5. Design Principles

- 기본 카메라 품질을 유지한다. 직접 카메라 앱을 만들기보다 Xiaomi 기본 카메라를 우선 사용한다.
- 가능한 공식 Android API를 먼저 사용한다. 다른 앱 제어는 접근성 서비스 범위 안에서만 한다.
- 사용자 허가가 필요한 권한은 앱 안에서 명확하게 드러낸다.
- 자동화는 앱별 레시피로 확장한다. 카메라 레시피가 안정화되면 갤러리, 설정, 전화, 메시지 등으로 확장한다.
- 보안 우회를 목표로 하지 않는다. 민감 화면에서는 동작하지 않는 것을 정상 동작으로 본다.

## 6. Android Constraints

Android 일반 앱은 다른 앱의 내부 버튼이나 기능을 직접 제어할 수 없다. 기본 카메라의 셔터/필터 버튼도 외부 앱용 API가 아니다.

Jarvis는 다음 조합으로 동작한다.

- Foreground Service: 마이크 기반 음성 인식 유지
- SpeechRecognizer: 한국어 음성 명령 인식
- AccessibilityService: 화면 노드 탐색, 클릭, 전역 동작 수행
- Intent: 기본 카메라 앱 실행
- BroadcastReceiver: 재부팅 또는 앱 업데이트 후 Jarvis 시작 알림 표시

접근성 서비스는 사용자가 설정에서 직접 켜야 하며, 설치만으로 자동 활성화할 수 없다.

전면 카메라 실행은 Android 카메라 인텐트에 전면 렌즈 힌트 extra를 넣어 시도한다. 이 extra는 카메라 앱 구현에 따라 무시될 수 있으므로, Xiaomi 기본 카메라가 힌트를 무시하면 카메라를 연 뒤 접근성 전환 명령을 조합하는 fallback이 필요하다.

Android 14(API 34)+에서 `RECORD_AUDIO`는 while-in-use 권한으로 취급된다. 현재 앱은 `targetSdk=35`이므로 `BOOT_COMPLETED` receiver에서 microphone foreground service를 직접 시작할 수 없다. 부팅 후 자동 대기는 다음 구조로 처리한다.

1. `JarvisBootReceiver`가 `BOOT_COMPLETED` 또는 `MY_PACKAGE_REPLACED`를 받는다.
2. receiver는 마이크 서비스를 직접 시작하지 않고 `Jarvis 대기 준비됨` 알림을 띄운다.
3. 사용자가 알림의 `Jarvis 시작`을 누르면 notification interaction으로 `JarvisVoiceService`를 foreground service로 시작한다.
4. 한 번 시작된 뒤에는 foreground notification과 `START_STICKY`로 계속 대기한다.

완전한 “부팅 직후 무터치 마이크 대기”는 일반 앱 권한만으로는 신뢰할 수 없다. 장기적으로는 기본 Assistant/VoiceInteractionService 역할, device-owner/system app, 또는 사용자가 명시적으로 실행한 foreground session을 유지하는 방식 중 하나를 검토한다.

## 7. Current Architecture

```text
사용자 음성
  ↓
JarvisVoiceService
  ↓
CommandInterpreter
  ↓
CommandBus
  ↓
JarvisAccessibilityService
  ↓
화면 노드 탐색 또는 좌표 탭
```

### Components

| File | Role |
| --- | --- |
| `MainActivity.kt` | 권한 요청, 접근성 설정 진입, Jarvis 시작/중지 UI |
| `JarvisBootReceiver.kt` | 부팅/앱 업데이트 후 Jarvis 시작 알림 표시 |
| `JarvisVoiceService.kt` | 포그라운드 음성 인식 서비스 |
| `OwnerVoiceEngine.kt` | sherpa-onnx speaker embedding 생성, 녹음, cosine 검증 |
| `OwnerVoiceStore.kt` | 소유자 음성 embedding 저장 |
| `CommandInterpreter.kt` | 인식된 문장을 내부 명령으로 변환 |
| `CommandBus.kt` | 앱 내부 명령 브로드캐스트 |
| `JarvisAccessibilityService.kt` | 접근성 기반 UI 탐색/탭/전역 동작 |
| `CameraLauncher.kt` | 기본 카메라 앱 실행 |
| `AndroidManifest.xml` | 권한, 서비스, 접근성 메타데이터 선언 |
| `res/xml/jarvis_accessibility_service.xml` | 접근성 서비스 설정 |

## 8. Command Model

내부 명령은 문자열 상수로 관리한다.

| Command | Meaning |
| --- | --- |
| `open_camera` | 기본 카메라 앱 열기 |
| `open_front_camera` | 기본 카메라 앱을 전면 카메라 힌트와 함께 열기 |
| `open_camera_and_take_photo` | 카메라 앱 열고 잠시 후 촬영 |
| `take_photo` | 현재 화면에서 셔터 탭 |
| `open_filters` | 필터/효과 UI 열기 |
| `switch_camera` | 전후면 카메라 전환 |
| `back` | 뒤로가기 |
| `home` | 홈으로 이동 |
| `stop_listening` | Jarvis 음성 서비스 중지 |

`카메라 종료해`, `카메라 닫아`, `카메라 꺼`, `카메라 나가` 같은 표현은 현재 카메라 앱을 직접 kill하지 않고 `home` 명령으로 매핑한다.

모든 명령은 `자비스` 계열 호출어를 포함해야 한다. 현재 허용 호출어는 `자비스`, `자베스`, `쟈비스`, `제비스`, `차비스`, `jarvis`다. 이는 우발적인 반응을 줄이는 장치이며, 사용자 목소리 자체를 인증하는 speaker verification은 별도 기능으로 구현해야 한다.

예외: owner voice gate를 통과해 12초 인증 window가 열린 동안에는 이어지는 명령에서 호출어를 생략할 수 있다. 예를 들어 `헤이 자비스`만 먼저 말해 command window를 열고, 다음 발화로 `카메라 셀피 모드로 실행해`를 말할 수 있다. wake-only 발화가 인식되면 window를 다시 12초로 연장하고 25ms 후 다음 명령 인식을 시작한다.

## 8.1 Owner Voice Gate

소유자 목소리 인증은 오픈소스 `sherpa-onnx` Android 런타임과 3D-Speaker CAM++ speaker verification 모델을 사용한다.

- Runtime: `sherpa-onnx` 공식 Android AAR `v1.13.3`의 Kotlin API jar와 `arm64-v8a` native libraries
- Model: `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`
- 저장 방식: 소유자 embedding `FloatArray`를 little-endian bytes로 변환한 뒤 Base64 인코딩하여 앱 private `SharedPreferences`에 저장한다.
- 기본 허용 threshold는 `0.50`으로 시작하며 실기기 테스트 후 조정한다.
- 현재 APK는 Xiaomi 15 Ultra를 우선해 `arm64-v8a` ABI만 패키징한다.

현재 구현 흐름:

1. 사용자가 `내 목소리 등록 시작`을 누르고 조용한 환경에서 6초 동안 말한다.
2. `OwnerVoiceEngine`이 16kHz mono PCM을 녹음하고 sherpa-onnx로 speaker embedding을 계산한다.
3. 계산된 embedding을 `OwnerVoiceStore`에 저장한다.
4. 이후 `JarvisVoiceService`는 owner embedding이 있으면 owner gate 대기 중 `AudioRecord`를 계속 열어 둔다.
5. 최근 2.5초 rolling audio window에서 candidate embedding을 만들고 500ms마다 저장된 embedding과 cosine similarity를 비교한다.
6. similarity가 threshold 이상이면 `AudioRecord`를 닫고 12초 인증 window를 열어 `SpeechRecognizer` 명령 인식을 시작한다.
7. window 안에서 `헤이 자비스` 같은 wake-only 발화가 인식되면 확인음을 내고 command window를 유지한다.
8. window 안에서는 호출어 없는 명령도 허용하며, STT의 command-mode silence timeout을 더 짧게 사용한다.
9. 명령 처리 후 인증 window를 닫고 다시 소유자 확인 상태로 돌아간다.

제약:

- Android `SpeechRecognizer`는 텍스트 결과만 안정적으로 제공하고 원음 PCM을 제공하지 않는다.
- 따라서 현재 버전은 “한 문장을 동시에 speaker verification + STT 처리”하지 않고, 소유자 확인 후 짧은 명령 window를 여는 2단계 구조다.
- owner gate 대기 중에는 Android 상태바의 마이크 개인정보 표시가 켜져 있는 것이 정상이다. Android 정책상 앱이 이 표시를 숨길 수 없다.
- 빅스비처럼 자연스러운 단일 발화 UX를 만들려면 wake word, speaker verification, command recognition을 하나의 raw audio pipeline으로 재구성해야 한다.
- 현재 모델과 native library는 앱에 포함되며, 등록/검증 자체는 온디바이스에서 실행된다.

명령 추가 시 규칙:

1. `CommandBus.kt`에 내부 명령 상수를 추가한다.
2. `CommandInterpreter.kt`에 한국어 표현을 추가한다.
3. 실제 실행 위치를 정한다.
4. README와 이 명세서의 명령 목록을 갱신한다.

실행 위치 기준:

- 앱 실행/시스템 Intent: 전용 launcher/helper
- 화면 조작: `JarvisAccessibilityService`
- 음성 서비스 제어: `JarvisVoiceService`

## 9. Accessibility Automation Strategy

카메라 UI 제어는 2단계로 시도한다.

### 1단계: 접근성 노드 탐색

`text`, `contentDescription`, `viewIdResourceName`, `className`에서 키워드를 찾아 클릭 가능한 노드 또는 부모 노드를 클릭한다.

예상 키워드:

- 셔터: `shutter`, `capture`, `take photo`, `촬영`, `셔터`, `사진 찍기`, `拍照`
- 필터: `filter`, `effects`, `leica`, `필터`, `효과`, `색감`
- 전환: `switch camera`, `flip camera`, `전면`, `후면`, `카메라 전환`

### 2단계: 좌표 fallback

노드 탐색이 실패하면 화면 크기 기준 상대 좌표를 탭한다.

현재 portrait 기준:

- 셔터: `x=50%`, `y=88%`
- 필터: `x=25%`, `y=88%`
- 전환: `x=86%`, `y=14%`

실기기 테스트 후 Xiaomi 15 Ultra 기본 카메라 UI에 맞춰 보정한다.

## 10. Permissions

| Permission | Reason |
| --- | --- |
| `RECORD_AUDIO` | 음성 명령 인식 |
| `POST_NOTIFICATIONS` | Android 13+ 포그라운드 서비스 알림 |
| `FOREGROUND_SERVICE` | Jarvis 음성 서비스 실행 |
| `FOREGROUND_SERVICE_MICROPHONE` | Android 14+ 마이크 foreground service 선언 |
| `RECEIVE_BOOT_COMPLETED` | 재부팅 후 Jarvis 시작 알림 표시 |
| `BIND_ACCESSIBILITY_SERVICE` | 접근성 서비스 바인딩. 일반 런타임 권한이 아니며 시스템 설정에서 사용자가 직접 켜야 함 |

소유자 목소리 등록/검증은 기존 `RECORD_AUDIO` 권한을 사용한다. 별도 런타임 권한은 추가하지 않았다.

## 11. Installation Flow

개발 중 기본 설치 방법은 Android Studio 직접 설치다.

1. Android Studio에서 `E:\_task\jarvis` 열기
2. Xiaomi 폰 개발자 옵션 활성화
3. USB 디버깅 켜기
4. USB 연결 후 디버깅 허용
5. Android Studio에서 기기 선택 후 Run
6. Jarvis 앱 실행
7. 마이크/알림 권한 허용
8. 접근성 설정에서 Jarvis 서비스 활성화
9. 앱 정보 또는 HyperOS 보안 설정에서 자동 시작 허용 및 배터리 제한 해제
10. Jarvis 시작

APK 수동 설치도 가능하지만, 접근성 서비스는 반드시 사용자가 직접 켜야 한다.

## 12. Test Plan

### Build Test

- Android Studio Gradle Sync 성공
- Debug APK 빌드 성공
- Xiaomi 15 Ultra에 설치 성공

### Permission Test

- 마이크 권한 허용 상태가 UI에 표시됨
- 알림 권한 허용 상태가 UI에 표시됨
- 접근성 서비스 켜짐/꺼짐 상태가 UI에 표시됨
- 배터리 최적화/앱 정보 설정 화면으로 이동할 수 있음

### Voice Test

- `자비스` 호출어가 없는 `찍어`, `필터`, `뒤로`는 무시된다.
- `자비스, 카메라 열어`가 기본 카메라 앱을 연다.
- `헤이 자비스, 카메라 셀피 모드로 실행해`가 기본 카메라 앱을 전면 카메라 힌트와 함께 연다.
- `자비스, 찍어`가 카메라 화면에서 셔터를 누른다.
- 카메라 앱이 열리지 않은 상태에서 `자비스, 카메라 찍어`가 카메라를 열고 촬영을 시도한다.
- `자비스, 카메라 종료해`가 홈으로 이동한다.
- `자비스, 멈춰`가 음성 서비스를 중지한다.

### Camera Automation Test

- portrait 상태에서 셔터 탭 성공
- landscape 상태에서 셔터 탭 성공
- 전면/후면 전환 성공
- 필터 UI 열기 성공

테스트 실패 시 접근성 노드 덤프 또는 화면 좌표를 기준으로 `JarvisAccessibilityService.kt`의 키워드/fallback 좌표를 조정한다.

### Owner Voice Test

- `내 목소리 등록 시작` 후 6초 녹음 진행률이 올라간다.
- 등록 완료 시 owner embedding이 저장된다.
- owner embedding이 저장된 상태에서 Jarvis 시작 시 먼저 owner voice verification이 시작된다.
- 등록된 사용자 목소리 similarity가 threshold 이상이면 명령 인식 window가 열린다.
- 다른 사람 목소리는 threshold 미만으로 유지되어 명령 인식 window가 열리지 않아야 한다.
- owner gate 대기 중 Android 마이크 표시가 깜빡이는 대신 켜진 상태로 유지된다.

### Boot/Always-On Test

- `MY_PACKAGE_REPLACED` 또는 재부팅 후 `Jarvis 대기 준비됨` 알림이 표시된다.
- 알림의 `Jarvis 시작`을 누르면 `JarvisVoiceService` foreground notification이 표시된다.
- Android 정책상 `BOOT_COMPLETED`에서 microphone foreground service가 직접 시작되지 않는다.
- HyperOS 자동 시작 허용 및 배터리 제한 해제 후 장시간 대기 안정성을 확인한다.

## 13. Known Risks

- Xiaomi 카메라 앱 업데이트로 UI 키워드나 좌표가 바뀔 수 있다.
- SpeechRecognizer가 네트워크/Google 앱 상태에 영향을 받을 수 있다.
- 마이크 포그라운드 서비스는 배터리 최적화나 OS 정책에 의해 중단될 수 있다.
- owner gate에서 마이크를 계속 열어 두면 privacy indicator는 안정적이지만 배터리 사용량이 늘 수 있다.
- Android 14+는 부팅 receiver에서 microphone foreground service 시작을 제한한다. 현재 구현은 알림 탭을 통한 시작으로 우회가 아니라 정책 준수 경로를 사용한다.
- HyperOS는 추가 자동 시작/배터리 제한이 있어 사용자가 앱별 설정을 직접 조정해야 할 수 있다.
- speaker verification threshold `0.50`은 시작값이다. Xiaomi 15 Ultra 실측에서 본인/타인 점수 분포를 보고 조정해야 한다.
- ONNX 모델과 native library를 앱에 포함하므로 APK 크기가 커진다.
- 접근성 API를 자동화 비서로 쓰는 방식은 개인용 실험에는 적합하지만 Google Play 배포에는 정책 검토가 필요하다.
- 보안 화면에서는 접근성 노드가 가려지거나 동작이 차단될 수 있다.

## 14. Roadmap

### Phase 1: Camera MVP

- Android Studio 빌드 성공
- Xiaomi 15 Ultra 설치
- 접근성 서비스 활성화
- `찍어` 명령으로 기본 카메라 셔터 탭
- 셔터 좌표 보정

### Phase 2: Camera Control

- 필터 UI 열기 안정화
- 전후면 전환 안정화
- 타이머/연속 촬영 명령 추가
- 명령 인식 중복 실행 방지 강화
- 현재 실행 중인 앱이 카메라인지 판단 후 fallback 제한

### Phase 3: Assistant Core

- 명령 레시피 시스템 도입
- 규칙 기반 명령 매핑을 넘어서는 자연어 intent parser 도입 검토
- 앱 실행 명령 추가: 갤러리, 설정, 전화, 메시지
- 명령 로그 화면 추가
- 실패 원인 표시
- 사용자 정의 명령어 매핑

### Phase 4: Advanced Control

- 온디바이스 wake word 검토
- 더 안정적인 음성 인식 엔진 검토
- Shizuku/ADB 연동 가능성 검토
- 단, 루팅 의존은 기본 방향으로 삼지 않는다.

## 15. Change Policy

이 프로젝트를 이어서 작업할 때는 다음 순서를 따른다.

1. 이 문서를 먼저 읽는다.
2. 변경하려는 기능이 Product Goal과 Android Constraints에 맞는지 확인한다.
3. 명령을 추가하면 Command Model 섹션을 갱신한다.
4. 권한을 추가하면 Permissions 섹션을 갱신한다.
5. 실기기 테스트 결과로 좌표나 키워드를 바꾸면 Accessibility Automation Strategy에 기록한다.
6. 새 기능이 사용자에게 보이면 README도 갱신한다.

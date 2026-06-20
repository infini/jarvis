# Jarvis

개인 Android 비서 앱 실험 프로젝트입니다. 첫 목표는 Xiaomi 기본 카메라 앱을 열고, 음성 명령으로 셔터/필터/전환 같은 UI 조작을 자동화하는 것입니다.

장기 명세와 이어받기 기준은 [`docs/PROJECT_SPEC.md`](docs/PROJECT_SPEC.md)에 정리되어 있습니다. 기능을 추가할 때는 해당 문서를 먼저 갱신합니다.

## 현재 구조

- `JarvisVoiceService`: 마이크를 사용하는 포그라운드 서비스입니다. 소유자 인증, 명령 인식, 명령 실행 객체를 조합해 음성 상태 전환을 관리합니다.
- `OwnerVoiceGate`: 등록된 소유자 목소리를 확인하고 짧은 명령 window를 엽니다.
- `OwnerVoiceEngine`: sherpa-onnx와 3D-Speaker CAM++ 모델로 소유자 목소리 embedding을 만들고 검증합니다.
- `OwnerVoiceStore`: 등록된 소유자 목소리 embedding을 앱 private storage에 저장합니다.
- `OwnerVoiceEnrollmentController`: 앱 UI에서 실행되는 소유자 목소리 등록 workflow를 담당합니다.
- `LocalCommandRecognizer`: sherpa-onnx 한국어 streaming ASR 모델로 command window 안의 짧은 명령을 로컬에서 빠르게 인식합니다.
- `LocalCommandSession`: 로컬 명령 ASR 스레드와 fallback 상태를 관리합니다.
- `SpeechRecognitionIntentFactory`: Android `SpeechRecognizer` 실행 옵션을 한곳에서 생성합니다.
- `JarvisCommandExecutor`: 내부 명령 실행, 중복 실행 방지, 카메라 세션 window 유지 정책을 담당합니다.
- `JarvisNotificationController`: 음성 서비스 foreground notification과 상태 문구를 관리합니다.
- `JarvisFeedbackController`: 명령 가능/처리/실패 상태의 소리, 진동, 상태 broadcast를 담당합니다.
- `JarvisStateIndicatorController`: 접근성 overlay로 현재 Jarvis 상태를 화면 위에 표시합니다.
- `JarvisBootReceiver`: 재부팅 또는 앱 업데이트 후 Jarvis 시작 알림을 띄웁니다.
- `JarvisAccessibilityService`: 접근성 서비스 생명주기와 명령 수신을 담당합니다.
- `CameraAccessibilityController`: Xiaomi 기본 카메라의 셔터/필터/전후면 전환 자동화를 담당합니다.
- `AccessibilityNodeMatcher`: 접근성 노드 키워드 검색과 스코어링을 담당합니다.
- `ScreenController`: 짧은 wake lock으로 꺼진 화면을 깨웁니다.
- `MainActivity`: 마이크/알림 권한 요청, 접근성 설정 열기, Jarvis 시작/중지 UI입니다.

## 지원 명령 초안

- `자비스, 카메라 열어`
- `자비스, 카메라 실행`
- `헤이 자비스, 카메라 셀피 모드로 실행해`
- `자비스, 셀피`
- `자비스, 전면`
- `자비스, 셀피 모드`
- `자비스, 카메라 후면 모드로 실행해`
- `자비스, 후면`
- `자비스, 후면 모드`
- `자비스, 찍어`
- `자비스, 사진 찍기`
- `자비스, 카메라 찍어`
- `자비스, 필터`
- `자비스, 전면 카메라`
- `자비스, 카메라 전환`
- `자비스, 카메라 종료해`
- `자비스, 종료`
- `자비스, 화면 켜`
- `자비스, 화면 꺼`
- `자비스, 뒤로`
- `자비스, 홈`
- `자비스, 멈춰`

기본 명령에는 호출어 `자비스`가 필요합니다. 음성 인식이 흔들릴 수 있어 `자베스`, `쟈비스`, `제비스`, `차비스`, `jarvis`도 호출어로 인정합니다.

소유자 목소리 확인을 통과한 직후에는 12초 동안 명령 대기 상태가 됩니다. 이때는 `자비스` 또는 `헤이 자비스`만 먼저 말한 뒤 이어서 `카메라 셀피 모드로 실행해`처럼 호출어 없이 명령만 말해도 됩니다. Jarvis가 깨어나면 짧은 확인음과 함께 알림 문구가 `소유자 확인됨. 명령을 말하세요.`로 바뀌고, 바로 다음 명령 인식으로 넘어갑니다.

카메라 관련 명령은 처리 후에도 30초 명령 대기 상태를 유지합니다. 예를 들어 `자비스` 후 `카메라 실행`, `후면`, `전면`, `찍어`를 이어서 말할 수 있습니다. 카메라 세션 안에서는 Android `SpeechRecognizer` 대신 sherpa-onnx 한국어 streaming ASR을 우선 사용해 짧은 명령을 로컬에서 바로 실행합니다. 로컬 모델이 없거나 초기화에 실패하면 기존 `SpeechRecognizer` 경로로 fallback합니다.

Jarvis 상태 overlay는 사용자가 바로 판단해야 하는 순간에만 표시됩니다. 화면에는 노치/상태바 아래의 작은 iPhone-style pill 형태로 `JARVIS`만 표시하고, 상태는 작은 컬러 점과 소리/진동 패턴으로 전달합니다. 초록 점은 명령 대기/인식 중, 빨간 점은 방금 명령 인식에 실패했다는 뜻입니다. idle/소유자 확인/호출어 대기 상태에서는 화면을 가리지 않도록 overlay를 숨깁니다. 명령 가능 상태에 들어갈 때는 확인음 2회와 짧은 진동이 함께 발생합니다.

## 폰에서 켜야 하는 것

1. Android Studio에서 이 폴더를 엽니다.
2. 앱을 설치합니다.
3. 앱에서 마이크 권한과 알림 권한을 허용합니다.
4. `내 목소리 등록 시작`을 누른 뒤 조용한 곳에서 6초 동안 자연스럽게 말합니다.
5. `접근성 설정 열기`를 누르고 `Jarvis` 접근성 서비스를 켭니다.
6. HyperOS 앱 설정에서 자동 시작을 허용하고 배터리 제한을 풀어줍니다.
7. 앱으로 돌아와 `Jarvis 시작`을 누릅니다.

## 설치 방법

### Android Studio로 바로 설치

1. PC에 Android Studio를 설치합니다.
2. Android Studio에서 이 저장소 폴더를 엽니다.
3. Xiaomi 폰에서 `설정 > 휴대전화 정보`로 들어가 `OS 버전` 또는 `빌드 번호`를 여러 번 눌러 개발자 옵션을 켭니다.
4. `설정 > 추가 설정 > 개발자 옵션`에서 `USB 디버깅`을 켭니다.
5. USB 케이블로 폰을 PC에 연결합니다.
6. 폰에 뜨는 `USB 디버깅을 허용하시겠습니까?` 창에서 허용합니다.
7. Android Studio 상단 기기 목록에서 Xiaomi 15 Ultra를 선택합니다.
8. `Run` 버튼을 눌러 앱을 설치합니다.

첫 빌드에서는 한국어 streaming ASR 모델을 Hugging Face에서 내려받아 APK asset에 포함합니다. 모델은 `app/build/generated/sherpaAssets` 아래에 캐시되며 git에는 커밋하지 않습니다.

### APK 파일로 설치

1. Android Studio에서 `Build > Build App Bundle(s) / APK(s) > Build APK(s)`를 실행합니다.
2. 생성된 `app-debug.apk`를 폰으로 옮깁니다.
3. 폰에서 APK를 열고 설치합니다.
4. 설치가 막히면 `이 출처의 앱 설치 허용`을 켭니다.

접근성 권한은 APK 설치만으로 자동 허용되지 않습니다. Jarvis 앱을 처음 실행한 뒤 `접근성 설정 열기`를 눌러 `Jarvis` 접근성 서비스를 직접 켜야 합니다.

## 한계

기본 카메라 앱은 외부 제어 API를 제공하지 않습니다. 그래서 이 프로젝트는 접근성 서비스를 통해 버튼을 찾고 탭합니다. 전면/후면 모드 실행은 Android 카메라 인텐트에 렌즈 방향 힌트를 전달한 뒤, Xiaomi 카메라의 전환 버튼 상태를 읽어 목표 렌즈와 다를 때만 전환 버튼을 누릅니다. 셔터/필터/전환은 접근성 노드를 찾으면 노드 중앙을 실제 터치 제스처로 탭하고, 실패하면 화면 비율 기반 좌표를 탭합니다. Xiaomi 카메라 UI 버전, 언어, 화면 방향, 업데이트에 따라 노드 탐색 키워드나 좌표 fallback을 조정해야 할 수 있습니다.

`화면 켜`는 꺼진 디스플레이를 깨워 잠금화면을 보이게 하는 동작입니다. `화면 꺼`는 접근성 서비스의 잠금화면 전역 액션으로 기기를 잠그는 동작입니다. Android 보안 정책상 비밀번호, 지문, 얼굴인식 같은 잠금 해제는 자동으로 우회하지 않습니다.

소유자 목소리 인증은 오픈소스 `sherpa-onnx` 런타임과 3D-Speaker CAM++ ONNX 모델을 사용합니다. 앱에 등록된 소유자 embedding이 있으면 Jarvis는 마이크를 열어 둔 채 최근 2.0초 음성 window를 반복 검사하고, 통과한 짧은 시간 동안만 명령 인식 window를 엽니다. owner gate는 말소리 앞뒤의 무음을 줄여 embedding을 만들고, 기본 threshold `0.50`을 넘으면 즉시 통과합니다. 짧은 호출어를 보정하기 위해 active speech가 충분한 `0.46` 이상 근접 점수가 2회 연속 나오면 같은 소유자 발화로 보고 command window를 엽니다. command window 안에서는 sherpa-onnx 한국어 streaming ASR을 우선 사용하고, 불가능하면 Android `SpeechRecognizer`로 fallback합니다. 이 때문에 Jarvis 대기 중에는 Android의 초록색 마이크 표시가 켜져 있는 것이 정상입니다. 현재 구조상 한 문장을 완전히 동시에 인증/인식하지는 못하므로, 실사용에서는 먼저 Jarvis를 부르듯 말해 소유자 확인을 통과한 뒤 명령을 말하는 2단계 흐름이 가장 안정적입니다.

Android 14+ 정책상 `targetSdk=35` 앱은 재부팅 broadcast에서 microphone foreground service를 직접 시작할 수 없습니다. 대신 Jarvis는 재부팅 후 시작 알림을 띄우고, 사용자가 알림을 탭하면 마이크 서비스를 시작합니다. 한 번 시작된 뒤에는 foreground notification으로 계속 대기합니다.

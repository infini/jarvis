# Jarvis

개인 Android 비서 앱 실험 프로젝트입니다. 첫 목표는 Xiaomi 기본 카메라 앱을 열고, 음성 명령으로 셔터/필터/전환 같은 UI 조작을 자동화하는 것입니다.

장기 명세와 이어받기 기준은 [`docs/PROJECT_SPEC.md`](docs/PROJECT_SPEC.md)에 정리되어 있습니다. 기능을 추가할 때는 해당 문서를 먼저 갱신합니다.

## 현재 구조

- `JarvisVoiceService`: 마이크를 사용하는 포그라운드 서비스입니다. 소유자 인증, 명령 인식, 명령 실행 객체를 조합해 음성 상태 전환을 관리합니다.
- `JarvisVoiceServiceStarter`: 앱 UI와 접근성 watchdog에서 공통으로 쓰는 음성 서비스 시작 helper입니다.
- `OwnerVoiceGate`: 등록된 소유자 목소리를 확인하고 짧은 명령 window를 엽니다.
- `OwnerVoiceEngine`: sherpa-onnx와 3D-Speaker CAM++ 모델로 소유자 목소리 embedding을 만들고 검증합니다.
- `OwnerVoiceStore`: 등록된 소유자 목소리 embedding을 앱 private storage에 저장합니다.
- `OwnerVoiceEnrollmentController`: 앱 UI에서 실행되는 소유자 목소리 등록 workflow를 담당합니다.
- `LocalCommandRecognizer`: sherpa-onnx 한국어 streaming ASR 모델로 owner gate 직전 음성 window와 Android STT 실패 fallback을 인식합니다.
- `LocalCommandSession`: 로컬 명령 ASR 스레드 상태를 관리합니다.
- `SpeechRecognitionIntentFactory`: Android `SpeechRecognizer` 실행 옵션을 한곳에서 생성합니다.
- `JarvisCommandExecutor`: 내부 명령 실행, 중복 실행 방지, 카메라 세션 window 유지 정책을 담당합니다.
- `JarvisNotificationController`: 음성 서비스 foreground notification과 상태 문구를 관리합니다.
- `JarvisFeedbackController`: 명령 가능/처리/실패 상태의 소리, 진동, 상태 broadcast를 담당합니다.
- `JarvisStateIndicatorController`: 접근성 overlay로 현재 Jarvis 상태를 화면 위에 표시합니다.
- `JarvisBootReceiver`: 재부팅 또는 앱 업데이트 후 Jarvis 시작 알림을 띄웁니다.
- `JarvisAccessibilityService`: 접근성 서비스 생명주기, 명령 수신, 음성 서비스 watchdog 복구를 담당합니다.
- `CameraAccessibilityController`: Xiaomi 기본 카메라의 셔터/필터/전후면 전환 자동화를 담당합니다.
- `AccessibilityNodeMatcher`: 접근성 노드 키워드 검색과 스코어링을 담당합니다.
- `ScreenController`: 짧은 wake lock으로 꺼진 화면을 깨웁니다.
- `MainActivity`: 마이크/알림 권한 요청, 접근성 설정 열기, Jarvis 시작 UI입니다.

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

소유자 목소리 확인을 통과한 직후에는 12초 동안 명령 대기 상태가 됩니다. 이때는 `자비스` 또는 `헤이 자비스`만 먼저 말한 뒤 이어서 `카메라 셀피 모드로 실행해`처럼 호출어 없이 명령만 말해도 됩니다. `자비스 카메라 실행`처럼 한 문장에 명령이 같이 들어간 경우에는 owner gate가 방금 들은 1.2초 음성 window를 즉시 로컬 ASR에 넣어 먼저 해석합니다. Jarvis가 깨어나면 알림 문구가 `소유자 확인됨. 명령을 말하세요.`로 바뀌고 초록 표시가 뜹니다. 실제 명령 준비음/진동은 Android STT `ready_for_speech`가 들어온 순간에 울립니다.

카메라 관련 명령은 처리 후에도 30초 명령 대기 상태를 유지합니다. 예를 들어 `자비스` 후 `카메라 실행`, `후면`, `전면`, `찍어`, `종료`를 이어서 말할 수 있습니다. 30초 안에 다음 명령이 없으면 Jarvis는 조용히 소유자 확인 대기로 돌아가며, 로컬 ASR이나 Android STT 재시도는 이 시간을 넘겨 명령 대기 상태를 연장하지 않습니다. 카메라 세션 안에서는 owner gate 직전 1.2초 음성 window를 sherpa-onnx 한국어 streaming ASR로 먼저 해석하고, live command는 Android System Intelligence(AiAi) `SpeechRecognizer` partial/final 결과를 우선 사용해 실행합니다. Android STT가 실제 발화를 감지한 뒤 실패했거나 사용할 수 없을 때만 local ASR fallback을 사용하고, 아무 발화도 감지하지 못한 `NO_MATCH`/timeout은 실패 피드백 없이 command window 안에서 조용히 다시 듣습니다. local ASR fallback은 `0.0035` RMS 이상의 말소리를 기준으로 잡고, 최소 청취 560ms 이후 240ms trailing silence가 감지되면 빠르게 final decode로 넘어갑니다. 입력 음량이 낮아 ASR 텍스트가 비는 문제를 줄이기 위해 원본 RMS가 `0.0025` 이상인 구간은 ASR 입력에서 목표 RMS `0.04`, 최대 `10x`까지 증폭합니다. Jarvis 자체 확인음이 마이크에 들어가 첫 명령을 망치지 않도록 소유자 확인 직후에는 추가 대기 없이 다음 명령 리스닝을 시작하고, 명령 처리 직후에는 80ms 뒤에 다음 명령 리스닝을 시작합니다. `종료`, `홈`, `뒤로`는 현재 앱만 제어하고 Jarvis 명령 대기 상태는 유지합니다. `멈춰`는 현재 명령 대기만 닫고 소유자 호출 대기로 돌아갑니다. Jarvis 서비스는 한 번 시작되면 재부팅 전까지 foreground service로 유지하는 것을 원칙으로 하며, 접근성 서비스가 켜져 있고 소유자 목소리와 마이크 권한이 준비되어 있으면 watchdog이 꺼진 음성 서비스를 다시 시작합니다.

Jarvis 상태 overlay는 사용자가 바로 판단해야 하는 순간에만 표시됩니다. 화면에는 노치/상태바 아래의 작은 iPhone-style pill 형태로 `JARVIS`만 표시하고, 상태는 작은 컬러 점과 소리/진동 패턴으로 전달합니다. 초록 점은 명령 대기/인식 중, 빨간 점은 방금 명령 인식에 실패했다는 뜻입니다. idle/소유자 확인/호출어 대기 상태에서는 화면을 가리지 않도록 overlay를 숨깁니다. 명령 가능 상태에 들어갈 때는 확인음 2회와 짧은 진동이 함께 발생합니다.

## 속도 측정 로그

음성 인식 속도 개선은 `JarvisLatency` 로그를 기준으로 판단합니다. 다음 명령을 켜고 `자비스`, `카메라 실행`, `후면`, `전면`, `찍어`, `종료`를 한 사이클 말하면 같은 `trace=` 값으로 구간별 시간이 출력됩니다.

```bash
adb logcat -v time -s JarvisLatency
```

주요 이벤트는 `owner_audio_asr_start`, `owner_audio_asr_complete`, `owner_audio_wake_only`, `listen_start`, `local_partial`, `local_complete`, `fallback_to_android`, `ready_for_speech`, `speech_begin`, `speech_end`, `speech_error`, `partial_results`, `wake_only_partial`, `partial_wake_complete`, `final_results`, `command_parsed`, `command_execute_start`, `command_execute_return`, `accessibility_command_received`, `accessibility_command_dispatch_return`, `command_complete`입니다. 음성 서비스 내부 이벤트의 `total=...ms`는 trace 시작부터 해당 이벤트까지의 누적 시간이고, `step=...ms`는 직전 이벤트 이후 걸린 시간입니다. `owner_audio_asr_complete`는 owner gate 통과 직전 음성 window의 즉시 ASR 결과를 기록하고, `owner_audio_wake_only`는 이 buffered audio에서 `자비스` wake-only가 확인된 경로입니다. `local_complete`는 live local ASR 종료 이유, local ASR 자체 elapsed, active speech, trailing silence, peak/mean RMS, ASR gain을 함께 기록합니다. `ready_for_speech`, `speech_begin`, `speech_end`, `speech_error`를 보면 Android STT가 실제로 언제 준비되고 언제 발화를 감지했는지 분리할 수 있습니다. `wake_only_partial`은 Android STT partial에서 `자비스` wake-only를 먼저 잡아 final 결과를 기다리지 않고 다음 command listening으로 넘어간 경로입니다. `accessibility_command_received`의 `totalMs`는 trace 시작부터 접근성 서비스 수신까지의 누적 시간이고, `busDelayMs`는 음성 서비스가 명령을 보낸 뒤 접근성 서비스가 받은 지연입니다.

trace별 요약은 다음 스크립트로 확인합니다.

```bash
scripts/jarvis-latency-report.sh
```

기존 측정 로그와 진단 로그를 함께 판독해 실패 단계를 분류하려면 다음 스크립트를 사용합니다.

```bash
scripts/jarvis-latency-audit.sh /tmp/jarvis-command-trace.log
```

새 측정은 로그를 비우고 정해진 시간 동안 녹화한 뒤 바로 요약합니다.

```bash
scripts/jarvis-command-trace.sh 45
```

출력의 `path`는 `owner_audio_asr`, `owner_audio_asr->android_stt`, `android_stt`, `local_asr` 같은 실제 인식 경로이고, `total`은 마지막 trace 이벤트까지의 누적 시간입니다. `owner_acceptance`, `owner_auth_speech`, `owner_gate_elapsed`, `owner_attempts`, `owner_endpoint`, `owner_elapsed`, `local_endpoint`, `android_ready`, `speech_begin`, `speech_end`, `error`, `peak_rms`, `mean_rms`, `asr_gain`을 함께 보면 owner gate와 명령 ASR 중 어느 단계가 병목인지 분리할 수 있습니다. `listen`은 Android STT 시작 시점, `listen_ready`는 Android STT 시작부터 `ready_for_speech`까지의 시간입니다. `parsed`는 trace 시작부터 명령 파싱까지의 누적 시간이고, `speech_parse`는 Android STT가 발화를 감지한 뒤 명령 파싱까지의 시간입니다. 정상적인 빠른 명령은 `command_parsed`, `command_execute_start`, `accessibility_command_received`, `command_complete`가 같은 trace 안에 이어져야 합니다. `jarvis-command-trace.sh`는 debug APK에서 ADB 시작용 no-display Activity를 먼저 호출해 Jarvis 서비스를 올리려고 시도합니다. 이 자동 시작을 끄려면 `JARVIS_START_DEBUG_ACTIVITY=0`을 지정합니다. 측정 중에는 `자비스`를 말한 뒤 초록 `JARVIS` 표시 또는 준비음/진동이 나오면 `카메라 실행` 같은 짧은 명령을 말합니다. 이 스크립트는 `status=command_complete`가 없으면 `owner_authorized`, `ready_for_speech`, `speech_begin`, `partial_results`, `command_parsed`, `non_strict_wake_idle_suppressed` 카운터로 어느 단계에서 멈췄는지 출력하고, 같은 경로의 `.diagnostic` 로그에 `OwnerVoiceGate` 점수/거절 사유를 함께 저장합니다. `jarvis-latency-audit.sh`는 같은 로그를 `PASS`, `FAIL_NO_OWNER_WAKE`, `FAIL_NO_SPEECH`, `FAIL_NO_COMMAND`, `FAIL_NO_COMMAND_COMPLETE`, `FAIL_SLOW`로 분류하고 `max_score`, `max_peak_rms`, `max_noise_rms`, owner gate 거절 사유 상위 항목을 함께 보여줍니다. 명령이 실행됐더라도 `speech_begin`이 있는 trace에서 기본 기준 `speech_parse<=2500ms`, `speech_begin`이 없는 trace에서 `parsed<=2500ms`, 접근성 실행이 있는 경우 `access<=4000ms`를 넘으면 실패로 종료합니다. 기준은 `JARVIS_MAX_SPEECH_PARSED_MS`, `JARVIS_MAX_PARSED_MS`, `JARVIS_MAX_ACCESS_MS` 환경 변수로 조정할 수 있습니다.

## 폰에서 켜야 하는 것

1. Android Studio에서 이 폴더를 엽니다.
2. 앱을 설치합니다.
3. 앱에서 마이크 권한과 알림 권한을 허용합니다.
4. `내 목소리 등록 시작`을 누른 뒤 조용한 곳에서 6초 동안 자연스럽게 말합니다.
5. `접근성 설정 열기`를 누르고 `Jarvis` 접근성 서비스를 켭니다.
6. HyperOS 앱 설정에서 자동 시작을 허용하고 배터리 제한을 풀어줍니다.
7. 앱으로 돌아와 `Jarvis 시작`을 누릅니다. 이후 접근성 서비스가 살아 있으면 Jarvis 음성 서비스가 내려간 상태를 watchdog이 주기적으로 복구합니다.

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

소유자 목소리 인증은 오픈소스 `sherpa-onnx` 런타임과 3D-Speaker CAM++ ONNX 모델을 사용합니다. 앱에 등록된 소유자 embedding이 있으면 Jarvis는 마이크를 열어 둔 채 최근 1.2초 음성 window를 반복 검사하고, 통과한 짧은 시간 동안만 명령 인식 window를 엽니다. owner gate는 말소리 앞뒤의 무음을 줄여 embedding을 만들고, 기본 threshold `0.50`을 넘으면 즉시 통과합니다. 인증 중에는 일정한 배경음이 계속 말소리로 처리되지 않도록 배경음 floor 대비 피크가 충분한 구간만 embedding으로 계산합니다. 등록/일반 embedding은 최소 peak RMS `0.002`를 유지하되, Xiaomi 15 Ultra 실기기에서 짧은 `자비스` 발화가 peak RMS `0.0009`대까지 낮게 들어오는 로그가 확인되어 owner verification 경로만 최소 peak RMS `0.00075`, 최소 active RMS `0.00050`, floor 대비 최소 상승폭 `0.00032`로 분리했습니다. 짧은 호출어를 보정하기 위해 active speech가 충분한 `0.28` 이상 근접 점수가 2회 연속 나오거나, 450ms 이상 말소리에서 `0.24` 이상 soft wake 점수가 1회 나오거나, 400ms 이상 말소리에서 `0.14` 이상 soft wake 점수가 4회 나오면 같은 소유자 발화로 보고 command window를 엽니다. soft wake 연속 판정은 짧은 `자비스` 발화의 점수 흔들림을 흡수하기 위해 중간에 `0.10` 이상 애매한 점수 1회까지 허용합니다. owner gate가 통과하면 command window는 즉시 초록 `JARVIS` overlay로 표시합니다. strict, near, single soft wake는 Android STT `ready_for_speech` 시점에 준비음/진동도 내고, 낮은 점수 연속 soft wake는 소리 없이 overlay만 표시합니다. 이 window에서 Android STT가 실제 발화를 감지하지 못하면 Jarvis는 즉시 닫고 8초 동안 낮은 점수 연속 soft wake만 억제해 idle 환경의 반복 오작동을 줄입니다. near와 single soft wake는 이 억제에 묶지 않아 사용자의 `자비스` 재호출이 같이 막히지 않게 합니다. owner gate 통과 직전의 음성 window는 즉시 로컬 ASR로 재해석해 `자비스 카메라 실행`처럼 한 문장 안에 들어온 명령이나 `자비스` wake-only를 먼저 시도합니다. 이후 live command window는 Android System Intelligence(AiAi) `SpeechRecognizer`를 먼저 사용하고, Android STT가 실패하거나 사용할 수 없을 때만 local ASR fallback을 사용합니다. 로컬 ASR fallback은 짧은 명령 후 trailing silence를 감지하면 timeout 전에도 final decode를 실행합니다. 카메라 세션 command window는 서비스 레벨의 30초 deadline으로 닫히므로 fallback 재시도 때문에 무한히 유지되지 않습니다. 이 때문에 Jarvis 대기 중에는 Android의 초록색 마이크 표시가 켜져 있는 것이 정상입니다.

Android 14+ 정책상 `targetSdk=35` 앱은 재부팅 broadcast에서 microphone foreground service를 직접 시작할 수 없습니다. 대신 Jarvis는 재부팅 후 시작 알림을 띄우고, 사용자가 알림을 탭하면 마이크 서비스를 시작합니다. 소유자 목소리와 마이크 권한이 준비되어 있고 접근성 서비스가 연결되어 있으면 watchdog이 앱 업데이트나 서비스 종료 이후 음성 서비스를 다시 시작하려고 시도합니다. Android/HyperOS가 백그라운드 시작을 막는 경우에는 시작 알림을 탭하는 경로가 fallback입니다.

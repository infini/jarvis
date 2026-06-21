# Jarvis

개인 Android 비서 앱 실험 프로젝트입니다. 첫 목표는 Xiaomi 기본 카메라 앱을 열고, 음성 명령으로 셔터/필터/전환 같은 UI 조작을 자동화하는 것입니다.

장기 명세와 이어받기 기준은 [`docs/PROJECT_SPEC.md`](docs/PROJECT_SPEC.md)에 정리되어 있습니다. 기능을 추가할 때는 해당 문서를 먼저 갱신합니다.

## 현재 구조

- `JarvisVoiceService`: 마이크를 사용하는 포그라운드 서비스입니다. 소유자 인증, 명령 인식, 명령 실행 객체를 조합해 음성 상태 전환을 관리합니다.
- `JarvisVoiceServiceStarter`: 앱 UI와 접근성 watchdog에서 공통으로 쓰는 음성 서비스 시작 helper입니다.
- `LocalActivationSession`: idle에서 activation 전용 ASR을 실행하고 최근 음성 rolling buffer를 관리합니다.
- `OwnerVoiceGate`: local activation ASR을 사용할 수 없을 때 fallback 소유자 확인을 담당합니다.
- `OwnerVoiceEngine`: sherpa-onnx와 3D-Speaker CAM++ 모델로 소유자 목소리 embedding 묶음을 만들고 검증합니다.
- `OwnerVoiceStore`: 등록된 소유자 목소리 embedding 묶음을 앱 private storage에 저장합니다.
- `OwnerVoiceEnrollmentController`: 앱 UI에서 실행되는 소유자 목소리 등록 workflow를 담당합니다.
- `LocalCommandRecognizer`: sherpa-onnx 한국어 streaming ASR 모델로 idle activation과 Android STT 실패 fallback을 인식합니다.
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

Idle 상태에서는 `자비스 깨어나` 계열 문장만 command window를 엽니다. 실제 ASR 표기 흔들림을 고려해 `자베스 깨어나`, `쟈비스 깨어나`, `제비스 깨어나`, `차비스 깨어나`, `잡비스 깨어나`, `잡스 깨어나`, `jarvis 깨어나`는 같은 호출로 봅니다. Xiaomi 15 Ultra 실기기에서 local ASR이 앞의 `자비스`를 누락하고 `깨어나` 또는 `깨우나`만 반환하는 경우가 있어, 이 결과도 local activation ASR 한정 equivalent로 인정합니다. 단, 이 경우에도 같은 rolling audio의 owner voice verification을 반드시 통과해야 command window가 열립니다. `자비스 실행`, `자비스` 단독, `헤이 자비스`, `헤이 자비스 깨어나`, `자비스 카메라 실행`은 idle에서 명령 모드로 전환하지 않습니다. ASR 결과 끝에 붙는 마침표 같은 문장부호는 activation 비교 전에 제거합니다.

초록 `JARVIS` overlay가 보이는 command window 안에서는 호출어 없이 다음 명령을 말합니다.

- `카메라 열어`
- `카메라 실행`
- `카메라 셀피 모드로 실행해`
- `셀피`
- `전면`
- `셀피 모드`
- `카메라 후면 모드로 실행해`
- `후면`
- `후면 모드`
- `찍어`
- `사진 찍기`
- `카메라 찍어`
- `필터`
- `전면 카메라`
- `카메라 전환`
- `카메라 종료해`
- `종료`
- `화면 켜`
- `화면 꺼`
- `뒤로`
- `홈`
- `자비스 잠들어`
- `멈춰`

`자비스 깨어나`로 활성화되면 30초 동안 명령 대기 상태가 됩니다. `카메라 실행`, `후면`, `전면`, `찍어`, `종료` 같은 명령을 처리한 뒤에도 30초 command window를 다시 엽니다. 30초 안에 다음 명령이 없으면 Jarvis는 실패음 없이 조용히 idle activation 대기로 돌아가고, overlay도 사라집니다. 로컬 ASR이나 Android STT 재시도는 이 시간을 넘겨 명령 대기 상태를 연장하지 않습니다. command window가 닫힌 뒤 예약되어 있던 다음 STT 시작은 폐기되며, 소유자 프로필이 설정된 상태에서는 command window 밖에서 Android STT를 열지 않습니다.

Idle에서는 sherpa-onnx 한국어 streaming ASR의 activation 전용 hotwords recognizer가 먼저 `자비스 깨어나`를 듣습니다. 마이크 `AudioRecord`는 `VOICE_RECOGNITION` source를 우선 사용하고 실패 시 `MIC`로 fallback하며, 60초 세션으로 길게 유지하고 ASR stream은 최대 8초 segment 단위로만 재시작합니다. 이 때문에 Android/HyperOS 우상단의 초록 마이크 privacy chip과 `JARVIS` 앱명 표시는 Jarvis가 idle 대기 중 마이크를 사용한다는 OS 표시이며, 앱이 숨길 수 없습니다. Jarvis 자체 overlay는 화면 상단 중앙의 작은 `JARVIS` pill이고, activation phrase와 owner voice가 모두 통과해 command window가 열린 경우에만 표시됩니다.

activation recognizer는 `modified_beam_search`, `jarvis-activation-hotwords.txt`, `modelingUnit=cjkchar`를 사용하며, 최근 3.6초 rolling audio를 0.8초마다 buffered hotword decode로 다시 확인합니다. streaming final이 `깨우나`처럼 일부만 잡혀도 같은 rolling audio의 buffered decode에서 `자비스 깨어나` 계열이 확인되면 activation으로 처리합니다. 문구가 확인되면 그때 보존된 최근 오디오로 3D-Speaker owner voice verification을 실행하고, 소유자 목소리까지 통과한 경우에만 Android System Intelligence(AiAi) `SpeechRecognizer` 명령 리스닝을 시작합니다. activation 문구가 아니거나 소유자 목소리가 아니면 overlay, 준비음, Android command STT 없이 조용히 idle로 돌아갑니다. Android STT가 실제 발화를 감지한 뒤 실패했거나 사용할 수 없을 때만 local ASR fallback을 사용하고, 아무 발화도 감지하지 못한 `NO_MATCH`/timeout은 실패 피드백 없이 command window 안에서 조용히 다시 듣습니다. local ASR fallback은 `0.0020` RMS 이상의 말소리를 기준으로 잡고, activation ASR은 `0.0012` RMS 이상의 입력도 segment 대상으로 봅니다. local ASR fallback은 최소 청취 560ms 이후 240ms trailing silence가 감지되면 빠르게 final decode로 넘어갑니다. 입력 음량이 낮아 ASR 텍스트가 비는 문제를 줄이기 위해 원본 RMS가 `0.0010` 이상인 구간은 ASR 입력에서 목표 RMS `0.04`, 최대 `30x`까지 증폭합니다. 명령 처리 직후에는 80ms 뒤에 다음 명령 리스닝을 시작합니다. `종료`, `홈`, `뒤로`는 현재 앱만 제어하고 Jarvis 명령 대기 상태는 유지합니다. `자비스 잠들어`와 `멈춰`는 현재 명령 대기만 닫고 idle activation 대기로 돌아갑니다. Jarvis 서비스는 한 번 시작되면 재부팅 전까지 foreground service로 유지하는 것을 원칙으로 하며, 접근성 서비스가 켜져 있고 소유자 목소리와 마이크 권한이 준비되어 있으면 watchdog이 꺼진 음성 서비스를 다시 시작합니다.

Jarvis 상태 overlay는 사용자가 바로 판단해야 하는 순간에만 표시됩니다. 화면에는 노치/상태바 아래의 작은 iPhone-style pill 형태로 `JARVIS`만 표시하고, 상태는 작은 컬러 점과 소리/진동 패턴으로 전달합니다. 초록 점은 명령 대기/인식 중, 빨간 점은 방금 명령 인식에 실패했다는 뜻입니다. idle/local activation/소유자 확인 상태에서는 화면을 가리지 않도록 overlay를 숨깁니다. 명령 가능 상태에 들어갈 때는 확인음 2회와 짧은 진동이 함께 발생합니다.

따라서 모바일 화면에서 `JARVIS` overlay가 보이지 않는 상태가 사용자 기준 idle 상태입니다. idle에서는 local activation ASR과 owner voice 확인만 돌고, Android command STT 준비음이나 명령 실패음은 발생하지 않아야 합니다.

## 속도 측정 로그

음성 인식 속도 개선은 `JarvisLatency` 로그를 기준으로 판단합니다. 다음 명령을 켜고 `자비스 깨어나`, `카메라 실행`, `후면`, `전면`, `찍어`, `종료`를 한 사이클 말하면 같은 `trace=` 값으로 구간별 시간이 출력됩니다.

```bash
adb logcat -v time -s JarvisLatency
```

주요 이벤트는 `activation_listen_start`, `activation_asr_rejected_segment`, `activation_asr_complete`, `activation_owner_verified`, `owner_audio_activation`, `listen_start`, `local_partial`, `local_complete`, `fallback_to_android`, `ready_for_speech`, `speech_begin`, `speech_end`, `speech_error`, `partial_results`, `final_results`, `command_parsed`, `command_execute_start`, `command_execute_return`, `accessibility_command_received`, `accessibility_command_dispatch_return`, `command_complete`입니다. 음성 서비스 내부 이벤트의 `total=...ms`는 trace 시작부터 해당 이벤트까지의 누적 시간이고, `step=...ms`는 직전 이벤트 이후 걸린 시간입니다. `activation_asr_rejected_segment`는 60초 idle 세션 안에서 activation이 아닌 segment를 버리고 계속 듣는 경로입니다. `activation_asr_complete`는 activation 문구가 확인됐거나 60초 세션이 종료된 결과를, `activation_owner_verified`는 같은 rolling audio의 owner score를 기록합니다. `owner_audio_activation`은 activation phrase와 owner voice가 모두 통과해 command window를 여는 경로입니다. `local_complete`는 live local ASR 종료 이유, local ASR 자체 elapsed, active speech, trailing silence, peak/mean RMS, ASR gain을 함께 기록합니다. `ready_for_speech`, `speech_begin`, `speech_end`, `speech_error`를 보면 Android STT가 실제로 언제 준비되고 언제 발화를 감지했는지 분리할 수 있습니다. `accessibility_command_received`의 `totalMs`는 trace 시작부터 접근성 서비스 수신까지의 누적 시간이고, `busDelayMs`는 음성 서비스가 명령을 보낸 뒤 접근성 서비스가 받은 지연입니다. 같은 프로세스에서 접근성 서비스가 살아 있으면 `CommandBus`는 broadcast 전에 direct receiver를 먼저 호출하며, 로그의 `transport=direct|broadcast`로 경로를 구분합니다.

trace별 요약은 다음 스크립트로 확인합니다.

```bash
scripts/jarvis-latency-report.sh
```

기존 측정 로그와 진단 로그를 함께 판독해 실패 단계를 분류하려면 다음 스크립트를 사용합니다.

```bash
scripts/jarvis-latency-audit.sh /tmp/jarvis-command-trace.log
```

소유자 목소리 재등록 후에는 debug APK에서 저장된 embedding 개수를 먼저 확인합니다.

```bash
scripts/jarvis-profile-status.sh
```

정상적인 재등록 상태는 `profile_configured=true`, `profile_embeddings>=2`, `profile_phrase_id=jarvis_activation_v3`이어야 합니다.
`profile_embeddings`가 1이거나 `profile_phrase_id`가 비어 있으면 구버전 프로필이 남아 있는 상태입니다. 이 상태에서는 Jarvis 음성 서비스 시작을 막기 때문에 `자비스 깨어나`를 말해도 대기 상태로 들어가지 않습니다. USB 연결 상태에서는 다음 스크립트로 debug APK의 no-display 등록 Activity를 실행해 앱 화면을 열지 않고 다시 등록할 수 있습니다.
`jarvis-profile-status.sh`와 `jarvis-owner-enroll.sh`는 request id로 자기 실행 로그만 읽으며 기존 `JarvisLatency` 로그를 지우지 않습니다.
debug no-display Activity는 별도 task affinity를 사용하므로 ADB 검증 중 기존 Jarvis 앱 화면을 앞으로 복귀시키지 않습니다.

```bash
scripts/jarvis-owner-enroll.sh 6
```

스크립트가 `Speak now`를 출력하면 6초 동안 `자비스 깨어나`를 여러 번 또렷하게 말합니다. debug enrollment는 반복 발화 ASR 결과 안에 `자비스 깨어나` 계열 activation phrase가 포함되면 등록 검증을 통과시킵니다. 실제 idle activation은 `자비스 깨어나` 계열 단일 activation phrase 또는 local activation ASR에서만 허용되는 제한된 equivalent를 인정하며, 일반 command parsing wake word로 확장하지 않습니다. debug enrollment는 마지막 녹음을 앱 cache의 `jarvis-owner-enroll-last.wav`로 저장하고 `debug_wav` 로그에 경로를 남겨 activation ASR 실패를 재현할 수 있게 합니다. 등록이 끝나면 Jarvis 서비스를 다시 시작하고, 자동으로 `jarvis-profile-status.sh`를 실행합니다. `profile_configured=true`일 때만 속도 측정을 진행합니다.

새 측정은 저장된 owner profile이 현재 activation 등록 상태인지 먼저 확인한 뒤, 로그를 비우고 정해진 시간 동안 녹화해 바로 요약합니다.

activation 디버깅은 사용자가 같은 문장을 반복해 말하는 방식으로 진행하지 않습니다. debug APK는 idle activation ASR이 사용한 원본 rolling WAV와 JSON 메타데이터를 앱 cache의 `jarvis-activation-attempts/`에 자동 저장합니다. 이후 같은 샘플을 현재 APK의 activation ASR과 owner verification으로 다시 돌릴 때는 다음 스크립트를 사용합니다.

```bash
scripts/jarvis-wake-diagnose.sh
scripts/jarvis-wake-live-check.sh 20
scripts/jarvis-activation-replay.sh
scripts/jarvis-activation-captures.sh
```

`jarvis-wake-live-check.sh`는 실기기에서 wake만 검증합니다. 기본값은 앱 화면을 띄우지 않고 `JarvisVoiceService`만 재시작해 activation 세션 시작점과 측정 시작점을 맞춥니다. 실행하면 폰이 짧게 진동하고, 사용자는 진동 직후 `자비스 깨어나`를 말합니다. 스크립트는 `activation_partial`, `owner_audio_activation`, `ready_for_speech`, `JarvisStateIndicator overlay_visible state=COMMAND_READY`를 함께 확인해 상단 중앙 `JARVIS` overlay가 실제로 표시됐는지 판정합니다. `jarvis-wake-diagnose.sh`는 프로필 상태, 저장된 activation WAV replay, 최신 캡처 메타데이터, 관련 logcat을 한 번에 묶어 wake 실패 원인을 먼저 분류합니다. `jarvis-activation-replay.sh`는 저장된 WAV들을 앱 내부에서 재디코딩해 accepted/text/RMS와 owner score를 로그로 요약합니다. `jarvis-activation-captures.sh`는 WAV/JSON 묶음을 `/tmp`로 가져와 사람이 직접 확인하거나 별도 분석에 사용할 수 있게 합니다. 이 흐름으로 ASR rule과 threshold 변경은 저장 샘플로 먼저 검증하고, 사용자의 실시간 발화는 최종 확인 단계에서만 요청합니다.

30초 command window timeout은 사용자가 발화하지 않아도 debug APK에서 검증할 수 있습니다.

```bash
scripts/jarvis-idle-guard.sh 20
scripts/jarvis-command-window-timeout.sh 30
scripts/jarvis-command-window-timeout.sh 30 open_camera
scripts/jarvis-command-window-timeout-matrix.sh 30
scripts/jarvis-overlay-timeout.sh 30 open_camera
scripts/jarvis-ready-feedback-once.sh 30
```

`jarvis-idle-guard.sh`는 idle 상태에서 지정 시간 동안 accepted activation 또는 command STT의 `listen_start`/`ready_for_speech`가 발생하지 않는지 확인합니다. `activation_phrase_missing`과 `activation_owner_rejected`는 호출어 또는 소유자 확인이 통과하지 않아 조용히 거절된 정상 idle 경로로 봅니다. 성공 시에는 요약과 원본 logcat 파일 경로만 출력하고, 실패 시에는 원인 이벤트를 함께 출력합니다. `jarvis-command-window-timeout.sh`는 debug no-display Activity로 command window를 열고, 지정한 시간 뒤 command window close가 발생하며 이후 `ready_for_speech`가 다시 나오지 않는지 확인합니다. 두 번째 인자로 command id를 넘기면 `open_camera`, `open_front_camera`, `open_rear_camera`, `take_photo`, `home` 같은 명령 실행 후 command window가 다시 열린 뒤 닫히는 경로를 검증합니다. close 이벤트는 일반 timeout, active speech grace 만료, listen timeout 만료, deadline 이후 speech error/final/local no-command 종료를 모두 포함합니다. `jarvis-command-window-timeout-matrix.sh`는 기본 command window와 `open_camera`, `open_front_camera`, `open_rear_camera`, `take_photo`, `home`을 같은 기준으로 순차 검증합니다. `jarvis-overlay-timeout.sh`는 command window에서 `JARVIS` overlay가 표시되고 command window close 뒤 `overlay_hidden`으로 사라지는지 확인합니다. `jarvis-ready-feedback-once.sh`는 command window 안에서 Android STT가 여러 번 `ready_for_speech`로 재시작돼도 준비음은 한 번만 발생하는지 확인합니다.

```bash
scripts/jarvis-command-trace.sh 45
```

출력의 `path`는 `local_activation_asr->android_stt`, `android_stt`, `local_asr` 같은 실제 인식 경로이고, `total`은 마지막 trace 이벤트까지의 누적 시간입니다. `activation_asr_complete`, `activation_owner_verified`, `local_endpoint`, `android_ready`, `speech_begin`, `speech_end`, `error`, `peak_rms`, `mean_rms`, `asr_gain`을 함께 보면 idle activation ASR, owner verification, live Android STT, local fallback 병목을 나눌 수 있습니다. `listen`은 Android STT 시작 시점, `listen_ready`는 Android STT 시작부터 `ready_for_speech`까지의 시간입니다. `parsed`는 trace 시작부터 명령 파싱까지의 누적 시간이고, `speech_parse`는 Android STT가 발화를 감지한 뒤 명령 파싱까지의 시간입니다. `speech_access`는 발화 시작부터 접근성 서비스 수신까지, `command_access`는 명령 파싱부터 접근성 서비스 수신까지 걸린 시간입니다. 정상적인 빠른 명령은 `owner_audio_activation`, `listen_start`, `command_parsed`, `command_execute_start`, `accessibility_command_received`, `command_complete`가 같은 trace 안에 이어져야 합니다. `jarvis-command-trace.sh`는 debug APK에서 ADB 시작용 no-display Activity를 먼저 호출해 Jarvis 서비스를 재시작하려고 시도합니다. 이 자동 시작을 끄려면 `JARVIS_START_DEBUG_ACTIVITY=0`을 지정합니다. 프로필 검사를 건너뛰고 강제로 측정하려면 `JARVIS_SKIP_PROFILE_CHECK=1`을 지정합니다. 측정 중에는 `자비스 깨어나`를 말한 뒤 초록 `JARVIS` 표시 또는 준비음/진동이 나오면 `카메라 실행` 같은 짧은 명령을 말합니다. 이 스크립트는 `status=command_complete`가 없으면 `activation_asr_complete`, `activation_owner_verified`, `owner_audio_activation`, `ready_for_speech`, `speech_begin`, `partial_results`, `command_parsed` 카운터로 어느 단계에서 멈췄는지 출력하고, 같은 경로의 `.diagnostic` 로그에 activation/owner score와 거절 사유를 함께 저장합니다. `jarvis-latency-audit.sh`는 같은 로그를 `PASS`, `FAIL_LEGACY_PROFILE`, `FAIL_NO_ACTIVATION`, `FAIL_NO_SPEECH`, `FAIL_NO_COMMAND`, `FAIL_NO_COMMAND_COMPLETE`, `FAIL_SLOW`로 분류합니다. `FAIL_LEGACY_PROFILE`은 저장된 owner profile이 현재 activation 등록 기준을 만족하지 않아 속도 판정에 사용할 수 없다는 뜻입니다. `FAIL_NO_ACTIVATION`은 local activation hotwords ASR이 `자비스 깨어나` activation phrase를 확인하지 못했거나 owner verification이 거절했다는 뜻입니다. 명령이 실행됐더라도 `speech_begin`이 있는 trace에서 `speech_parse<=2500ms`, `speech_begin`이 없는 trace에서 `parsed<=2500ms`, 접근성 실행이 있는 경우 `speech_access<=4000ms` 및 `command_access<=1200ms`를 넘으면 실패로 종료합니다. 기준은 `JARVIS_MAX_SPEECH_PARSED_MS`, `JARVIS_MAX_PARSED_MS`, `JARVIS_MAX_ACCESS_MS`, `JARVIS_MAX_COMMAND_ACCESS_MS` 환경 변수로 조정할 수 있습니다.

## 폰에서 켜야 하는 것

1. Android Studio에서 이 폴더를 엽니다.
2. 앱을 설치합니다.
3. 앱에서 마이크 권한과 알림 권한을 허용합니다.
4. `내 목소리 등록 시작`을 누른 뒤 조용한 곳에서 6초 동안 `자비스 깨어나`를 여러 번 또렷하게 말합니다.
5. `접근성 설정 열기`를 누르고 `Jarvis` 접근성 서비스를 켭니다.
6. HyperOS 앱 설정에서 자동 시작을 허용하고 배터리 제한을 풀어줍니다.
7. 앱으로 돌아와 `Jarvis 시작`을 누릅니다. 이후 접근성 서비스가 살아 있으면 Jarvis 음성 서비스가 내려간 상태를 watchdog이 주기적으로 복구합니다.

USB 디버깅이 연결되어 있으면 `scripts/jarvis-owner-enroll.sh 6`으로 소유자 목소리를 다시 등록하고, `scripts/jarvis-profile-status.sh`로 `profile_configured=true`인지 확인할 수 있습니다. 구버전 단일 샘플 프로필 또는 `자비스 깨어나` activation 이전 프로필은 `profile_configured=false`로 표시되며, 이 상태에서는 보안상 Jarvis가 음성 대기에 들어가지 않습니다.

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

소유자 목소리 인증은 오픈소스 `sherpa-onnx` 런타임과 3D-Speaker CAM++ ONNX 모델을 사용합니다. 앱에 등록된 소유자 embedding이 있으면 Jarvis는 먼저 local activation ASR로 `자비스 깨어나`를 찾고, activation이 확인된 rolling audio를 speaker embedding 입력으로 사용합니다. speaker embedding 입력은 내부에서 최소 1.2초로 padding합니다. 등록 시에는 `자비스 깨어나`를 반복한 전체 6초 음성을 먼저 activation hotwords ASR로 확인하고, activation phrase가 확인된 경우에만 1.4초 단위 짧은 구간에서 최소 2개, 최대 8개의 embedding을 저장하며 `profile_phrase_id=jarvis_activation_v3` 메타데이터를 함께 저장합니다. verification은 이 묶음 중 가장 높은 similarity를 사용합니다. 기본 threshold `0.50`을 넘으면 strict 통과이고, activation phrase가 이미 확인된 경우에만 다중 embedding 프로필에서 active speech 450ms 이상, similarity `0.36` 이상 고신뢰 단일 점수 또는 `0.28` 이상 근접 점수를 통과시킵니다. 이 activation-after-owner 경로는 peak RMS `0.0035` 이상일 때만 허용하며, 기존 owner gate의 soft wake 단일/연속 점수는 command window 오픈 조건으로 사용하지 않습니다.

중요한 점은 activation phrase와 owner voice가 둘 다 확인되어야 command window가 열린다는 것입니다. `자비스 깨어나` 계열 hotwords ASR 또는 local activation ASR 한정 equivalent가 먼저 통과하고, 같은 rolling audio의 owner score가 통과한 경우에만 30초 command window를 열고 초록 `JARVIS` overlay와 준비음/진동을 제공합니다. `자비스` 단독, `헤이 자비스 깨어나`, `자비스 카메라 실행`, 주변 대화, 소유자의 다른 말은 idle 상태에서 Android STT를 열지 않습니다. 이후 live command window는 Android System Intelligence(AiAi) `SpeechRecognizer`를 먼저 사용하고, Android STT가 실패하거나 사용할 수 없을 때만 local ASR fallback을 사용합니다. 로컬 ASR fallback은 짧은 명령 후 trailing silence를 감지하면 timeout 전에도 final decode를 실행합니다. command window는 서비스 레벨의 30초 deadline으로 닫히므로 fallback 재시도 때문에 무한히 유지되지 않습니다. 이 때문에 Jarvis 대기 중에는 Android의 초록색 마이크 표시가 켜져 있는 것이 정상입니다.

Android 14+ 정책상 `targetSdk=35` 앱은 재부팅 broadcast에서 microphone foreground service를 직접 시작할 수 없습니다. 대신 Jarvis는 재부팅 후 시작 알림을 띄우고, 사용자가 알림을 탭하면 마이크 서비스를 시작합니다. 소유자 목소리와 마이크 권한이 준비되어 있고 접근성 서비스가 연결되어 있으면 watchdog이 앱 업데이트나 서비스 종료 이후 음성 서비스를 다시 시작하려고 시도합니다. Android/HyperOS가 백그라운드 시작을 막는 경우에는 시작 알림을 탭하는 경로가 fallback입니다.

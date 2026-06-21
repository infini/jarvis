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
- sherpa-onnx + 3D-Speaker CAM++ 기반 소유자 목소리 등록 UI 및 다중 embedding 저장 구현 완료
- 등록된 소유자 embedding 묶음이 있을 때 명령 인식 전 owner voice gate 1차 구현 완료
- 재부팅/앱 업데이트 후 Jarvis 시작 알림을 띄우는 boot receiver 구현 완료
- README 설치 문서 작성 완료
- 로컬 Debug 빌드는 `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` 지정 시 성공 확인
- Xiaomi 15 Ultra USB 연결 상태에서 Debug APK 재설치 성공 확인
- 앱 업데이트 후 `Jarvis 대기 준비됨` 시작 알림 표시 확인
- Xiaomi 15 Ultra에서 `JarvisVoiceService` foreground 실행, 접근성 서비스 바인딩, 배터리 최적화 예외 등록 확인
- 2026-06-20 21:26 KST 기준 약 15분 유지 테스트에서 프로세스, foreground notification, 접근성 바인딩, owner voice verification loop 유지 확인
- `자비스 깨어나` activation 발화가 확인된 뒤에만 30초 command window를 열고, 이 window 안에서는 호출어 없는 후속 명령을 허용하도록 보정
- activation 후속 명령 인식 지연을 줄이기 위해 다음 listening 예약을 즉시 실행으로 낮추고, command window 안의 `SpeechRecognizer` silence timeout을 단축
- idle 대기 중 `AudioRecord`를 계속 열어 두고 local activation ASR이 `자비스 깨어나`를 먼저 찾도록 변경했다. `AudioRecord`는 `VOICE_RECOGNITION` source를 우선 사용하고 실패 시 `MIC`로 fallback한다. activation이 확인된 최근 3.6초 rolling audio를 owner voice verification 입력으로 사용하고, speaker embedding 입력은 내부에서 최소 1.2초로 padding한다.
- 짧은 `자비스` 호출어가 2초 window 안의 무음에 묻히지 않도록 owner voice gate에서 RMS 기반 말소리 구간 정리와 근접 점수 2회 연속 통과 정책을 추가함
- Xiaomi 15 Ultra 실기기 로그에서 짧은 호출 발화 점수가 낮게 나오는 케이스가 확인되어, 다중 embedding 프로필에서 450ms 이상 말소리의 similarity `0.36` 이상 고신뢰 점수는 1회 통과시키고, `0.28` 이상 근접 점수는 2회 연속 통과시키도록 보정함. 850ms 이상 말소리의 단발 soft score는 similarity `0.16` 이상일 때 통과시키고 낮은 점수 경로는 400ms 이상 말소리에서 similarity `0.14` 이상 4회 연속일 때만 통과시키도록 보정함. 단, near/soft 보조 통과는 activation ASR이 의미 있는 텍스트를 만들 수 있도록 peak RMS `0.0035` 이상일 때만 허용한다. 짧은 발화의 점수 흔들림을 흡수하기 위해 soft score 연속 판정 중간에 similarity `0.10` 이상 애매한 점수 1회까지 허용함
- owner voice gate가 일정한 배경음을 계속 말소리로 판단하지 않도록, 인증 시에는 noise floor 대비 피크가 충분한 음성 구간만 speaker embedding으로 계산하도록 보정함
- idle local activation ASR은 sherpa-onnx 한국어 streaming ASR의 activation 전용 hotwords recognizer로 `자비스 깨어나`를 먼저 확인한다. 이후 같은 rolling audio의 owner voice score가 strict/high-confidence/near 기준을 통과한 경우에만 live command window를 연다. live command window는 Android 기본 `SpeechRecognizer` partial/final 결과를 우선 사용한다.
- Android `SpeechRecognizer`가 command window 안에서 실패하거나 사용할 수 없으면 local command ASR fallback을 1회 시도하도록 변경
- `종료`, `홈`, `뒤로`는 현재 앱만 제어하고 Jarvis command window는 닫지 않도록 변경
- `멈춰`는 Jarvis 서비스를 중지하지 않고 현재 command window만 닫아 이후 `자비스`로 다시 깨울 수 있도록 변경
- Jarvis 서비스는 한 번 시작되면 재부팅 전까지 foreground service로 유지하며, 앱 UI에서도 서비스 중지 버튼을 제공하지 않도록 변경
- 접근성 서비스가 살아 있는데 `JarvisVoiceService` foreground service가 없는 상태를 줄이기 위해, 소유자 목소리와 마이크 권한이 준비된 경우 접근성 watchdog이 음성 서비스를 자동 재시작하도록 변경
- Jarvis 서비스 실행 중에는 마이크 점유 충돌을 피하기 위해 소유자 목소리 재등록 시작 시 음성 서비스를 잠시 중지한다. 등록 중에는 watchdog 자동 재시작을 막고, 등록이 성공하면 Jarvis 음성 서비스를 다시 시작한다.
- command window의 watchdog timeout을 12초로 늘려 명령 대기 중 불필요한 STT 재시작을 줄이고, partial 명령 실행 후 다음 리스닝 전환 대기를 80ms로 단축함
- 카메라 세션 command window는 서비스 레벨 30초 hard deadline으로 관리하며, STT 재시도나 local fallback이 이 시간을 넘겨 명령 대기 상태를 연장하지 못하도록 변경
- 음성 인식 속도 개선 준비를 위해 `JarvisLatency` trace 로그를 추가하고, Android STT/local ASR/명령 실행/접근성 수신 구간을 같은 trace id로 측정할 수 있게 변경
- 실기기 로그에서 live local ASR이 입력 speech/RMS를 빠르게 잡아도 command text를 안정적으로 생성하지 못하는 케이스가 확인되어, live command 1차 경로는 Android STT로 되돌리고 owner audio/local fallback 경로만 sherpa-onnx local ASR을 사용하도록 조정함
- local ASR은 6초 live listen timeout만 기다리지 않고, 최소 발화/최소 청취 조건을 만족한 뒤 240ms trailing silence가 감지되면 final decode로 넘어가도록 보정함
- local ASR 종료 시 `local_complete` 이벤트에 endpoint, local elapsed, active speech, trailing silence, peak/mean RMS를 기록해 실제 빠른 종료 여부와 음성 입력 레벨을 리포트에서 확인할 수 있게 함
- 2026-06-21 실기기 trace에서 owner gate 통과 발화와 command window 입력 peak RMS가 `0.005`대인 케이스가 확인되어, live local ASR 말소리 판정 기준을 `0.0035` RMS로 낮췄다. 이후 `VOICE_RECOGNITION` source 적용 후에도 낮은 입력이 반복되어 command fallback 기준은 `0.0012`, activation 기준은 `0.00022` RMS로 낮췄고, ASR gain 적용 시작점도 `0.00008` RMS로 낮췄다. activation ASR은 짧은 파편을 너무 빨리 endpoint 처리하지 않도록 최소 active speech 560ms, trailing silence 600ms로 보정했다.
- 낮은 입력 음량에서 sherpa-onnx local ASR 텍스트가 비는 문제를 줄이기 위해, 원본 RMS가 `0.00008` 이상인 구간은 ASR 입력에만 목표 RMS `0.04`, 최대 `30x` gain을 적용하고 원본 RMS와 gain을 trace에 남김
- Jarvis 자체 확인음이 local ASR에 녹음되어 첫 명령을 방해하지 않도록 소유자 확인 직후에는 추가 대기 없이, 카메라 세션 명령 처리 직후에는 80ms 뒤에 다음 리스닝을 시작함
- idle wake 경로를 owner gate-first에서 activation ASR-first로 전환했다. local activation ASR이 `자비스 깨어나`를 잡으면 해당 rolling audio를 owner verification에 넣고, owner score가 통과한 경우에만 command window를 연다.
- 2026-06-21 Xiaomi 15 Ultra 실기기 trace에서 owner gate는 통과했지만 activation hotwords ASR이 `자비스 깨어나`를 `다비스때어나`, `아에스에어나`로 반환한 케이스가 확인되어 넓은 fuzzy equivalent를 실험했다. 이후 무발화 false activation이 재현되어 현재 live 경로에서는 호출어와 activation 단어가 함께 잡힌 제한된 equivalent만 허용하고, `아에스에어나` 같은 넓은 fuzzy 결과는 거절한다.
- 2026-06-21 live trace에서 streaming activation ASR이 같은 발화를 `깨우나`로만 반환했지만 저장 WAV replay는 `아비스깨어나`와 strict owner score로 통과하는 케이스가 확인됐다. 이에 따라 idle activation은 60초 `AudioRecord` 세션을 유지하고, 최근 3.6초 rolling audio를 0.8초마다 buffered hotword decode로 재확인한다. ASR stream은 마이크를 닫지 않고 최대 8초 segment 단위로만 재시작한다. 단, `깨어나`, `깨우나`, `깨워나`, `때어나`처럼 activation 동사만 남은 결과는 더 이상 live wake로 인정하지 않는다.
- 2026-06-21 idle wake 보조 경로로 Android 기본 `SpeechRecognizer`를 추가했지만, 실기기에서 Android STT wake가 5초 단위로 열리고 닫히며 우상단 privacy chip 깜빡임과 `NO_MATCH`를 반복했다. 따라서 idle 기본 경로는 local activation ASR로 고정하고, Android STT wake는 local activation을 시작할 수 없을 때만 fallback으로 사용한다.
- debug command window는 기존 recognizer cancel 직후 busy가 발생하지 않도록 1000ms settle 후 command STT를 시작한다.
- 2026-06-21 Xiaomi 15 Ultra 화면 켠 상태 live check에서 Android STT wake는 발화 시작/끝을 감지했지만 `NO_MATCH`를 반환했고, snapshot local replay는 485ms 안에 완료됐지만 text가 비어 accepted=0이었다. 이어서 local activation 세션이 시작되는 fallback은 확인했으나, 저장 WAV replay도 accepted=0으로 남고 상태 overlay는 확인되지 않았다. MIC source 우선 실험은 RMS를 높였지만 continuous noise를 말소리로 잡고 owner score도 낮아 되돌렸다. 이 시점의 검증 상태는 `FAIL_NO_ACCEPTED_ACTIVATION_REPLAY`였고, 이후 strict acoustic wake fallback으로 대체됐다.
- 2026-06-21 16:45 KST 정확 타이밍 live check에서도 Android STT wake는 4.36초 snapshot과 peak RMS `0.050`대 발화를 잡았지만 `NO_MATCH`를 반환했다. 같은 snapshot의 local replay는 text가 비었고, fallback local activation session은 `응.`, `진자` 같은 조각만 반환해 activation equivalent로 통과하지 못했다. windowed owner replay는 일부 저장 캡처에서 owner near 기준 통과를 확인했지만, ASR accepted가 0이라 command window는 열리지 않았다. 따라서 남은 병목은 owner threshold가 아니라 `자비스 깨어나`를 안정적으로 검출할 local activation ASR/KWS 품질이다.
- activation owner verification은 긴 rolling audio 전체만 계산하지 않고, 1.2초/1.6초/2.2초 최고 에너지 후보 구간을 함께 계산해 가장 높은 owner score를 사용한다. debug replay 로그는 `ownerFullScore`, `ownerWindowStartMs`, `ownerWindowMs`, `ownerWindows`를 출력해 긴 캡처 잡음 때문에 전체 score가 낮아진 경우와 ASR 자체 실패를 분리한다. `scripts/jarvis-activation-replay.sh` timeout은 windowed replay 계산 시간을 고려해 180초로 늘린다.
- 2026-06-21 17:09 KST 저장 캡처 replay에서 ASR 실패를 우회하는 개인 wake phrase template fallback이 한 캡처를 통과시켰다. 이후 template-only wake와 accepted wake 캡처를 owner profile에 병합하는 실험이 무발화/주변음 false activation까지 strict owner 통과로 강화하는 문제가 확인되어 debug calibration에서 제거했다. 앱은 저장된 embedding 중 등록 기반 앞 8개까지만 사용한다.
- 2026-06-21 18:32 KST 이후 live activation은 strict acoustic wake fallback을 사용한다. ASR 텍스트가 `자비스 깨어나`로 나오지 않아도 등록 문구 `jarvis-owner-enroll-last.wav` 기반 template distance `<=0.22`, 후보 peak RMS `>=0.006`, 95퍼센타일 robust speech segment, owner score `>=0.50`, owner speech `>=500ms`가 동시에 통과하면 `*_acoustic_wake` endpoint로 command window를 연다. 이 경로도 template-only wake가 아니며 owner voice verification 없이는 overlay/command STT를 시작하지 않는다.
- 2026-06-21 18:32 KST Xiaomi 15 Ultra live check에서 `rolling_buffer_live_buffered_acoustic_wake`가 `자비스 깨어나`를 검출했고 owner verification `STRICT score=0.7634`, `owner_audio_activation`, `ready_for_speech`, `COMMAND_READY` overlay까지 확인했다. 같은 APK에서 `scripts/jarvis-idle-guard.sh 120`은 무발화 command STT 미진입을 통과했다.
- 2026-06-21 19:04 KST Xiaomi 15 Ultra debug command window에서 Hyper Island overlay를 실기기 캡처로 확인했다. 글자 크기는 유지하고 검은 pill 여백만 줄인 뒤 `JarvisStateIndicator overlay_visible state=COMMAND_READY width=446 height=84 gap=73 x=34 y=21 left=136 right=203` 로그가 남았고, 화면에서는 시안 `JARVIS`와 초록 `LISTENING` 전체 텍스트가 잘림 없이 표시됐다.
- live command는 Android 기본 STT가 먼저 듣고 partial 결과에서 빠른 명령을 즉시 실행한다. Android STT가 실제 발화를 감지한 뒤 실패했을 때만 local ASR fallback이 6초 동안 첫 명령 발화를 기다리고, 아무 말이 없거나 텍스트 없는 360ms 미만의 짧은 소리만 있으면 local 대기를 이어간다. Android STT가 발화 시작/partial 없이 `NO_MATCH` 또는 timeout을 반환하면 실패 피드백 없이 command window 안에서 조용히 다시 듣는다.
- Android STT command window는 짧은 명령 구문에 맞춰 `LANGUAGE_MODEL_WEB_SEARCH`, minimum input 300ms, possibly-complete silence 150ms, complete silence 300ms를 사용한다. 빠른 실행은 final보다 partial command path로 우선 달성한다.
- local activation ASR은 idle에서 Android STT를 열기 전 activation phrase만 확인한다. local activation ASR에서 `자비스 깨어나`를 잡고 owner verification까지 통과하면 30초 command window와 Android command STT를 시작하고, 그렇지 않으면 overlay/비프음 없이 idle activation 대기로 돌아간다.
- activation 디버깅은 반복 수동 발화를 요구하지 않도록 변경한다. debug APK는 idle activation ASR이 사용한 원본 rolling WAV와 JSON 메타데이터를 cache `jarvis-activation-attempts/`에 자동 저장하며, `scripts/jarvis-wake-diagnose.sh`는 프로필/저장 샘플 replay/logcat을 한 번에 묶어 wake 실패 원인을 분류한다. `scripts/jarvis-activation-replay.sh`는 저장된 WAV들을 현재 APK의 local activation ASR로 재디코딩하고 owner score를 함께 기록하며, `scripts/jarvis-activation-captures.sh`는 저장 샘플을 host `/tmp`로 가져온다. live wake 리포트는 `android_activation_local_replay_complete`와 `android_activation_local_replay_detected`를 함께 세어 Android STT 실패 snapshot이 local replay에서 복구됐는지 확인한다.
- idle guard와 command window timeout은 사용자의 발화 없이 검증한다. `scripts/jarvis-idle-guard.sh 20`은 idle 상태에서 accepted activation 또는 command STT `listen_start`/`ready_for_speech`가 발생하지 않는지 검사한다. `activation_phrase_missing`과 `activation_owner_rejected`는 호출어 또는 owner voice가 아니어서 조용히 거절된 정상 idle 경로로 본다. 성공 시에는 요약과 원본 logcat 파일 경로만 출력하고, 실패 시에는 원인 이벤트를 함께 출력한다. `scripts/jarvis-command-window-timeout.sh 30`은 debug APK의 no-display `JarvisDebugCommandWindowActivity`로 command window를 30초 열고 command window close 이후 `ready_for_speech`가 다시 발생하지 않는지 검사한다. close 이벤트는 일반 timeout, active speech grace 만료, listen timeout 만료, deadline 이후 speech error/final/local no-command 종료를 포함한다. `scripts/jarvis-command-window-timeout.sh 30 open_camera`처럼 command id를 넘기면 실제 명령 처리 후 command window가 다시 열린 뒤 닫히는 경로를 검증한다.
- 2026-06-21 실기기 자동 검증에서 `scripts/jarvis-idle-guard.sh 20`, `scripts/jarvis-command-window-timeout.sh 30`, `scripts/jarvis-command-window-timeout.sh 30 open_camera`, `scripts/jarvis-command-window-timeout-matrix.sh 30`, `scripts/jarvis-overlay-timeout.sh 30 open_camera`, `scripts/jarvis-ready-feedback-once.sh 30`이 모두 통과했다. matrix 검증은 기본 command window, `open_camera`, `open_front_camera`, `open_rear_camera`, `take_photo`, `home` 각각에서 command window close 이후 `ready_for_speech` 재시작이 없는지 확인한다. overlay 검증은 `COMMAND_READY` 표시 후 command window close 뒤 `overlay_hidden`이 발생하는지 확인한다. ready feedback 검증은 command window 안에서 Android STT가 여러 번 `ready_for_speech`로 재시작돼도 `feedback=command_ready`가 1회만 발생하는지 확인한다.
- owner audio activation에서 실제로 사용하는 `CommandInterpreter.isActivationWakeAsrEquivalent()`는 `자비스 깨어나` 계열과 호출어가 포함된 제한적 ASR equivalent만 통과시킨다. `깨어나`, `깨우나`, `깨워나`, `때어나` 같은 activation 단어 단독 결과는 거절한다. `자비스 실행`, `자비스`, `헤이 자비스`, `헤이 자비스 깨어나`, `자비스 카메라 실행`, `자비스 깨어나 카메라 실행`도 unit test로 거절을 고정한다.
- 2026-06-21 실기기 trace에서 AiAi STT partial `카메라 실행해 줘`가 `open_camera`로 파싱되고 카메라 foreground 실행이 확인되었다. 해당 trace의 `speech_parse`는 648ms였다.
- 사용자 준비음/진동은 owner authorization 직후가 아니라 `자비스 깨어나` activation으로 열린 Android STT `ready_for_speech` callback 시점에 낸다.
- command listening timeout이 발화 진행 중인 Android STT를 먼저 취소하지 않도록, `speech_begin` 이후에는 active speech deadline grace가 command window 종료를 담당한다. active speech grace는 3.5초다.
- owner voice score만으로 command window를 열지 않는다. activation hotwords ASR 결과가 `자비스 깨어나`이거나 strict acoustic wake fallback이 같은 rolling audio에서 template과 owner voice를 모두 통과할 때만 `JARVIS LISTENING` Hyper Island overlay와 command STT가 시작된다.
- 2026-06-21 idle UX 보정으로 `자비스` 단독, `자비스 카메라 실행`, 주변 대화처럼 activation phrase가 아닌 audio는 `activation_phrase_missing`으로 끝나며 Jarvis 앱의 준비음과 Android command STT가 발생하지 않도록 변경했다.
- 2026-06-21 wake debug trace에서 사용자의 짧은 `자비스` 발화로 추정되는 구간이 peak RMS `0.0009`대였지만 기존 verification 최소 peak RMS `0.002`에 막혀 `PEAK_BELOW_MIN`으로 거절되는 문제가 확인되었다. 등록 경로에서도 `자비스 깨어나` 반복 녹음이 peak RMS `0.00178`로 들어와 embedding 생성에 실패했기 때문에 등록/일반 embedding 최소 peak RMS를 `0.0012`, 최소 active RMS를 `0.00085`로 낮췄다. owner verification 경로는 최소 peak RMS `0.00075`, 최소 active RMS `0.00050`, floor 대비 최소 상승폭 `0.00022`로 분리해 저음량 호출을 받아들이되 일정한 배경음은 floor contrast로 계속 거절한다.
- command window 전환 지연과 trace 혼선을 줄이기 위해 `scheduleListening`과 `scheduleNextCapture`는 익명 delayed lambda를 누적하지 않고 단일 runnable을 재예약한다.
- command window 안에서 `자비스 깨어나` activation partial이 다시 들어와도 command-ready 표시나 30초 window 갱신을 수행하지 않는다. wake phrase는 idle activation 전용이고, command window 연장은 명시적으로 유지 대상인 명령 처리 결과로만 발생한다.
- debug profile/enrollment 스크립트는 request id로 자기 실행 로그만 읽고 전체 logcat을 지우지 않는다. `jarvis-command-trace.sh`는 측정 전 `profile_configured=true`, `profile_embeddings>=2`, `profile_phrase_id=jarvis_activation_v3`를 확인하고, 실패하면 latency 로그를 지우지 않는다.
- 앱 UI에서 소유자 목소리 재등록을 시작하면 실행 중인 Jarvis 음성 서비스를 잠시 중지하고, 등록 중에는 접근성 watchdog의 자동 재시작을 `owner_voice_enrollment_active`로 억제한다.
- 저장된 소유자 목소리 embedding이 2개 미만이거나 `자비스 깨어나` activation 문구로 등록되지 않은 구버전 프로필은 `profile_configured=false`로 취급하고, Jarvis 음성 서비스 시작과 접근성 watchdog 자동 재시작을 차단한다. 재등록 성공 후에는 Jarvis 음성 서비스를 자동으로 다시 시작한다.
- 속도 측정 리포트는 activation ASR, owner verification, Android STT, 접근성 실행 구간을 분리해 출력한다.
- 접근성 서비스가 같은 프로세스에 살아 있으면 `CommandBus`는 broadcast 전에 direct receiver를 호출한다. trace는 `transport=direct|broadcast`, `speech_access`, `command_access`를 기록하고 기본 audit은 `command_access<=1200ms`를 함께 검사한다.
- 한국어 streaming ASR 모델은 Gradle `downloadKoreanStreamingAsrModel` 태스크가 Hugging Face에서 받아 `app/build/generated/sherpaAssets`에 캐시하고 APK asset에 포함한다.
- sherpa-onnx 한국어 streaming 모델은 공식 예제의 `model_type=""`, `modeling_unit="cjkchar"` 설정을 따른다. 앱에서는 `modelType`을 강제로 지정하지 않고 `modelingUnit=cjkchar`만 설정한다.
- activation 확인은 일반 greedy local ASR과 분리해 `modified_beam_search`, `jarvis-activation-hotwords.txt`, `hotwordsScore=8.0`, `maxActivePaths=8`을 쓰는 전용 recognizer로 수행한다. idle에서는 이 recognizer가 streaming으로 먼저 activation phrase를 찾고, 최근 3.6초 rolling audio를 0.8초마다 buffered hotword decode로 재확인한다. debug replay와 live buffered decode에는 500ms zero tail padding을 추가한다. activation ASR이 호출어와 activation 단어가 함께 있는 `자비스 깨어나` 계열 activation phrase 또는 제한적 equivalent를 반환하고 owner voice verification이 통과할 때만 command window를 연다.
- debug owner enrollment service는 마지막 6초 녹음을 앱 cache의 `jarvis-owner-enroll-last.wav`로 저장하고 `debug_wav` 로그에 경로를 남긴다.
- 2026-06-21 현재 Xiaomi 15 Ultra 저장 프로필은 `profile_configured=true`, `profile_embeddings=8`, `profile_phrase_id=jarvis_activation_v3`로 확인됐다. 구버전 v1 프로필은 더 이상 현재 기기 상태가 아니다.
- 2026-06-20 리팩토링으로 비대했던 음성/접근성/UI 클래스의 책임을 `OwnerVoiceGate`, `LocalCommandSession`, `JarvisCommandExecutor`, `JarvisNotificationController`, `CameraAccessibilityController`, `AccessibilityNodeMatcher`, `OwnerVoiceEnrollmentController`로 분리했다.
- 명령 가능 여부를 사용자가 확실히 알 수 있도록 소리, 진동, 접근성 overlay 기반 Jarvis 상태 표시를 추가했다.

다음 우선순위:

1. 실제 카메라 화면에서 셔터/필터/전환 좌표 보정
2. 재부팅 후 시작 알림 및 HyperOS 자동 시작/배터리 설정 실기기 검증
3. 다른 사람 목소리와 TV/주변 대화로 acoustic wake fallback false activation 장기 검증
4. activation phrase, speaker verification, command recognition을 하나의 streaming raw audio pipeline으로 합치는 장기 구조 검토

## 2. Quick Context For Future Work

다음 작업자는 먼저 이 순서로 확인한다.

1. `README.md`: 설치와 실행 절차 확인
2. `docs/PROJECT_SPEC.md`: 목표, 제약, 명령 모델 확인
3. `app/src/main/java/com/personal/jarvis/CommandBus.kt`: 현재 내부 명령 확인
4. `app/src/main/java/com/personal/jarvis/CommandInterpreter.kt`: 한국어 음성 문장 매핑 확인
5. `app/src/main/java/com/personal/jarvis/JarvisCommandExecutor.kt`: 명령 실행 위치, 중복 방지, command window 유지 정책 확인
6. `app/src/main/java/com/personal/jarvis/CameraAccessibilityController.kt`: 실제 카메라 화면 조작 로직 확인
7. `app/src/main/java/com/personal/jarvis/AccessibilityNodeMatcher.kt`: 접근성 노드 탐색/스코어링 기준 확인
8. `app/src/main/java/com/personal/jarvis/JarvisFeedbackController.kt`: 상태별 소리/진동/overlay broadcast 정책 확인

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

- Idle 상태에서 `자비스 깨어나`: 소유자 목소리와 activation phrase를 확인한 뒤 30초 command window 열기
- `카메라 열어`: 기본 카메라 앱 실행
- `카메라 실행`: 기본 카메라 앱 실행
- `카메라 셀피 모드로 실행해`: 기본 카메라 앱을 전면 카메라 힌트와 함께 실행
- `셀피`: 기본 카메라 앱을 전면 카메라 힌트와 함께 실행
- `전면`: 기본 카메라 앱을 전면 카메라 힌트와 함께 실행
- `셀피 모드`: 기본 카메라 앱을 전면 카메라 힌트와 함께 실행
- `카메라 후면 모드로 실행해`: 기본 카메라 앱을 후면 카메라 힌트와 함께 실행
- `후면`: 기본 카메라 앱을 후면 카메라 힌트와 함께 실행
- `후면 모드`: 기본 카메라 앱을 후면 카메라 힌트와 함께 실행
- `후면으로 전환`: 기본 카메라 앱을 후면 카메라 힌트와 함께 실행
- `찍어`: 현재 화면에서 셔터 버튼 탭
- `사진 찍기`: 카메라 앱 실행 후 셔터 탭
- `카메라 찍어`: 카메라 앱 실행 후 셔터 탭
- `필터`: 카메라 앱 필터/효과 UI 열기
- `전면 카메라`: 기본 카메라 앱을 전면 카메라 힌트와 함께 실행
- `카메라 전환`: 전후면 카메라 전환
- `카메라 종료해`: 카메라 앱에서 홈으로 이동
- `종료`: 현재 카메라 세션에서 홈으로 이동
- `화면 켜`: 꺼진 화면을 깨워 잠금화면 표시
- `화면 꺼`: 접근성 잠금화면 전역 액션으로 화면 끄기/잠금
- `뒤로`: Android 뒤로가기
- `홈`: Android 홈으로 이동
- `멈춰`: 현재 command window 종료 후 idle activation 대기로 복귀

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
- LocalCommandRecognizer: idle activation phrase와 Android STT 실패 fallback을 sherpa-onnx streaming ASR로 인식
- SpeechRecognizer: command window 안의 live 명령을 Android STT partial/final 결과로 우선 인식
- AccessibilityService: 화면 노드 탐색, 클릭, 전역 동작 수행
- Intent: 기본 카메라 앱 실행
- BroadcastReceiver: 재부팅 또는 앱 업데이트 후 Jarvis 시작 알림 표시

접근성 서비스는 사용자가 설정에서 직접 켜야 하며, 설치만으로 자동 활성화할 수 없다.

전면/후면 카메라 실행은 Android 카메라 인텐트에 렌즈 방향 힌트 extra를 넣은 뒤, 접근성 서비스에서 Xiaomi 카메라의 `com.android.camera:id/v9_camera_picker` 노드를 읽어 현재 렌즈를 확인한다. content description은 실기기에서 `전후면 카메라 전환,후면` 또는 `전후면 카메라 전환,전면` 형태로 노출된다. 현재 렌즈가 목표와 다를 때만 전환 버튼을 클릭한다.

Android 14(API 34)+에서 `RECORD_AUDIO`는 while-in-use 권한으로 취급된다. 현재 앱은 `targetSdk=35`이므로 `BOOT_COMPLETED` receiver에서 microphone foreground service를 직접 시작할 수 없다. 부팅 후 자동 대기는 다음 구조로 처리한다.

1. `JarvisBootReceiver`가 `BOOT_COMPLETED` 또는 `MY_PACKAGE_REPLACED`를 받는다.
2. receiver는 마이크 서비스를 직접 시작하지 않고 `Jarvis 대기 준비됨` 알림을 띄운다.
3. 사용자가 알림의 `Jarvis 시작`을 누르면 notification interaction으로 `JarvisVoiceService`를 foreground service로 시작한다.
4. 소유자 목소리와 마이크 권한이 준비되어 있고 접근성 서비스가 연결되어 있으면 `JarvisAccessibilityService` watchdog이 앱 업데이트, 서비스 kill, 접근성 재바인딩 이후 `JarvisVoiceService`가 없는 상태를 감지해 다시 시작한다.
5. 한 번 시작된 뒤에는 foreground notification, `START_STICKY`, 접근성 watchdog 조합으로 계속 대기한다.

접근성 watchdog은 Android 정책 우회가 아니라 사용자가 켜 둔 접근성 서비스 생명주기 안에서 복구를 시도하는 보강이다. Android/HyperOS가 백그라운드 foreground service 시작을 제한하면 boot receiver의 `Jarvis 대기 준비됨` 알림 탭이 fallback이다.

완전한 “부팅 직후 무터치 마이크 대기”는 일반 앱 권한만으로는 신뢰할 수 없다. 장기적으로는 기본 Assistant/VoiceInteractionService 역할, device-owner/system app, 또는 사용자가 명시적으로 실행한 foreground session을 유지하는 방식 중 하나를 검토한다.

## 7. Current Architecture

```text
사용자 음성
  ↓
JarvisVoiceService
  ├─ JarvisVoiceServiceStarter: UI/watchdog 공통 startForegroundService helper
  ├─ LocalActivationSession → LocalCommandRecognizer: idle activation phrase streaming ASR
  ├─ OwnerVoiceEngine: activation rolling audio의 소유자 목소리 확인
  ├─ OwnerVoiceGate → OwnerVoiceEngine: local activation ASR 미사용 시 fallback owner gate
  ├─ LocalCommandSession → LocalCommandRecognizer: Android STT 실패 시 command fallback
  ├─ SpeechRecognitionIntentFactory → SpeechRecognizer: command window 안의 live Android STT
  ├─ JarvisNotificationController: foreground notification 상태 표시
  ├─ JarvisFeedbackController → JarvisStateBus: 소리/진동/상태 broadcast
  └─ JarvisCommandExecutor: 내부 명령 실행/전달
  ↓
CommandInterpreter
  ↓
CommandBus
  ↓
JarvisAccessibilityService
  ├─ JarvisStateIndicatorController: 화면 overlay 상태 표시
  └─ CameraAccessibilityController
      ↓
      AccessibilityNodeMatcher 또는 좌표 fallback 탭
```

### Components

| File | Role |
| --- | --- |
| `MainActivity.kt` | 권한 요청, 접근성 설정 진입, Jarvis 시작 UI |
| `OwnerVoiceEnrollmentController.kt` | 소유자 목소리 등록 workflow, 진행률, 완료/실패 callback |
| `JarvisBootReceiver.kt` | 부팅/앱 업데이트 후 Jarvis 시작 알림 표시 |
| `JarvisVoiceService.kt` | 포그라운드 음성 인식 서비스의 상태 전환 orchestration |
| `JarvisVoiceServiceStarter.kt` | 앱 UI와 접근성 watchdog에서 공통으로 쓰는 음성 서비스 시작 helper |
| `debug/JarvisDebugStartActivity.kt` | debug APK 전용 ADB service start 진입점 |
| `debug/JarvisDebugOwnerEnrollActivity.kt` | debug APK 전용 ADB owner voice 재등록 진입점 |
| `debug/JarvisDebugProfileStatusActivity.kt` | debug APK 전용 owner voice profile 상태 로그 진입점 |
| `OwnerVoiceGate.kt` | local activation ASR을 사용할 수 없을 때의 fallback owner voice verification 관리 |
| `OwnerVoiceEngine.kt` | sherpa-onnx speaker embedding 묶음 생성, 녹음, cosine 검증 |
| `OwnerVoiceStore.kt` | 소유자 음성 embedding 묶음 저장 |
| `LocalCommandRecognizer.kt` | sherpa-onnx 한국어 streaming ASR 기반 activation 및 command fallback 인식 |
| `LocalActivationSession.kt` | idle activation ASR 실행 스레드와 rolling audio 상태 관리 |
| `LocalCommandSession.kt` | 로컬 명령 ASR 실행 스레드와 상태 관리 |
| `SpeechRecognitionIntentFactory.kt` | Android `SpeechRecognizer` intent/timing option 생성 |
| `JarvisCommandExecutor.kt` | 내부 명령 실행, 중복 실행 방지, 카메라 세션 유지 정책 |
| `JarvisNotificationController.kt` | foreground notification channel, 표시 문구, notification update |
| `JarvisFeedbackController.kt` | Jarvis 상태별 소리, 진동, 상태 broadcast |
| `JarvisVoiceState.kt` | Jarvis 상태 enum |
| `JarvisStateBus.kt` | 음성 서비스에서 접근성 서비스로 상태 전달 |
| `CommandInterpreter.kt` | 인식된 문장을 내부 명령으로 변환 |
| `CommandBus.kt` | 앱 내부 명령 브로드캐스트 |
| `JarvisAccessibilityService.kt` | 접근성 서비스 생명주기, 명령 dispatch, 음성 서비스 watchdog 복구 |
| `JarvisStateIndicatorController.kt` | 접근성 overlay로 Jarvis 상태 표시 |
| `CameraAccessibilityController.kt` | Xiaomi 기본 카메라 접근성 자동화 recipe |
| `AccessibilityNodeMatcher.kt` | 접근성 노드 키워드 검색, 스코어링, 최적 노드 선택 |
| `CameraLauncher.kt` | 기본 카메라 앱 실행 |
| `JarvisLatencyTrace.kt` | 음성 인식/명령 실행 latency trace 로그 |
| `ScreenController.kt` | 짧은 wake lock으로 화면 켜기 |
| `AndroidManifest.xml` | 권한, 서비스, 접근성 메타데이터 선언 |
| `res/xml/jarvis_accessibility_service.xml` | 접근성 서비스 설정 |

### App Automation Controller Architecture

Jarvis가 카메라 외의 앱을 제어하게 되면 앱별 자동화 로직은 별도 controller 파일로 분리한다. `JarvisAccessibilityService`는 접근성 서비스 생명주기와 내부 명령 dispatch만 담당하고, 실제 화면 탐색/버튼 탭/fallback 좌표는 앱별 controller가 담당한다.

예상 확장 구조:

```text
JarvisAccessibilityService
  ├─ CameraAccessibilityController
  ├─ GalleryAccessibilityController
  ├─ SettingsAccessibilityController
  └─ PhoneAccessibilityController
```

설계 원칙:

- 앱별 세부 자동화 로직은 `...AccessibilityController` 파일에 둔다.
- 여러 앱에서 공통으로 쓰는 접근성 노드 검색, 스코어링, 제스처 helper는 `AccessibilityNodeMatcher` 같은 공통 helper로 분리한다.
- `JarvisAccessibilityService`에는 command routing 이상의 로직을 누적하지 않는다.
- 두 번째 또는 세 번째 앱 controller가 추가되어 라우팅 패턴이 반복되면 `AccessibilityCommandController` interface와 controller registry 도입을 검토한다.
- 새 앱 제어를 추가할 때는 `CommandBus`, `CommandInterpreter`, `JarvisCommandExecutor`, 앱별 controller, README, 이 문서를 함께 갱신한다.

### Voice State Feedback

Jarvis는 Android 상태바의 초록색 마이크 표시만으로 상태를 판단하지 않는다. 마이크 표시의 의미는 “마이크 사용 중”뿐이므로, 명령 가능 여부는 Jarvis 자체 feedback으로 표시한다. 다만 idle, owner verification, activation phrase 확인처럼 사용자가 즉시 수행할 액션이 없는 passive 상태에서는 화면을 계속 가리지 않도록 overlay를 표시하지 않는다.

상태별 사용자 feedback:

| State | Overlay | Sound/Vibration | Meaning |
| --- | --- | --- | --- |
| `COMMAND_READY` | 컷아웃 영역 Hyper Island pill `JARVIS [camera hole] LISTENING` | 확인음 2회, 짧은 진동 1회 | 호출어 없이 바로 명령 가능 |
| `COMMAND_PROCESSING` | 컷아웃 영역 Hyper Island pill `JARVIS [camera hole] WORKING` | 없음 | 명령을 실행 중 |
| `COMMAND_HANDLED` | 컷아웃 영역 Hyper Island pill `JARVIS [camera hole] DONE` | 확인음 1회, 짧은 진동 1회 | 명령 처리 완료, 다음 명령 가능 |
| `COMMAND_FAILED` | 컷아웃 영역 Hyper Island pill `JARVIS [camera hole] FAILED` | 실패음 2회, 짧은 진동 2회 | 인식 실패 또는 command window 안의 무명령 |
| `IDLE` | overlay 제거 | 명시적 종료 시 낮은 안내음 1회, 짧은 진동 2회 | 명령 window 종료 |

Passive 상태 표시 정책:

- owner voice verification 중에는 overlay를 띄우지 않는다. 이 상태는 평상시 기본 대기 상태이므로 지속 표시하면 전체화면 앱 사용을 방해한다.
- activation phrase 확인 중에도 overlay를 띄우지 않는다. 사용자가 `자비스 깨어나`로 command window를 열었을 때만 명확히 표시한다.
- notification 문구는 passive 상태 진단용으로 유지하되, 사용자의 현재 화면 위에 지속적으로 노출하지 않는다.
- overlay text는 컷아웃 왼쪽 `JARVIS`, 컷아웃 오른쪽 상태 단어 구조를 사용한다. `COMMAND_READY`는 `LISTENING`, 처리/완료/실패는 `WORKING`/`DONE`/`FAILED`를 사용한다.
- `JARVIS` 라벨은 iOS system cyan 계열 `#64D2FF`로 고정하고, 상태 의미는 오른쪽 상태 단어 색, 소리, 진동으로 전달한다. 실패 직후 새 local/fallback listening이 시작되면 overlay는 다시 `LISTENING`으로 돌아가야 한다.
- 30초 command window 자동 만료는 사용자가 요청한 종료가 아니므로 실패음이나 종료음을 내지 않고 overlay만 제거한 뒤 idle activation 대기로 돌아간다.

Overlay는 `JarvisAccessibilityService`가 `TYPE_ACCESSIBILITY_OVERLAY`로 표시한다. 별도 “다른 앱 위에 표시” 권한을 요구하지 않지만, Jarvis 접근성 서비스가 켜져 있어야 카메라 앱 위에서도 보인다. 상단 위치와 높이는 Android status bar/display cutout inset과 centered display cutout bounds를 기준으로 계산해 컷아웃 영역 안에 배치한다. Android가 centered display cutout bounds를 제공하면 해당 폭에 작은 여백을 더해 중앙 camera gap을 잡고, bounds가 없으면 Xiaomi 15 Ultra의 중앙 punch-hole 형태에 맞춘 fallback gap을 사용한다. `JARVIS`와 상태 단어는 같은 weight 영역을 쓰지 않고 글자폭을 각각 측정해 비대칭 label width로 배치한다. view 전체는 gap 중심이 화면 중앙 cutout과 맞도록 `x` offset을 보정한다. 글자 크기는 고정 목표값을 유지하고, 검은 pill의 좌우/상하 여백과 camera gap만 줄여 compact하게 만든다.

### Latency Instrumentation

음성 인식 속도 개선은 체감이 아니라 `JarvisLatency` 로그의 구간별 시간으로 판단한다. 앱은 command window 안에서 하나의 발화 또는 명령 실행을 trace id 하나로 묶어 다음 이벤트를 기록한다.

```bash
adb logcat -v time -s JarvisLatency
```

요약은 repo root에서 다음 스크립트로 확인한다.

```bash
scripts/jarvis-latency-report.sh
```

소유자 목소리 재등록 후에는 debug APK 전용 no-display Activity를 호출하는 다음 스크립트로 프로필 상태를 확인한다. debug no-display Activity들은 main launcher task와 분리된 debug task affinity를 사용해, ADB 검증 중 기존 Jarvis 앱 화면이 앞으로 복귀하지 않게 한다. `profile_configured=true`, `profile_embeddings>=2`, `profile_phrase_id=jarvis_activation_v3`여야 `자비스 깨어나` activation latency 검증을 진행한다.

```bash
scripts/jarvis-profile-status.sh
```

`profile_embeddings`가 1이거나 `profile_phrase_id`가 비어 있으면 v1 단일 embedding fallback 또는 activation 이전 프로필이 남아 있는 상태다. 이 상태는 `profile_configured=false`로 보고 Jarvis 음성 서비스 시작을 차단하므로, `자비스 깨어나`를 말해도 대기 상태로 들어가지 않는다. USB 연결 상태에서는 다음 스크립트로 debug APK의 no-display owner enrollment Activity를 실행한다.

```bash
scripts/jarvis-owner-enroll.sh 6
```

스크립트가 `Speak now`를 출력하면 사용자는 6초 동안 `자비스 깨어나`를 여러 번 또렷하게 말한다. debug no-display Activity는 Android `Theme.NoDisplay` resume 제약을 피하기 위해 즉시 foreground enrollment service를 시작하고 종료한다. Service는 등록 중 `JarvisVoiceServiceStarter.setOwnerEnrollmentActive(true)`로 watchdog 재시작을 막고, 기존 `JarvisVoiceService`를 잠시 중지한 뒤 전체 녹음을 activation hotwords ASR로 확인한다. debug 등록은 반복 발화 ASR 결과 안에 `자비스 깨어나` 계열 activation phrase가 포함되면 통과시킨다. 실제 idle activation은 `자비스 깨어나` 계열 단일 activation phrase 또는 local activation ASR에서만 허용되는 제한된 equivalent를 인정하며, 일반 command parsing wake word로 확장하지 않는다. 등록 검증을 통과한 녹음만 `OwnerVoiceEngine.createEnrollmentEmbeddings`로 최소 2개 이상의 embedding을 만들고 `OwnerVoiceStore.saveEmbeddings`를 호출한다. 이때 `profile_phrase_id=jarvis_activation_v3`도 함께 저장한다. debug service는 마지막 녹음을 앱 cache의 `jarvis-owner-enroll-last.wav`로 저장하고 `debug_wav` 로그에 경로를 남긴다. embedding이 부족하면 `peakRms`, `meanRms`, 녹음 duration을 실패 로그에 남긴다. 완료 후에는 Jarvis 음성 서비스 시작을 다시 요청하고, 스크립트는 `jarvis-profile-status.sh`를 실행해 저장 상태를 확인한다. profile status/enrollment 스크립트는 request id로 자기 실행 로그만 필터링하고 전체 logcat을 비우지 않으므로 기존 `JarvisLatency` 로그를 보존한다.

새 실기기 측정은 `profile_configured=true`를 먼저 확인한 뒤 로그를 비우고 정해진 시간 동안 녹화해 바로 요약한다.

activation ASR 디버깅은 다음 순서로 진행한다.

```bash
scripts/jarvis-wake-live-check.sh 20
scripts/jarvis-wake-diagnose.sh
scripts/jarvis-activation-replay.sh
scripts/jarvis-activation-captures.sh
```

debug APK는 activation attempt마다 `jarvis-activation-attempts/activation-<timestamp>-<accepted|rejected>.wav`와 같은 이름의 `.json` 메타데이터를 cache에 저장한다. idle Android STT wake 보조 경로는 activation phrase가 잡힌 경우 같은 시점의 `IdleWakeAudioBuffer` snapshot을 owner verification에 사용하고, 실패 snapshot도 debug cache에 저장할 수 있다. local activation ASR은 60초 마이크 세션 안에서 최근 3.6초 rolling audio를 보존하고, activation phrase가 확인되면 같은 audio를 owner verification에 사용한다. activation이 아닌 segment는 `activation_asr_rejected_segment`로 기록한 뒤 마이크를 닫지 않고 계속 듣는다. `jarvis-wake-live-check.sh`는 실기기에서 `자비스 깨어나` wake만 검증한다. debug APK 기본값은 no-display debug Activity로 음성 서비스를 재시작해 activation 세션 시작점과 측정 시작점을 맞추며, 폰을 짧게 진동시킨 뒤 사용자가 말하도록 한다. 이 스크립트는 `android_activation_detected`, `android_activation_audio_snapshot`, `android_activation_disabled`, `activation_partial`, `owner_audio_activation`, `ready_for_speech`, `JarvisStateIndicator overlay_visible state=COMMAND_READY`를 함께 확인해 컷아웃 영역 `JARVIS LISTENING` overlay가 실제로 열렸는지 판정한다. `jarvis-wake-diagnose.sh`는 프로필 상태, 저장 WAV replay, 최신 캡처 메타데이터, 관련 logcat을 한 번에 묶어 wake 실패 원인을 분류한다. replay 스크립트는 사용자의 추가 발화 없이 저장 WAV를 현재 APK의 `LocalCommandRecognizer.recognizeBufferedActivation`으로 다시 실행해 text, accepted, endpoint, RMS, gain과 owner score를 리포트한다. 이 절차를 먼저 통과하지 못한 threshold/fuzzy rule 변경은 실시간 발화 테스트로 넘기지 않는다.

command window timeout은 다음 스크립트로 실기기에서 검증한다.

```bash
scripts/jarvis-idle-guard.sh 20
scripts/jarvis-command-window-timeout.sh 30
scripts/jarvis-command-window-timeout.sh 30 open_camera
scripts/jarvis-command-window-timeout-matrix.sh 30
scripts/jarvis-overlay-timeout.sh 30 open_camera
scripts/jarvis-ready-feedback-once.sh 30
```

`jarvis-idle-guard.sh`는 debug start Activity로 서비스를 깨운 뒤 지정 시간 동안 idle에서 accepted `owner_audio_activation`, `listen_start`, `ready_for_speech`가 발생하지 않는지 확인한다. `activation_phrase_missing`과 `activation_owner_rejected`는 호출어 또는 소유자 확인이 통과하지 않아 조용히 거절된 정상 idle 경로로 본다. 성공 시에는 요약과 원본 logcat 파일 경로만 출력하고, 실패 시에는 원인 이벤트를 함께 출력한다. `jarvis-command-window-timeout.sh`는 debug command window를 열고 지정 시간 뒤 command window close가 발생하는지 확인한다. 두 번째 인자로 command id를 지정하면 `command_complete keepWindow=true`가 먼저 발생했는지도 함께 검사한다. close 이후 같은 로그 안에 `ready_for_speech`가 다시 나타나면 command STT가 idle 전환 뒤 재시작된 것이므로 실패로 본다. close 이벤트는 일반 timeout, active speech grace 만료, listen timeout 만료, deadline 이후 speech error/final/local no-command 종료를 모두 포함한다. `jarvis-command-window-timeout-matrix.sh`는 기본 command window와 `open_camera`, `open_front_camera`, `open_rear_camera`, `take_photo`, `home`을 같은 기준으로 순차 검증한다. `jarvis-overlay-timeout.sh`는 command window에서 `JARVIS` overlay가 표시되고 command window close 뒤 `overlay_hidden`으로 사라지는지 확인한다. `jarvis-ready-feedback-once.sh`는 command window 안에서 Android STT가 여러 번 `ready_for_speech`로 재시작돼도 준비음은 한 번만 발생하는지 확인한다.

```bash
scripts/jarvis-command-trace.sh 45
```

스크립트는 `trace`, `total`, `path`, `command`, `status`, `owner_gate`, `listen`, `listen_ready`, `parsed`, `speech_parse`, `access`, `speech_access`, `command_access`, `bus`를 한 줄로 출력한다. 하위 줄에는 `activation_endpoint`, `activation_elapsed`, `activation_text`, `activation_owner`, `local_endpoint`, `local_elapsed`, `local_text`, `android_ready`, `speech_begin`, `speech_end`, `error`, `peak_rms`, `mean_rms`, `asr_gain`을 출력해 local activation ASR, owner voice verification, live Android STT, local fallback 병목을 분리한다. `listen`은 Android STT 시작 시점이고, `listen_ready`는 Android STT 시작부터 `ready_for_speech`까지의 시간이다. `parsed`는 trace 시작부터 명령 파싱까지의 누적 시간이고, `speech_parse`는 Android STT가 발화를 감지한 뒤 명령 파싱까지의 시간이다. `speech_access`는 Android STT 발화 시작부터 접근성 서비스 수신까지, `command_access`는 명령 파싱부터 접근성 서비스 수신까지의 시간이다. `jarvis-command-trace.sh`는 debug APK의 no-display `JarvisDebugStartActivity`를 먼저 호출해 Jarvis 서비스를 재시작하려고 시도한다. release APK에는 이 Activity가 포함되지 않으며, 자동 시작은 `JARVIS_START_DEBUG_ACTIVITY=0`으로 끌 수 있다. 현재 activation 등록 기준을 건너뛰고 강제로 측정하려면 `JARVIS_SKIP_PROFILE_CHECK=1`을 지정한다. 측정 중에는 `자비스 깨어나`를 말한 뒤 `JARVIS LISTENING` overlay 또는 준비음/진동이 나오면 `카메라 실행` 같은 짧은 명령을 말한다. 이 스크립트는 `status=command_complete`가 하나도 없으면 실패 종료하고 `activation_asr_complete`, `activation_owner_verified`, `owner_audio_activation`, `ready_for_speech`, `speech_begin`, `partial_results`, `command_parsed` 카운터로 어느 단계에서 멈췄는지 출력한다. 같은 경로의 `.diagnostic` 로그에는 `OwnerVoiceGate`, `JarvisVoiceService`, `JarvisLatency` 원본을 함께 저장해 owner voice 점수와 activation 거절 사유를 확인할 수 있다. `jarvis-latency-audit.sh <log>`는 기존 측정 로그와 `.diagnostic` 로그를 함께 읽어 `PASS`, `FAIL_LEGACY_PROFILE`, `FAIL_NO_ACTIVATION`, `FAIL_NO_SPEECH`, `FAIL_NO_COMMAND`, `FAIL_NO_COMMAND_COMPLETE`, `FAIL_SLOW`로 분류한다. `FAIL_LEGACY_PROFILE`은 저장된 owner profile이 현재 activation 등록 기준을 만족하지 않아 activation/command latency 판정에 사용할 수 없다는 뜻이고, `FAIL_NO_ACTIVATION`은 local activation hotwords ASR이 `자비스 깨어나` activation phrase를 확인하지 못했거나 owner verification이 거절했다는 뜻이다. 명령이 실행됐더라도 기본 기준 `speech_begin`이 있는 trace에서는 `speech_parse<=2500ms`, `speech_begin`이 없는 trace에서는 `parsed<=2500ms`, 접근성 실행이 있는 경우 `speech_access<=4000ms`와 `command_access<=1200ms`도 함께 검사한다. 기준은 `JARVIS_MAX_SPEECH_PARSED_MS`, `JARVIS_MAX_PARSED_MS`, `JARVIS_MAX_ACCESS_MS`, `JARVIS_MAX_COMMAND_ACCESS_MS` 환경 변수로 조정한다. 목표는 activation 후 command window 안의 정상 명령이 `path=local_activation_asr->android_stt`, `path=android_stt`, 또는 Android 실패 후 `path=local_asr`, `status=command_complete`로 끝나고, `speech_parse`, `speech_access`, `command_access`가 체감 지연을 설명할 수 있는 낮은 값으로 유지되는 것이다. `fallback_to_local` 또는 `local_asr`가 포함된 live trace는 Android STT가 발화를 감지한 뒤 실패해 local fallback을 탔다는 뜻이므로 별도 튜닝 대상으로 본다. `speech_idle_retry`는 command window 안에서 아무 발화가 감지되지 않아 조용히 다시 듣는 정상 idle retry로 본다.

주요 이벤트:

- `activation_listen_start`: idle local activation ASR 리스닝 시작
- `activation_asr_rejected_segment`: 60초 idle activation 세션 안에서 activation이 아닌 segment를 버리고 계속 듣는 경로. endpoint, elapsed, active speech, trailing silence, peak/mean RMS, ASR gain을 함께 기록
- `activation_asr_complete`: activation 문구가 확인됐거나 60초 idle activation 세션이 종료된 결과. endpoint, elapsed, active speech, trailing silence, peak/mean RMS, ASR gain을 함께 기록
- `activation_owner_verified`: activation rolling audio의 소유자 목소리 검증 완료
- `owner_audio_activation`: activation phrase와 owner voice가 모두 확인되어 command window를 여는 지점
- `owner_authorized`: fallback owner gate 경로에서 소유자 목소리 인증 통과
- `owner_audio_asr_start` / `owner_audio_asr_complete`: fallback owner gate 경로에서 owner gate 통과 직전 음성 window의 즉시 local ASR 시작/종료
- `listen_start`: Android STT 또는 local ASR 리스닝 시작
- `local_partial`: local ASR partial text 수신
- `local_complete`: local ASR 종료. endpoint, local elapsed, active speech, trailing silence, peak/mean RMS, ASR gain을 함께 기록
- `fallback_to_android`: local fallback이 명령을 못 잡아 Android STT fallback으로 전환
- `ready_for_speech`: Android `SpeechRecognizer` 준비 완료 callback. 사용자 준비음/진동은 이 시점에 발생
- `speech_begin` / `speech_end`: Android `SpeechRecognizer` 발화 시작/끝 callback
- `partial_results`: partial STT 결과 수신
- `activation_partial`: idle Android wake 보조 경로의 partial STT에서 activation 발화가 파싱되어 owner verification으로 넘긴 경우
- `partial_activation_complete`: partial activation 처리 완료
- `final_results`: final STT 결과 수신
- `command_parsed`: 명령 파싱 완료
- `command_execute_start` / `command_execute_return`: `JarvisCommandExecutor` 실행 진입/반환
- `accessibility_command_received`: 접근성 서비스가 `CommandBus` 명령을 수신. `transport=direct|broadcast`로 direct receiver와 broadcast fallback을 구분한다.
- `accessibility_command_dispatch_return`: 접근성 서비스 command dispatch 반환
- `command_complete`: Jarvis command window 정책까지 반영한 명령 처리 완료

음성 서비스 내부 이벤트의 `total=...ms`는 trace 시작부터 해당 이벤트까지의 누적 시간이고, `step=...ms`는 직전 이벤트 이후의 시간이다. `activation_asr_complete`의 `elapsedMs`는 idle activation ASR이 녹음/디코딩에 사용한 시간이고, `android_activation_local_replay_complete`는 Android wake 실패 snapshot을 local activation ASR로 재판정한 시간이다. `activation_owner_verified`는 같은 rolling audio의 owner score를 보여준다. `local_complete`의 `elapsedMs`는 local command fallback이 실제 녹음/디코딩에 사용한 시간이다. `accessibility_command_received`의 `totalMs`는 trace 시작부터 접근성 서비스 수신까지의 누적 시간이고, `busDelayMs`는 음성 서비스가 command를 보낸 뒤 접근성 서비스가 받은 지연이다. 접근성 서비스가 같은 프로세스에 등록되어 있으면 `transport=direct` 경로가 broadcast보다 먼저 사용된다. 2026-06-21 실기기 로그에서는 owner gate-first 경로가 주변 대화를 soft wake로 통과시키고 activation ASR이 빈 문자열/`응`을 반환하는 케이스가 반복되었다. 따라서 idle wake는 activation ASR-first 구조로 전환하고, command window live 1차 인식은 Android 기본 STT partial/final 결과로 수행하며, Android 발화 감지 후 실패 fallback에 local ASR을 사용한다.

### Wake/Sleep Recognition Lessons

`자비스 깨어나`와 `자비스 잠들어` 개선은 단순 threshold 조정 문제가 아니었다. 가장 큰 반복 패턴은 “말해도 안 깨어남”을 고치려고 wake 조건을 넓히면 “말하지 않아도 깨어남”이 생기고, false activation을 막으려고 조건을 다시 좁히면 다시 wake miss가 늘어나는 흐름이었다. 다음 작업에서는 이 두 증상을 같은 축의 threshold 문제로 보지 말고, activation phrase 검출, owner voice verification, command window lifecycle을 분리해서 봐야 한다.

실패 확률이 높았던 접근:

| 접근 | 문제 | 현재 기준 |
| --- | --- | --- |
| owner voice score만으로 command window 열기 | 소유자의 주변 대화, 짧은 소리, TV/환경음이 soft/near owner 점수를 통과하면 Android command STT와 overlay가 켜졌다. | owner score는 activation phrase 후보가 먼저 있을 때만 command window 오픈 조건에 참여한다. |
| `자비스` 단독 또는 넓은 fuzzy wake 허용 | `자비스 카메라 실행`, `헤이 자비스 깨어나`, 주변 대화 조각까지 wake로 오인식될 수 있었다. | idle wake는 `자비스 깨어나` 계열과 제한된 ASR equivalent만 허용한다. |
| `깨어나`, `깨우나`, `때어나` 같은 activation 동사 단독 허용 | local ASR이 실제 호출어를 놓친 상황을 보완하려다 false activation surface가 너무 넓어졌다. | 호출어와 activation 단어가 함께 잡힌 결과만 인정한다. |
| Android `SpeechRecognizer`를 idle wake 기본 경로로 사용 | Xiaomi/HyperOS에서 5초 단위로 마이크 privacy chip이 깜빡이고 `NO_MATCH`가 반복됐다. 발화 snapshot을 잡아도 wake phrase text가 비는 케이스가 많았다. | idle 기본 경로는 long-lived `AudioRecord` + local activation ASR이다. Android wake STT는 local activation을 시작할 수 없을 때만 fallback이다. |
| template-only acoustic wake | 저장 문구와 비슷한 음향 패턴만으로 command window를 열면 무발화/주변음 false activation 위험이 커졌다. | acoustic wake fallback은 template distance, peak RMS, owner score, owner speech duration을 모두 통과해야 한다. |
| accepted wake capture를 owner profile에 병합 | false activation 샘플까지 owner strict 통과로 강화할 수 있었다. | owner profile은 등록 기반 embedding 앞 8개만 사용하고, wake capture 병합 debug calibration은 제거했다. |
| command window 종료 후 예약된 STT runnable 방치 | `종료`, `자비스 잠들어`, timeout 뒤에도 `ready_for_speech`가 다시 뜨면서 Jarvis가 계속 듣는 것처럼 보였다. | command window close 시 active recognizer, delayed listen runnable, partial finalize callback을 정리한다. |

현재 가장 중요한 불변 조건:

- Idle 상태에서는 `자비스 깨어나` activation phrase 또는 strict acoustic wake 후보가 먼저 있어야 한다.
- activation 후보가 있어도 같은 rolling audio의 owner voice verification이 통과하지 않으면 command STT, overlay, 준비음, 진동이 발생하지 않는다.
- `자비스 잠들어`, `멈춰`, `stop_listening`은 Jarvis foreground service를 종료하지 않는다. 현재 command window만 닫고 idle activation 대기로 돌아가야 한다.
- command window 안에서 `자비스 깨어나`가 다시 들어와도 window를 갱신하지 않는다. wake phrase는 idle activation 전용이다.
- `종료`, `홈`, `뒤로` 같은 앱 제어 명령은 현재 앱을 제어할 뿐 Jarvis 음성 서비스나 idle activation을 종료하지 않는다.
- command window의 30초 deadline은 Android STT retry나 local fallback 때문에 연장되지 않아야 한다. 카메라 세션 명령처럼 명시적으로 유지 대상인 command 처리 결과만 새 30초 window를 연다.

디버깅 우선순위:

1. 먼저 `scripts/jarvis-profile-status.sh`로 `profile_configured=true`, `profile_embeddings>=2`, `profile_phrase_id=jarvis_activation_v3`인지 확인한다. 구버전 profile이면 wake 품질을 판단하지 않는다.
2. live 발화를 반복시키기 전에 `jarvis-wake-diagnose.sh`, `jarvis-activation-replay.sh`, `jarvis-activation-captures.sh`로 저장 WAV replay를 본다. 같은 샘플에서 activation text, owner score, RMS, endpoint를 재현하지 못하면 live threshold를 만지지 않는다.
3. `FAIL_NO_ACTIVATION`은 owner threshold보다 activation ASR/KWS 품질을 먼저 의심한다. 이전 실기기 로그에서 owner near는 통과했지만 activation text가 비거나 `응.`, `진자`, `깨우나`로 깨지는 케이스가 많았다.
4. false activation이 나오면 owner threshold를 올리기 전에 activation phrase 인정 범위가 넓어진 변경부터 되돌린다. 넓은 fuzzy rule이 가장 위험하다.
5. “안 깨어남”과 “멋대로 깨어남”을 한 번에 고치려 하지 않는다. 저장 WAV replay에서 miss를 먼저 분류하고, idle guard로 false activation을 별도로 검증한다.
6. command latency 문제는 wake 품질과 분리한다. wake 통과 뒤에는 `ready_for_speech`, `speech_begin`, `command_parsed`, `accessibility_command_received` 구간을 `jarvis-command-trace.sh`와 `jarvis-latency-audit.sh`로 본다.
7. `자비스 잠들어` 또는 timeout 뒤 다시 듣는 문제가 보이면 `command_window_timeout`, `overlay_hidden`, 이후 `ready_for_speech` 재출현 여부를 먼저 본다. `scripts/jarvis-command-window-timeout.sh`와 `scripts/jarvis-overlay-timeout.sh`가 이 회귀를 잡는다.

다음 개선의 최우선 방향은 threshold 미세조정보다 activation ASR/KWS 품질 개선이다. 현재 구조는 owner verification보다 `자비스 깨어나` 텍스트 또는 acoustic wake 후보를 안정적으로 만드는 단계에서 더 자주 막혔다. 장기적으로는 activation phrase, speaker verification, command recognition을 하나의 streaming raw audio pipeline으로 통합해 “깨어나” 뒤 command STT를 새로 여는 지연과 인식 흔들림을 줄이는 방향을 검토한다.

## 8. Command Model

내부 명령은 문자열 상수로 관리한다.

| Command | Meaning |
| --- | --- |
| `open_camera` | 기본 카메라 앱 열기 |
| `open_front_camera` | 기본 카메라 앱을 전면 카메라 힌트와 함께 열기 |
| `open_rear_camera` | 기본 카메라 앱을 후면 카메라 힌트와 함께 열기 |
| `open_camera_and_take_photo` | 카메라 앱 열고 잠시 후 촬영 |
| `take_photo` | 현재 화면에서 셔터 탭 |
| `open_filters` | 필터/효과 UI 열기 |
| `switch_camera` | 전후면 카메라 전환 |
| `back` | 뒤로가기 |
| `home` | 홈으로 이동 |
| `wake_screen` | 꺼진 화면을 깨워 잠금화면 표시 |
| `sleep_screen` | 접근성 잠금화면 전역 액션으로 화면 끄기/잠금 |
| `stop_listening` | 현재 command window 닫기, idle activation 대기로 복귀 |

`카메라 종료해`, `카메라 닫아`, `카메라 꺼`, `카메라 나가`, `종료` 같은 표현은 현재 카메라 앱을 직접 kill하지 않고 `home` 명령으로 매핑한다.

실행/종료, 켜기/끄기처럼 자연스러운 반대 동작이 있는 명령은 항상 페어로 등록한다. 새 명령을 추가할 때는 파서, Command Model, 테스트 문장, README 지원 명령 목록에 양쪽 표현을 함께 반영한다.

Idle 상태에서 command window를 여는 기본 activation phrase는 `자비스 깨어나`이다. ASR 표기 흔들림을 고려해 `자베스`, `쟈비스`, `제비스`, `차비스`, `잡비스`, `잡스`, `jarvis` + `깨어나`도 같은 호출로 보지만, 호출어 앞뒤에 다른 filler나 명령이 붙으면 activation으로 인정하지 않는다. local activation ASR equivalent도 호출어와 activation 단어가 함께 잡힌 결과만 허용한다. `깨어나`, `깨우나`, `깨워나`, `때어나` 같은 activation 단어 단독 결과와 넓은 fuzzy 결과는 false activation을 줄이기 위해 command window를 열지 않는다. ASR 결과 끝에 붙는 마침표 같은 문장부호는 activation 비교 전에 제거한다. `자비스 실행`, `자비스` 단독, `헤이 자비스`, `헤이 자비스 깨어나`, `자비스 카메라 실행`은 idle에서 command window를 열지 않는다. 이는 activation hotwords ASR과 owner voice verification이 모두 통과하지 않으면 Android command STT와 overlay/비프음을 시작하지 않기 위한 제약이다.

예외: 30초 command window가 열린 동안에는 이어지는 명령에서 호출어를 생략한다. 예를 들어 `자비스 깨어나`로 `JARVIS LISTENING` overlay가 보인 뒤 `카메라 셀피 모드로 실행해`를 말할 수 있다. command window 안에서 `자비스 깨어나` activation phrase가 다시 들어와도 window를 갱신하지 않는다. command window 연장은 카메라 세션 명령처럼 명시적으로 유지 대상인 명령 처리 결과로만 발생한다.

카메라 세션 명령은 처리 후에도 command window를 다시 30초로 연다. 대상 명령은 `open_camera`, `open_front_camera`, `open_rear_camera`, `open_camera_and_take_photo`, `take_photo`, `open_filters`, `switch_camera`, `home`, `back`이다. 따라서 `자비스 깨어나` 후 `카메라 실행`, `후면`, `전면`, `찍어`, `종료`를 호출어 없이 연속 처리할 수 있어야 한다. Jarvis는 이 30초를 `JarvisVoiceService`의 hard deadline으로 별도 관리한다. 30초 안에 다음 명령이 없으면 active recognizer를 취소하고 idle activation 대기로 돌아가며, local ASR 또는 Android STT fallback은 남은 시간 안에서만 허용된다. command window가 닫힐 때는 예약된 다음 `startListening` runnable과 partial finalize callback을 제거해, 닫힌 뒤 Android command STT가 다시 켜지지 않게 한다. idle activation은 local activation ASR이 먼저 최근 3.6초 rolling audio에서 `자비스 깨어나`를 찾고, 같은 audio를 owner verification에 넣어 owner score를 확인한다. streaming partial/final 외에도 0.8초마다 rolling audio를 buffered hotword decode로 검사한다. 로컬 activation ASR 결과에 한해 `자비스 깨어나`가 `자비스게임`, `다비스때어나` 계열로 오인식된 결과는 activation equivalent로 허용하지만, 호출어가 없는 단어 단독 결과와 넓은 fuzzy result는 허용하지 않는다. ASR 텍스트가 비거나 틀린 경우에는 등록 문구 템플릿과 같은 rolling audio의 owner verification이 모두 통과할 때만 `*_acoustic_wake` fallback으로 activation을 인정한다. live command는 Android 기본 `SpeechRecognizer`가 먼저 듣고 partial/final 결과에서 명령을 파싱한다. Android STT command window는 `LANGUAGE_MODEL_WEB_SEARCH`, minimum input 300ms, possibly-complete silence 150ms, complete silence 300ms를 사용한다. Android STT가 발화 시작이나 partial을 감지한 뒤 실패했거나 사용할 수 없을 때만 local ASR fallback을 사용하며, fallback은 `0.0012` RMS 이상의 160ms active speech, 최소 560ms 청취, 240ms trailing silence가 감지되면 6초 live listen timeout 전에도 final decode를 실행한다. Activation ASR segment 판정은 `0.00022` RMS 이상, 최소 active speech 560ms, trailing silence 600ms를 사용한다. Android STT가 발화 시작/partial 없이 `NO_MATCH` 또는 timeout을 반환하면 실패음/빨간 표시 없이 command window 안에서 조용히 다시 듣고, command window deadline은 연장하지 않는다. local ASR 입력은 원본 RMS가 `0.00008` 이상일 때 목표 RMS `0.04`, 최대 `30x`까지 gain을 적용한다. activation 직후 live command STT는 추가 대기 없이 시작하고, 실제 사용자 준비음/진동은 Android STT `ready_for_speech` callback에서 낸다. 명령 처리 후 연속 command STT는 80ms 뒤에 시작한다. deadline 이후 Android STT가 speech-active 상태로 결과를 붙잡고 있으면 3.5초 grace 뒤 취소하며, listening timeout은 발화 중인 recognizer를 먼저 취소하지 않는다. `home`, `back`은 현재 앱만 제어하고 command window를 유지한다. `stop_listening`, `wake_screen`, `sleep_screen`도 partial command path에서 빠르게 실행할 수 있다. `자비스 잠들어`와 `stop_listening`은 command window만 닫고 idle activation 대기로 돌아가며, Jarvis 음성 서비스는 계속 유지한다.

## 8.1 Owner Voice Gate

소유자 목소리 인증은 오픈소스 `sherpa-onnx` Android 런타임과 3D-Speaker CAM++ speaker verification 모델을 사용한다.

- Runtime: `sherpa-onnx` 공식 Android AAR `v1.13.3`의 Kotlin API jar와 `arm64-v8a` native libraries
- Model: `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`
- 저장 방식: 소유자 embedding `FloatArray` 묶음을 little-endian bytes로 변환한 뒤 Base64 인코딩하여 앱 private `SharedPreferences`에 저장한다. v1 단일 embedding은 읽기 fallback으로 유지하고, 신규 등록은 다중 embedding과 `profile_phrase_id=jarvis_activation_v3`를 저장한다.
- 등록 시 `자비스 깨어나`를 반복한 전체 6초 녹음을 먼저 activation hotwords ASR로 확인하고, 통과한 경우에만 전체 음성 embedding과 1.4초 window / 700ms step 구간 embedding을 합쳐 최소 2개, 최대 8개를 저장한다. verification은 등록 embedding 묶음 중 가장 높은 cosine similarity를 사용한다.
- 기본 허용 threshold는 `0.50`이다. 등록/일반 embedding은 최소 peak RMS `0.0012`, 최소 active RMS `0.00085`를 사용한다. idle wake는 local activation ASR이 먼저 `자비스 깨어나`를 확인하고, 같은 rolling audio를 owner verification에 넣는다. activation phrase가 이미 확인된 경우에는 전체 rolling audio score와 1.2초/1.6초/2.2초 최고 에너지 후보 구간 score 중 가장 높은 값을 사용한다. 다중 embedding 프로필에서 말소리 구간 450ms 이상, similarity `0.36` 이상 고신뢰 단일 점수 또는 `0.27` 이상 근접 점수를 통과시킨다. acoustic wake fallback은 ASR 텍스트가 실패했을 때만 별도 endpoint로 쓰며, template distance `<=0.22`, template peak RMS `>=0.006`, owner score `>=0.50`, owner speech `>=500ms`를 동시에 요구한다. 기존 owner gate의 soft wake 단일/연속 점수와 template-only wake는 command window 오픈 조건으로 쓰지 않는다.
- 현재 APK는 Xiaomi 15 Ultra를 우선해 `arm64-v8a` ABI만 패키징한다.

현재 구현 흐름:

1. 사용자가 `내 목소리 등록 시작`을 누르고 조용한 환경에서 6초 동안 `자비스 깨어나`를 여러 번 또렷하게 말한다.
2. 앱 UI 등록은 `OwnerVoiceEngine`이, debug ADB 등록은 foreground enrollment service가 16kHz mono PCM을 녹음한다.
3. 녹음 전체를 activation hotwords ASR로 먼저 확인해 `자비스 깨어나` activation phrase가 없으면 저장하지 않는다.
4. activation phrase가 확인된 녹음만 sherpa-onnx로 전체 음성과 짧은 구간 speaker embedding을 계산한다. embedding이 2개 미만이면 등록 실패로 보고 다시 녹음하게 한다.
5. 계산된 embedding 묶음과 `profile_phrase_id=jarvis_activation_v3`를 `OwnerVoiceStore`에 저장한다.
6. 이후 `JarvisVoiceService`는 local activation ASR을 idle 대기로 시작하고, `AudioRecord`를 60초 단위로 유지한다.
7. activation 전용 hotwords recognizer는 streaming partial/final과 최근 3.6초 rolling audio의 0.8초 buffered decode로 `자비스 깨어나`를 찾는다. activation이 아닌 segment는 최대 8초마다 stream만 재시작하고 마이크는 계속 유지한다.
8. ASR 텍스트가 `자비스 깨어나`로 나오지 않으면 등록 문구 WAV의 wake template matcher를 보조로 실행한다. template matcher는 최고 피크 하나에 끌려가지 않도록 후보 추출에 95퍼센타일 robust reference peak를 사용하고, `distance<=0.22`, `peakRms>=0.006`일 때만 `*_acoustic_wake` 후보를 만든다.
9. activation phrase 또는 acoustic wake 후보가 확인되면 같은 rolling audio로 speaker embedding을 계산한다. speaker embedding 입력은 내부에서 최소 1.2초로 padding하고, 저장된 embedding 묶음과 cosine similarity 최고점을 비교한다. 긴 rolling audio 전체 score가 낮을 수 있으므로 1.2초/1.6초/2.2초 최고 에너지 후보 구간도 함께 비교한다.
10. ASR 텍스트 activation은 전체 또는 후보 구간 similarity가 `0.50` 이상이거나 activation-after-owner 보조 기준을 만족하면 owner voice가 통과한 것으로 판단한다. acoustic wake fallback은 owner score `>=0.50`과 owner speech `>=500ms`를 요구한다. 이 시점 전까지는 command window를 열거나 overlay/비프음을 내지 않는다.
11. activation phrase/acoustic wake와 owner voice가 모두 통과하면 30초 command window를 열고 Android 기본 `SpeechRecognizer` live command STT를 시작한다. activation phrase가 아니거나 owner voice가 거절되면 `activation_phrase_missing` 또는 `activation_owner_rejected`로 trace를 끝내고 조용히 idle activation 대기로 돌아간다.
12. Android STT partial 결과에서 빠른 명령이 파싱되면 final 결과를 기다리지 않고 즉시 실행한다.
13. Android STT가 실제 발화를 감지한 뒤 실패했거나 사용할 수 없으면 `LocalCommandSession`이 로컬 한국어 streaming ASR fallback을 시도한다. 발화가 감지되지 않은 `NO_MATCH`/timeout은 idle retry로 처리한다.
14. 카메라 세션 명령과 `home` 종료 명령은 Android STT 또는 local fallback 결과에서 먼저 해석되면 즉시 실행한다.
15. 카메라 세션 명령 또는 `home`/`back` 앱 제어 명령이면 30초 command window를 새로 열고 바로 다음 명령 인식을 시작한다.
16. 30초 command window 안에서 명령이 없으면 active recognizer/local fallback을 정리하고 조용히 idle activation 상태로 돌아간다.
17. `자비스 잠들어`, `멈춰`, `stop_listening`은 command window를 닫고 다시 idle activation 상태로 돌아간다. foreground service는 유지하므로 이후 `자비스 깨어나`로 다시 command window를 열 수 있다.
18. 그 외 명령 처리 후에는 command window를 닫고 다시 idle activation 상태로 돌아간다.

제약:

- Android `SpeechRecognizer`는 텍스트 결과만 안정적으로 제공하고 원음 PCM을 제공하지 않는다.
- 현재 버전은 완전한 동시 speaker verification + command recognition 파이프라인은 아니지만, idle activation ASR과 owner verification은 같은 rolling audio를 사용한다. command recognition은 activation 후 Android STT로 별도 시작한다.
- `LocalCommandRecognizer`는 idle activation audio와 command window fallback audio에서 동작한다. activation phrase, speaker verification, command recognition을 하나의 streaming raw audio pipeline으로 완전히 합치는 작업은 별도 과제다.
- idle activation 대기 중에는 Android 상태바의 마이크 개인정보 표시가 켜져 있는 것이 정상이다. Android 정책상 앱이 이 표시를 숨길 수 없다.
- 빅스비처럼 자연스러운 단일 발화 UX를 만들려면 activation phrase, speaker verification, command recognition을 더 긴 하나의 raw audio pipeline으로 재구성해야 한다.
- 현재 speaker verification 모델과 native library는 source tree에 포함된다. 한국어 streaming ASR 모델은 파일 크기 때문에 source tree에 커밋하지 않고 Gradle 빌드 시 다운로드해 APK에 포함한다.

명령 추가 시 규칙:

1. `CommandBus.kt`에 내부 명령 상수를 추가한다.
2. `CommandInterpreter.kt`에 한국어 표현을 추가한다.
3. 실제 실행 위치를 정한다.
4. README와 이 명세서의 명령 목록을 갱신한다.

실행 위치 기준:

- 앱 실행/시스템 Intent: 전용 launcher/helper
- 화면 조작: `CameraAccessibilityController` 같은 앱별 접근성 controller
- 음성 서비스 제어: `JarvisVoiceService`
- 명령 실행/전달 정책: `JarvisCommandExecutor`

## 9. Accessibility Automation Strategy

카메라 UI 제어는 2단계로 시도한다.

### 1단계: 접근성 노드 탐색

`text`, `contentDescription`, `viewIdResourceName`, `className`에서 키워드를 찾아 가장 적합한 노드의 화면 중앙을 접근성 제스처로 탭한다. Xiaomi 카메라에서는 접근성 `ACTION_CLICK`보다 실제 좌표 탭이 셔터/전환 버튼에서 더 안정적이다.

예상 키워드:

- 셔터: `com.android.camera:id/shutter_button`, `shutter`, `capture`, `take photo`, `촬영`, `셔터`, `사진 찍기`, `拍照`
- 필터: `filter`, `effects`, `leica`, `필터`, `효과`, `색감`
- 전환: `com.android.camera:id/v9_camera_picker`, `switch camera`, `flip camera`, `카메라 전환`, `렌즈 전환`, `전후면 전환`

### 2단계: 좌표 fallback

노드 탐색이 실패하면 화면 크기 기준 상대 좌표를 탭한다.

현재 portrait 기준:

- 셔터: `x=50%`, `y=88%`
- 필터: `x=25%`, `y=88%`
- 전환: `x=90%`, `y=87%`

실기기 테스트 후 Xiaomi 15 Ultra 기본 카메라 UI에 맞춰 보정한다.

## 10. Permissions

| Permission | Reason |
| --- | --- |
| `RECORD_AUDIO` | 음성 명령 인식 |
| `POST_NOTIFICATIONS` | Android 13+ 포그라운드 서비스 알림 |
| `FOREGROUND_SERVICE` | Jarvis 음성 서비스 실행 |
| `FOREGROUND_SERVICE_MICROPHONE` | Android 14+ 마이크 foreground service 선언 |
| `RECEIVE_BOOT_COMPLETED` | 재부팅 후 Jarvis 시작 알림 표시 |
| `WAKE_LOCK` | 음성 명령으로 꺼진 화면을 짧게 깨우기 |
| `TURN_SCREEN_ON` | Android 14+에서 화면 켜기 명령 의도를 명시 |
| `VIBRATE` | 명령 가능/실패/종료 상태를 촉각 feedback으로 표시 |
| `BIND_ACCESSIBILITY_SERVICE` | 접근성 서비스 바인딩. 일반 런타임 권한이 아니며 시스템 설정에서 사용자가 직접 켜야 함 |

소유자 목소리 등록/검증은 기존 `RECORD_AUDIO` 권한을 사용한다. 별도 런타임 권한은 추가하지 않았다.

화면 켜기 명령은 잠금화면 표시까지만 수행한다. 화면 끄기 명령은 접근성 서비스가 켜져 있을 때 Android 잠금화면 전역 액션을 호출한다. 비밀번호, 지문, 얼굴인식 등 사용자 인증을 자동 우회하지 않는다.

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

- Idle 상태에서 `자비스 실행`, `자비스` 단독, `헤이 자비스`, `헤이 자비스 깨어나`, `자비스 카메라 실행`은 command window를 열지 않는다.
- Idle 상태에서 `자비스 깨어나`가 owner voice + activation phrase를 통과하면 `JARVIS LISTENING` Hyper Island overlay가 표시되고 30초 command window가 열린다.
- ASR 텍스트가 비어도 strict acoustic wake fallback에서 template과 owner voice가 모두 통과하면 command window가 열린다.
- 무발화 상태의 `scripts/jarvis-idle-guard.sh 120`은 command STT/overlay 없이 통과해야 한다.
- `자비스 깨어나` 후 `카메라 실행`, `후면`, `전면`, `찍어`를 호출어 없이 연속 처리한다.
- command window 안에서 `카메라 열어`가 기본 카메라 앱을 연다.
- 카메라 세션의 `후면`, `전면`, `찍어`, `종료`는 partial STT 결과에서 먼저 실행되어도 final STT 결과에서 중복 실행되지 않는다.
- command window 안에서 `카메라 실행`이 기본 카메라 앱을 연다.
- command window 안에서 `카메라 셀피 모드로 실행해`가 기본 카메라 앱을 전면 카메라 힌트와 함께 연다.
- command window 안에서 `셀피`와 `전면`이 기본 카메라 앱을 전면 카메라 힌트와 함께 연다.
- command window 안에서 `셀피 모드`가 기본 카메라 앱을 전면 카메라 힌트와 함께 연다.
- command window 안에서 `카메라 후면 모드로 실행해`가 기본 카메라 앱을 후면 카메라 힌트와 함께 연다.
- command window 안에서 `후면`, `후면 모드`, `후면으로 전환`이 기본 카메라 앱을 후면 카메라 힌트와 함께 연다.
- command window 안에서 `찍어`가 카메라 화면에서 셔터를 누른다.
- command window 안에서 `사진 찍기`가 카메라를 열고 촬영을 시도한다.
- command window 안에서 `카메라 전환`이 전후면 카메라 전환을 시도한다.
- 카메라 앱이 열리지 않은 상태에서 `카메라 찍어`가 카메라를 열고 촬영을 시도한다.
- command window 안에서 `카메라 종료해`가 홈으로 이동한다.
- command window 안에서 `종료`가 홈으로 이동한다.
- command window 안에서 `화면 켜`가 꺼진 화면을 깨워 잠금화면을 표시한다.
- command window 안에서 `화면 꺼`가 접근성 잠금화면 전역 액션으로 기기를 잠근다.
- command window 안에서 `자비스 잠들어`와 `멈춰`가 command window만 닫고 음성 서비스는 유지한다.
- command window 안에서 `자비스 깨어나`만 다시 인식되어도 command window가 연장되지 않는다.
- 카메라 세션 명령 후 30초 동안 다음 명령이 없으면 overlay가 사라지고 idle activation 대기로 돌아간다.
- command window가 닫힌 뒤 예약된 Android STT 시작이 남아 있어도 idle activation 대기로 되돌아가며, `자비스 깨어나` activation 없이 Android STT 준비음/비프음이 다시 발생하지 않는다.
- `adb logcat -v time -s JarvisLatency`로 한 사이클을 측정했을 때 같은 trace id 안에서 STT 수신, 명령 파싱, 실행, 접근성 수신 이벤트가 확인된다.

### State Feedback Test

- `자비스 깨어나` activation 통과 후 컷아웃 영역 Hyper Island pill `JARVIS [camera hole] LISTENING`, 확인음 2회, 짧은 진동 1회가 발생한다.
- activation phrase 또는 owner voice 중 하나만 통과해서는 overlay, 확인음, command STT가 발생하지 않는다.
- 카메라 명령 처리 후 Hyper Island pill `JARVIS [camera hole] DONE`, 확인음 1회, 짧은 진동 1회가 발생한다.
- command window 안에서 인식 실패 시 Hyper Island pill `JARVIS [camera hole] FAILED`, 실패음 2회, 짧은 진동 2회가 발생한다.
- local command ASR 실패 후 Android `SpeechRecognizer` fallback이 시작되면 상태 단어가 `LISTENING`으로 돌아간다.
- idle activation 대기, owner 확인, command window 종료 상태에서는 overlay가 사라진다.
- command window 자동 만료 시 실패음/종료음 없이 overlay가 사라진다.
- command window 안에서 Android STT가 `ready_for_speech`로 여러 번 재시작돼도 준비음은 command window 진입 시 1회만 발생한다.

### Camera Automation Test

- portrait 상태에서 셔터 탭 성공
- landscape 상태에서 셔터 탭 성공
- 전면/후면 전환 성공
- `v9_camera_picker` content description에서 `전면`/`후면` 상태 판별 성공
- 필터 UI 열기 성공

테스트 실패 시 접근성 노드 덤프 또는 화면 좌표를 기준으로 `CameraAccessibilityController.kt`의 키워드/fallback 좌표와 `AccessibilityNodeMatcher.kt`의 스코어링을 조정한다.

### Owner Voice Test

- `내 목소리 등록 시작` 후 6초 녹음 진행률이 올라간다.
- 등록 완료 시 2개 이상 8개 이하의 owner embedding이 저장된다.
- owner embedding 묶음이 저장된 상태에서 Jarvis 시작 시 먼저 idle activation ASR이 시작된다.
- `자비스 깨어나` activation phrase와 등록된 사용자 목소리 similarity가 모두 통과하면 명령 인식 window가 열린다.
- 다른 사람 목소리는 threshold 미만으로 유지되어 명령 인식 window가 열리지 않아야 한다.
- idle activation 대기 중 Android 마이크 표시가 깜빡이는 대신 켜진 상태로 유지된다.

### Boot/Always-On Test

- `MY_PACKAGE_REPLACED` 또는 재부팅 후 `Jarvis 대기 준비됨` 알림이 표시된다.
- 알림의 `Jarvis 시작`을 누르면 `JarvisVoiceService` foreground notification이 표시된다.
- 소유자 목소리와 마이크 권한이 준비된 상태에서 접근성 서비스만 살아 있고 `JarvisVoiceService`가 없으면 watchdog이 음성 서비스를 다시 시작한다.
- 저장된 owner embedding이 2개 미만인 구버전 프로필이면 `JarvisVoiceServiceStarter`와 watchdog 모두 음성 서비스 시작을 차단한다.
- Android 정책상 `BOOT_COMPLETED`에서 microphone foreground service가 직접 시작되지 않는다.
- HyperOS 자동 시작 허용 및 배터리 제한 해제 후 장시간 대기 안정성을 확인한다.

## 13. Known Risks

- Xiaomi 카메라 앱 업데이트로 UI 키워드나 좌표가 바뀔 수 있다.
- SpeechRecognizer가 네트워크/Google 앱 상태에 영향을 받을 수 있다.
- 마이크 포그라운드 서비스는 배터리 최적화나 OS 정책에 의해 중단될 수 있다.
- idle activation ASR에서 마이크를 계속 열어 두면 privacy indicator는 안정적이지만 배터리 사용량이 늘 수 있다.
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
7. 새 앱 자동화를 추가할 때는 `JarvisAccessibilityService`에 로직을 직접 누적하지 말고 앱별 controller와 필요한 matcher/helper로 분리한다.
8. 문서 수정이 완료되면 변경 내용을 커밋하고 원격 저장소에 푸시한다.

# Jarvis

개인 Android 비서 앱 실험 프로젝트입니다. 첫 목표는 Xiaomi 기본 카메라 앱을 열고, 음성 명령으로 셔터/필터/전환 같은 UI 조작을 자동화하는 것입니다.

장기 명세와 이어받기 기준은 [`docs/PROJECT_SPEC.md`](docs/PROJECT_SPEC.md)에 정리되어 있습니다. 기능을 추가할 때는 해당 문서를 먼저 갱신합니다.

## 현재 구조

- `JarvisVoiceService`: command window가 열린 동안만 마이크를 사용하는 포그라운드 서비스입니다.
- `JarvisVoiceServiceStarter`: 앱 UI와 기본 어시스턴트 Activity에서 공통으로 쓰는 command window 시작 helper입니다.
- `LocalActivationSession`: legacy/debug wake 진단용 activation ASR 세션입니다.
- `OwnerVoiceGate`: legacy/debug wake 진단용 fallback 소유자 확인을 담당합니다.
- `OwnerVoiceEngine`: sherpa-onnx와 3D-Speaker CAM++ 모델로 소유자 목소리 embedding 묶음을 만들고 검증합니다.
- `OwnerVoiceStore`: 등록된 소유자 목소리 embedding 묶음을 앱 private storage에 저장합니다.
- `OwnerVoiceEnrollmentController`: 앱 UI에서 실행되는 소유자 목소리 등록 workflow를 담당합니다.
- `LocalCommandRecognizer`: sherpa-onnx 한국어 streaming ASR 모델로 Android STT 실패 fallback을 인식합니다.
- `LocalCommandSession`: 로컬 명령 ASR 스레드 상태를 관리합니다.
- `SpeechRecognitionIntentFactory`: Android `SpeechRecognizer` 실행 옵션을 한곳에서 생성합니다.
- `CommandCatalog`: 앱에 표시할 대표 명령, 인식 문구, 상세 동작, 필요 조건을 관리합니다.
- `CommandListActivity`: `명령어 리스트` 화면에서 전체 명령어를 보여주고, 선택한 명령의 전체 인식 문구와 동작 설명을 보여줍니다.
- `JarvisCommandExecutor`: 내부 명령 실행, 중복 실행 방지, 카메라 세션 window 유지 정책을 담당합니다.
- `JarvisNotificationController`: 음성 서비스 foreground notification과 상태 문구를 관리합니다.
- `JarvisFeedbackController`: 명령 가능/처리/실패 상태의 소리, 진동, 상태 broadcast를 담당합니다.
- `JarvisStateIndicatorController`: 접근성 overlay로 현재 Jarvis 상태를 화면 위에 표시합니다.
- `JarvisBootReceiver`: 재부팅 또는 앱 업데이트 후 Jarvis command window 시작 알림을 띄웁니다.
- `JarvisAssistantActivity`: 기본 어시스턴트 호출을 받아 30초 Jarvis 명령 대기를 시작합니다.
- `JarvisAccessibilityStatus`: 접근성 설정값과 현재 프로세스의 접근성 서비스 연결 상태를 구분합니다.
- `JarvisAccessibilityService`: 접근성 서비스 생명주기와 명령 수신을 담당합니다. 음성 서비스 자동 재시작은 하지 않습니다.
- `CameraAccessibilityController`: Xiaomi 기본 카메라의 셔터/필터/전후면 전환 자동화를 담당합니다.
- `AccessibilityNodeMatcher`: 접근성 노드 키워드 검색과 스코어링을 담당합니다.
- `ScreenController`: 짧은 wake lock으로 꺼진 화면을 깨웁니다.
- `MainActivity`: 마이크/알림 권한 요청, 접근성/기본 어시스턴트 설정 열기, Jarvis 명령 듣기 UI입니다.

## 지원 명령 초안

Jarvis는 기본적으로 상시 음성 대기를 하지 않습니다. 전원 버튼 길게 누르기 또는 앱의 `Jarvis 명령 듣기` 버튼으로 30초 command window를 연 뒤, 명령 문장 앞에 `자비스`를 붙여 말합니다.

- `자비스 카메라 실행`
- `자비스 카메라 전면`
- `자비스 카메라 후면`
- `자비스 카메라 전환`
- `자비스 필터`
- `자비스 사진 찍어`
- `자비스 카메라 종료`
- `자비스 화면 켜`
- `자비스 화면 꺼`
- `자비스 뒤로`
- `자비스 홈`
- `자비스 잠들어`
- `자비스 완전 종료`

사진 촬영은 `자비스 사진 찍어`, `자비스 사진 찍어줘`, `자비스 사진 찍어 주세요`, `자비스 셔터 눌러`, `자비스 촬영해줘`, `자비스 찰칵`을 같은 `take_photo` 명령으로 처리합니다. `자비스 사진 찍어`는 partial STT에서 `자비스 사진 찍` 또는 `자비스 사진 찌`까지만 들어와도 빠른 실행 대상으로 인정하고, partial 경로에 한해 `자비스 사진 지`, `자비스 사진 치`처럼 더 짧은 중간 후보도 촬영으로 인정합니다. partial callback 없이 final 결과에만 이런 clipped 후보가 들어오는 경우도 후보 순서대로 strict final parse를 먼저 적용한 뒤 실패한 후보를 `final_fast_partial` fallback으로 한 번 더 처리합니다. Android STT가 중간 단어 `사진`을 빼고 `자비스 찍`, `자베스 찍`, `쟈비스 찌`, `제이비스 찍`, `서비스 찌`처럼 exact short 후보를 반환한 경우는 partial뿐 아니라 final-only 결과에서도 촬영으로 처리합니다. 단, `자비스 지금 찍`처럼 호출어 뒤에 다른 단어가 끼는 후보는 실행하지 않습니다. command window 안에서는 Android STT가 호출어를 `자 비서`, `제이비스`, `자비써`, `자비쓰`, `서비스`처럼 인식한 경우와 사진 문맥의 촬영 동사 `찍어`를 `찌거`, `찌꺼`, `지거`, `지꺼`, `치거`, `치꺼`, `지켜`, `치켜`처럼 인식한 경우를 명령용 보정으로 처리하지만, 이 보정은 `자비스 깨어나` activation 판정에는 쓰지 않습니다.

`JARVIS LISTENING` Hyper Island overlay가 보이는 동안에만 명령을 받습니다. 카메라 세션 명령을 처리하면 30초 command window를 다시 열고, 30초 안에 다음 명령이 없으면 Jarvis는 실패음 없이 overlay를 숨긴 뒤 음성 foreground service를 종료합니다. 열린 command window 안에서도 `찍어`, `후면`, `종료`처럼 `자비스`가 빠진 단독 명령은 실행하지 않습니다.

앱 메인 화면의 `명령어 리스트`에서 지원 명령 수와 예시 문구 수, 지원 명령 전체의 대표 문구를 볼 수 있습니다. 명령을 선택하면 전체 인식 문구, 실행 동작, 상세 설명, 필요 조건, 명령 후 30초 대기 유지 여부, 빠른 partial 실행 여부를 확인할 수 있습니다.

하이퍼아일랜드와 `전면`/`후면`/`사진 찍어`/`카메라 종료` 같은 카메라 세부 제어는 Jarvis 접근성 서비스가 켜져 있고 실제 서비스가 Android에 연결되어 있어야 동작합니다. 앱 상태 화면은 접근성을 `꺼짐`, `연결 필요`, `켜짐`으로 구분합니다. 접근성이 꺼져 있거나 설정에는 남아 있지만 서비스가 bind되지 않은 상태면 기본 어시스턴트 호출과 `Jarvis 명령 듣기` 모두 command window를 시작하지 않고 Jarvis 앱 화면에서 접근성 설정을 안내합니다.

Android STT가 실제 발화를 감지한 뒤 실패했거나 사용할 수 없을 때만 local ASR fallback을 사용합니다. fallback도 `자비스` 호출어가 포함된 명령만 실행합니다. 과거 `자비스 깨어나` 상시 wake, acoustic wake fallback, owner gate 튜닝 이력은 `docs/PROJECT_SPEC.md`에 보존되어 있지만 현재 기본 UX에서는 idle 마이크 대기를 시작하지 않습니다.

Jarvis 상태 overlay는 사용자가 바로 판단해야 하는 순간에만 표시됩니다. 화면에는 디스플레이 컷아웃을 감싸는 Hyper Island-style pill을 컷아웃 중심에 맞춰 표시하고, `COMMAND_READY` 상태에서는 `JARVIS`와 `LISTENING` 사이에 카메라 홀 공간이 들어가도록 배치합니다. `JARVIS`와 상태 단어는 같은 좌우 폭을 쓰지 않고 실제 글자폭을 각각 측정해 비대칭으로 배치하므로, 짧은 `JARVIS` 쪽 검은 여백이 불필요하게 커지지 않습니다. 상하 폭은 카메라 홀 하단 정렬을 유지한 채 상단 여백만 줄여 카메라 홀 상단과 pill 상단이 더 가깝게 맞도록 보정합니다. `JARVIS`는 iOS system cyan 계열 색상으로 고정하고, `LISTENING`은 초록색, 처리/완료/실패 상태는 각각 `WORKING`/`DONE`/`FAILED` 상태 텍스트 색으로 전달합니다. idle/local activation/소유자 확인 상태에서는 화면을 가리지 않도록 overlay를 숨깁니다. 명령 가능 상태에 들어갈 때는 짧은 확인음 1회와 진동이 함께 발생합니다.

따라서 모바일 화면에서 `JARVIS` overlay가 보이지 않는 상태는 사용자 기준 idle 상태입니다. 현재 기본 정책에서는 idle 상태에서 마이크를 계속 사용하지 않습니다.

## 속도 측정 로그

음성 인식 속도 개선은 `JarvisLatency` 로그를 기준으로 판단합니다. 전원 버튼 long press 또는 `Jarvis 명령 듣기`로 command window를 연 뒤 `자비스 카메라 실행`, `자비스 카메라 후면`, `자비스 카메라 전면`, `자비스 사진 찍어`, `자비스 카메라 종료`를 한 사이클 말하면 같은 `trace=` 값으로 구간별 시간이 출력됩니다.

```bash
adb logcat -v time -s JarvisLatency
```

주요 이벤트는 `activation_listen_start`, `activation_asr_rejected_segment`, `activation_asr_complete`, `android_activation_local_replay_start`, `android_activation_local_replay_complete`, `activation_owner_verified`, `owner_audio_activation`, `listen_start`, `local_partial`, `local_complete`, `fallback_to_android`, `ready_for_speech`, `speech_begin`, `speech_end`, `speech_error`, `partial_results`, `partial_no_command`, `final_results`, `parse_no_command`, `command_parsed`, `command_execute_start`, `command_execute_return`, `accessibility_command_received`, `accessibility_command_dispatch_return`, `command_complete`입니다. 음성 서비스 내부 이벤트의 `total=...ms`는 trace 시작부터 해당 이벤트까지의 누적 시간이고, `step=...ms`는 직전 이벤트 이후 걸린 시간입니다. `activation_asr_rejected_segment`는 60초 idle 세션 안에서 activation이 아닌 segment를 버리고 계속 듣는 경로입니다. `activation_asr_complete`는 activation 문구가 확인됐거나 60초 세션이 종료된 결과를, `android_activation_local_replay_complete`는 Android STT wake 실패 snapshot을 local activation ASR로 다시 판정한 결과를 기록합니다. `activation_owner_verified`는 같은 rolling audio의 owner score를 기록합니다. `owner_audio_activation`은 activation phrase와 owner voice가 모두 통과해 command window를 여는 경로입니다. `listen_start`는 command STT에서 `biasCount`, `minMs`, `possibleSilenceMs`, `completeSilenceMs`를 함께 남겨 실제 intent에 들어간 recognition hint와 저지연 타이밍을 확인할 수 있습니다. `local_complete`는 live local ASR 종료 이유, local ASR 자체 elapsed, active speech, trailing silence, peak/mean RMS, ASR gain을 함께 기록합니다. `partial_results`, `final_results`, `parse_no_command`는 Android STT 후보를 최대 5개까지 `candidates=a|b|c` 형태로 남기고 후보 안의 공백은 `_`로 치환하므로 첫 후보가 틀렸지만 뒤 후보에 정답이 있었는지 확인할 수 있습니다. `partial_no_command`와 `parse_no_command`의 `photo=` 값은 후보별로 `missing_wake`, `missing_shot`, `missing_photo_or_direct_shot`, `take_photo_partial`, `take_photo_final` 같은 사진 명령 진단 reason을 남겨 호출어/사진 문맥/촬영 동사 중 어디가 깨졌는지 분리합니다. 뒤의 flag는 `w` 호출어, `c` 카메라/사진 문맥, `s` 정식 촬영 동사, `v` 촬영 동사 ASR 변형, `p` 빠른 partial, `f` final 파싱 성공, `q` fast partial 파싱 성공을 뜻합니다. `command_parsed`는 `candidateIndex=1`처럼 몇 번째 후보에서 명령을 잡았는지도 남깁니다. `ready_for_speech`, `speech_begin`, `speech_end`, `speech_error`를 보면 Android STT가 실제로 언제 준비되고 언제 발화를 감지했는지 분리할 수 있습니다. `accessibility_command_received`의 `totalMs`는 trace 시작부터 접근성 서비스 수신까지의 누적 시간이고, `busDelayMs`는 음성 서비스가 명령을 보낸 뒤 접근성 서비스가 받은 지연입니다. 같은 프로세스에서 접근성 서비스가 살아 있으면 `CommandBus`는 broadcast 전에 direct receiver를 먼저 호출하며, 로그의 `transport=direct|broadcast`로 경로를 구분합니다.

현재 command window는 열리자마자 Android STT를 시작합니다. 2026-06-22 Xiaomi 15 Ultra debug APK 검증에서는 기본 어시스턴트 호출 후 `listen_start`가 20ms, `ready_for_speech`가 65ms에 기록됐고, 최종 설치본의 debug command window에서는 `listen_start`가 8-33ms, `ready_for_speech`가 39-62ms 범위로 기록됐습니다. `open_camera` debug 주입 검증에서는 `command_complete` 후 다음 `listen_start`가 약 3ms 뒤에 이어졌습니다. 2026-06-23 최신 debug APK를 Xiaomi 15 Ultra에 재설치한 뒤 촬영 없이 1초 debug command window를 열어 `biasCount=159`, `minMs=180`, `possibleSilenceMs=90`, `completeSilenceMs=180`, `listen_start=60ms`, `ready_for_speech=90ms`를 확인했습니다. 촬영 명령은 partial 단계의 `자비스 사진 찍`, `자비스 사진 찌`, `자비스 사진 지`, `자비스 사진 치`, `자비스 찍`, `자비스 찌`, 호출어 오인식 direct partial 패턴을 허용하고, 접근성 노드 탐색보다 셔터 좌표 탭을 먼저 보내 실행 지연을 줄입니다. partial이 오지 않는 final-only 경로도 빠르게 끝나도록 command STT는 minimum input 180ms, possibly-complete silence 90ms, complete silence 180ms를 사용합니다. command window에서는 Android STT가 지원할 경우 `CommandCatalog`의 지원 문구와 `자비스 사진 찍`, `자비스 사진 찌`, `자비스 사진 지`, `자비스 사진 치`, `자비스 찍`, `자비스 찌`, `자베스 찍`, `쟈비스 찌`, `서비스 찌`, 사진 촬영 동사 오인식 문구, 대표 호출어 오인식 문구, 호출어 오인식과 clipped/partial 촬영 동사를 조합한 문구를 `EXTRA_BIASING_STRINGS`로 전달해 `자비스 사진 찍어` 같은 짧은 명령이 후보에 더 잘 올라오게 유도합니다. 같은 촬영 명령을 빠르게 반복할 수 있도록 `take_photo` 중복 방지 시간은 다른 명령보다 짧게 관리합니다.

trace별 요약은 다음 스크립트로 확인합니다.

```bash
scripts/jarvis-latency-report.sh
```

촬영 명령 실행 경로만 빠르게 재검증하려면 debug APK 설치 후 다음 스크립트를 사용합니다. 이 스크립트는 `take_photo`를 주입하고 접근성 서비스 수신, 셔터 좌표 fast path, 다음 command listening 재개 여부를 확인합니다.

```bash
scripts/jarvis-photo-command-audit.sh
```

실제 음성 인식까지 확인하려면 카메라 앱이 열리는 것을 확인한 뒤 아래 스크립트가 출력하는 안내에 맞춰 `자비스 사진 찍어`를 한 번 말합니다. 스크립트는 partial/final STT 텍스트, 파싱된 후보 순번, STT bias/timing 값, `take_photo` 파싱 여부, 접근성 서비스 수신, 셔터 좌표 fast path를 함께 판정합니다. 실패 시에는 `partial_no_command` 또는 `parse_no_command`의 `photo=` 진단을 보고 `missing_wake`면 호출어 오인식, `missing_shot`이면 촬영 동사 누락, `missing_photo_or_direct_shot`이면 사진 문맥과 direct 촬영 동사가 모두 부족한 케이스로 분류합니다. 현재 debug 기준 `stt_bias expected_min=159`보다 설치 APK의 `actual` 값이 낮으면 `stale_or_missing_bias`로 실패시켜 generated clipped photo bias hint가 빠진 구버전 APK에서 인식률을 잘못 판단하지 않게 합니다. 기준은 `JARVIS_PHOTO_MIN_BIAS_COUNT`로 조정할 수 있습니다. 접근성 서비스가 Android에서 crashed/not bound 상태면 `accessibility_crashed` 또는 `accessibility_not_bound`로 분류하므로, 이 경우 Jarvis 접근성 서비스를 설정에서 껐다 켠 뒤 다시 측정합니다. HyperOS dumpsys가 bound service component를 숨기고 `Service[label=Jarvis]`만 표시하는 경우도 bound 상태로 인정합니다.

```bash
scripts/jarvis-photo-live-check.sh 12
```

인식률과 속도를 수치로 보려면 같은 문장을 여러 번 반복 측정합니다. 아래 예시는 5회 반복하며, 각 trial의 원본 로그, 마지막 STT 후보 텍스트, `parsed_candidate_index`, `stt_bias_count`, STT endpoint timing, `parse_no_command` 발생 수, 실패 유형, 파싱/접근성 지연, 최종 성공률 요약과 TSV 결과 파일을 `/tmp`에 남깁니다. summary에는 `partial`, `final`, `final_fast_partial`, `local` 같은 `parsed_source`, `parsed_candidate_index`, 반복 출현한 `stt_candidate` 빈도도 함께 집계됩니다.

```bash
scripts/jarvis-photo-live-series.sh 5 12
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

스크립트가 `Speak now`를 출력하면 6초 동안 `자비스 깨어나`를 여러 번 또렷하게 말합니다. debug enrollment는 반복 발화 ASR 결과 안에 `자비스 깨어나` 계열 activation phrase가 포함되면 등록 검증을 통과시킵니다. debug enrollment는 마지막 녹음을 앱 cache의 `jarvis-owner-enroll-last.wav`로 저장하고 `debug_wav` 로그에 경로를 남깁니다. 등록이 끝나면 자동으로 `jarvis-profile-status.sh`를 실행합니다. 음성 서비스는 자동으로 상시 대기하지 않으며, 기본 어시스턴트 호출이나 `Jarvis 명령 듣기`로 시작합니다.

새 측정은 저장된 owner profile이 현재 activation 등록 상태인지 먼저 확인한 뒤, 로그를 비우고 정해진 시간 동안 녹화해 바로 요약합니다.

activation 디버깅은 사용자가 같은 문장을 반복해 말하는 방식으로 진행하지 않습니다. debug APK는 idle activation ASR이 사용한 원본 rolling WAV와 JSON 메타데이터를 앱 cache의 `jarvis-activation-attempts/`에 자동 저장합니다. 이후 같은 샘플을 현재 APK의 activation ASR과 owner verification으로 다시 돌릴 때는 다음 스크립트를 사용합니다.

`자비스 깨어나`/`자비스 잠들어` 개선 과정에서 확인한 실패 패턴과 다음 우선순위는 [`docs/PROJECT_SPEC.md`](docs/PROJECT_SPEC.md)의 `Wake/Sleep Recognition Lessons`에 정리합니다.

```bash
scripts/jarvis-wake-diagnose.sh
scripts/jarvis-wake-live-check.sh 20
scripts/jarvis-activation-replay.sh
scripts/jarvis-activation-captures.sh
```

`jarvis-wake-live-check.sh`는 실기기에서 wake만 검증합니다. debug APK에서는 기본적으로 no-display debug Activity로 음성 서비스를 재시작해 activation 세션 시작점과 측정 시작점을 맞춥니다. 실행하면 폰이 짧게 진동하고, 사용자는 진동 직후 `자비스 깨어나`를 말합니다. 스크립트는 `activation_asr_complete`, `activation_owner_verified`, `owner_audio_activation`, `ready_for_speech`, `JarvisStateIndicator overlay_visible state=COMMAND_READY`를 함께 확인해 컷아웃 영역 `JARVIS LISTENING` overlay가 실제로 표시됐는지 판정합니다. `jarvis-wake-diagnose.sh`는 프로필 상태, 저장된 activation WAV replay, 최신 캡처 메타데이터, 관련 logcat을 한 번에 묶어 wake 실패 원인을 먼저 분류합니다. `jarvis-activation-replay.sh`는 저장된 WAV들을 앱 내부에서 재디코딩해 accepted/text/RMS와 owner score를 로그로 요약합니다. `jarvis-activation-captures.sh`는 WAV/JSON 묶음을 `/tmp`로 가져와 사람이 직접 확인하거나 별도 분석에 사용할 수 있게 합니다. 이 흐름으로 ASR rule과 threshold 변경은 저장 샘플로 먼저 검증하고, 사용자의 실시간 발화는 최종 확인 단계에서만 요청합니다.

2026-06-21 18:32 KST Xiaomi 15 Ultra 화면 켠 상태 live check에서 `rolling_buffer_live_buffered_acoustic_wake`가 `자비스 깨어나`를 검출했고, owner verification은 `STRICT score=0.7634`로 통과했습니다. 이어서 `owner_audio_activation`, `ready_for_speech`, `JarvisStateIndicator overlay_visible state=COMMAND_READY`가 확인되어 command window와 상태 overlay가 실제로 열렸습니다. 같은 APK에서 `scripts/jarvis-idle-guard.sh 120`은 `PASS: idle stayed out of command STT for 120s`로 통과했습니다.

2026-06-21 19:23 KST Xiaomi 15 Ultra debug command window에서 Hyper Island overlay를 실기기 캡처로 확인했습니다. 글자 크기와 pill 하단 정렬은 유지하고 상단만 줄인 뒤 `JarvisStateIndicator overlay_visible state=COMMAND_READY width=446 height=78 gap=73 x=34 y=27 left=136 right=203` 로그가 남았고, 화면에서는 시안 `JARVIS`와 초록 `LISTENING` 전체 텍스트가 잘림 없이 표시됐습니다.

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

출력의 `path`는 `local_activation_asr->android_stt`, `android_stt`, `local_asr` 같은 실제 인식 경로이고, `total`은 마지막 trace 이벤트까지의 누적 시간입니다. `activation_asr_complete`, `activation_owner_verified`, `local_endpoint`, `android_ready`, `speech_begin`, `speech_end`, `error`, `peak_rms`, `mean_rms`, `asr_gain`을 함께 보면 idle activation ASR, owner verification, live Android STT, local fallback 병목을 나눌 수 있습니다. `listen`은 Android STT 시작 시점, `listen_ready`는 Android STT 시작부터 `ready_for_speech`까지의 시간입니다. `parsed`는 trace 시작부터 명령 파싱까지의 누적 시간이고, `parsed_source`는 `partial`, `final`, `final_fast_partial`, `local` 같은 파싱 경로이며, `parsed_candidate_index`는 Android STT 후보 중 실제로 채택된 후보 순번입니다. `speech_parse`는 Android STT가 발화를 감지한 뒤 명령 파싱까지의 시간입니다. `speech_access`는 발화 시작부터 접근성 서비스 수신까지, `command_access`는 명령 파싱부터 접근성 서비스 수신까지 걸린 시간입니다. 정상적인 빠른 명령은 `owner_audio_activation`, `listen_start`, `command_parsed`, `command_execute_start`, `accessibility_command_received`, `command_complete`가 같은 trace 안에 이어져야 합니다. `jarvis-command-trace.sh`는 debug APK에서 ADB 시작용 no-display Activity를 먼저 호출해 Jarvis 서비스를 재시작하려고 시도합니다. 이 자동 시작을 끄려면 `JARVIS_START_DEBUG_ACTIVITY=0`을 지정합니다. 프로필 검사를 건너뛰고 강제로 측정하려면 `JARVIS_SKIP_PROFILE_CHECK=1`을 지정합니다. 측정 중에는 `자비스 깨어나`를 말한 뒤 `JARVIS LISTENING` overlay 또는 준비음/진동이 나오면 `자비스 카메라 실행` 같은 호출어 포함 명령을 말합니다. 이 스크립트는 `status=command_complete`가 없으면 `activation_asr_complete`, `activation_owner_verified`, `owner_audio_activation`, `ready_for_speech`, `speech_begin`, `partial_results`, `command_parsed` 카운터로 어느 단계에서 멈췄는지 출력하고, 같은 경로의 `.diagnostic` 로그에 activation/owner score와 거절 사유를 함께 저장합니다. `jarvis-latency-audit.sh`는 같은 로그를 `PASS`, `FAIL_LEGACY_PROFILE`, `FAIL_NO_ACTIVATION`, `FAIL_NO_SPEECH`, `FAIL_NO_COMMAND`, `FAIL_NO_COMMAND_COMPLETE`, `FAIL_SLOW`로 분류합니다. `FAIL_LEGACY_PROFILE`은 저장된 owner profile이 현재 activation 등록 기준을 만족하지 않아 속도 판정에 사용할 수 없다는 뜻입니다. `FAIL_NO_ACTIVATION`은 local activation hotwords ASR이 `자비스 깨어나` activation phrase를 확인하지 못했거나 owner verification이 거절했다는 뜻입니다. 명령이 실행됐더라도 `speech_begin`이 있는 trace에서 `speech_parse<=2500ms`, `speech_begin`이 없는 trace에서 `parsed<=2500ms`, 접근성 실행이 있는 경우 `speech_access<=4000ms` 및 `command_access<=1200ms`를 넘으면 실패로 종료합니다. 기준은 `JARVIS_MAX_SPEECH_PARSED_MS`, `JARVIS_MAX_PARSED_MS`, `JARVIS_MAX_ACCESS_MS`, `JARVIS_MAX_COMMAND_ACCESS_MS` 환경 변수로 조정할 수 있습니다.

## 폰에서 켜야 하는 것

1. Android Studio에서 이 폴더를 엽니다.
2. 앱을 설치합니다.
3. 앱에서 마이크 권한과 알림 권한을 허용합니다.
4. `내 목소리 등록 시작`을 누른 뒤 조용한 곳에서 6초 동안 `자비스 깨어나`를 여러 번 또렷하게 말합니다.
5. `접근성 설정 열기`를 누르고 `Jarvis` 접근성 서비스를 켭니다.
6. 앱에서 `기본 어시스턴트 설정`을 누르고 Jarvis를 기본 디지털 어시스턴트로 선택합니다.
7. HyperOS 설정에서 전원 버튼 길게 누르기가 기본 어시스턴트를 실행하도록 설정합니다.
8. 전원 버튼을 길게 누르거나 앱의 `Jarvis 명령 듣기`를 누른 뒤 `자비스 카메라 실행`처럼 말합니다.

접근성 화면에서 `앱의 액세스가 거부됨` 또는 비슷한 제한 문구가 보이면 Android/HyperOS가 debug APK나 수동 설치 APK의 접근성 권한을 제한한 상태입니다. 폰에서 `설정 > 앱 > 앱 관리 > Jarvis`로 들어가 오른쪽 상단 메뉴의 `제한된 설정 허용`을 먼저 켠 뒤, 다시 접근성 설정에서 `Jarvis` 서비스를 켭니다. USB 디버깅이 연결된 개발 기기에서는 아래처럼 기존 접근성 서비스 목록을 보존하면서 ADB로 테스트용 활성화도 가능합니다.

```sh
service='com.personal.jarvis/com.personal.jarvis.JarvisAccessibilityService'
current=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
if [ "$current" = "null" ] || [ -z "$current" ]; then
  next="$service"
elif printf '%s' ":$current:" | grep -q ":$service:"; then
  next="$current"
else
  next="$current:$service"
fi
adb shell settings put secure enabled_accessibility_services "$next"
adb shell settings put secure accessibility_enabled 1
```

USB 디버깅이 연결되어 있으면 `scripts/jarvis-owner-enroll.sh 6`으로 소유자 목소리를 다시 등록하고, `scripts/jarvis-profile-status.sh`로 `profile_configured=true`인지 확인할 수 있습니다. 구버전 단일 샘플 프로필 또는 `자비스 깨어나` activation 이전 프로필은 `profile_configured=false`로 표시되며, 이 상태에서는 Jarvis command window를 시작하지 않습니다.

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

접근성 권한은 APK 설치만으로 자동 허용되지 않습니다. Jarvis 앱을 처음 실행한 뒤 `접근성 설정 열기`를 눌러 `Jarvis` 접근성 서비스를 직접 켜야 합니다. Android/HyperOS가 `앱의 액세스가 거부됨`을 표시하면 앱 정보 화면에서 `제한된 설정 허용`을 먼저 켜야 접근성 서비스를 활성화할 수 있습니다.

## 한계

기본 카메라 앱은 외부 제어 API를 제공하지 않습니다. 그래서 이 프로젝트는 접근성 서비스를 통해 버튼을 찾고 탭합니다. 전면/후면 모드 실행은 Android 카메라 인텐트에 렌즈 방향 힌트를 전달한 뒤, Xiaomi 카메라의 전환 버튼 상태를 읽어 목표 렌즈와 다를 때만 전환 버튼을 누릅니다. 셔터/필터/전환은 접근성 노드를 찾으면 노드 중앙을 실제 터치 제스처로 탭하고, 실패하면 화면 비율 기반 좌표를 탭합니다. Xiaomi 카메라 UI 버전, 언어, 화면 방향, 업데이트에 따라 노드 탐색 키워드나 좌표 fallback을 조정해야 할 수 있습니다.

`화면 켜`는 꺼진 디스플레이를 깨워 잠금화면을 보이게 하는 동작입니다. `화면 꺼`는 접근성 서비스의 잠금화면 전역 액션으로 기기를 잠그는 동작입니다. Android 보안 정책상 비밀번호, 지문, 얼굴인식 같은 잠금 해제는 자동으로 우회하지 않습니다.

소유자 목소리 등록은 오픈소스 `sherpa-onnx` 런타임과 3D-Speaker CAM++ ONNX 모델을 사용합니다. 현재 호출형 정책에서는 등록된 프로필이 Jarvis command window 시작 가능 조건으로만 쓰이고, 전원 버튼으로 열린 command window 안의 각 명령은 별도 speaker verification 없이 `자비스` 호출어가 포함된 텍스트 명령으로 처리합니다. 물리 버튼을 누를 수 있는 사람이 command window를 열 수 있으므로, 더 강한 보안이 필요하면 “명령 음성 자체의 owner verification”을 별도 설계해야 합니다.

Android 14+ 정책상 `targetSdk=35` 앱은 재부팅 broadcast에서 microphone foreground service를 직접 시작할 수 없습니다. Jarvis는 재부팅 후 알림을 띄우고, 사용자가 알림을 탭하면 30초 command window를 시작합니다. 접근성 서비스는 음성 서비스를 자동 재시작하지 않습니다.

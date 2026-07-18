# Jarvis Voice Path Code Review — 2026-07-18

## 목표와 범위

리뷰 우선순위는 음성 인식 정확도, 응답 속도, 코드 구조 순서다. Android `SpeechRecognizer`의 partial/final 후보, local sherpa-onnx fallback, 저장 음성 샘플 matcher, command window와 worker 생명주기를 점검했다.

실기기 발화 데이터 없이 STT threshold나 모델 파라미터를 임의로 바꾸지 않았다. 이번 변경은 코드로 재현되는 오실행·경합과 단위 테스트로 고정할 수 있는 최적화에 한정한다.

## 반영한 변경

### 1. 음성 인식 정확도

- partial은 1순위 후보 진단만 기록하고 부작용을 실행하지 않는다. 촬영·화면·이동·종료 명령은 final 결과로만 확정해 `사진 찍…지 마`가 느리게 완성되는 동안 먼저 촬영되는 경합을 제거했다.
- 이 final-only 규칙을 local streaming ASR에도 적용했다. local partial text는 UI/latency 진단에만 전달하고 240ms trailing silence 뒤 final decode에서 명령을 확정한다.
- 어느 순위의 final 후보든 부정/취소 문장이면 다른 긍정 후보도 실행하지 않는다. 부정이 없으면 모든 후보의 strict parse를 순서대로 먼저 시도하고, 전부 실패한 경우에만 `take_photo`의 잘린 final 후보를 복구한다.
- command wake word는 문장 시작에서만 인정한다. `서비스`, `잡스`같은 모호한 alias는 사진/촬영 신호가 함께 있을 때만 허용하고, `제이비스`, `자비서` 등 신뢰 가능한 ASR alias는 일반 명령에도 유지한다.
- 후보 안의 `하지 마`, `주지 마`, `아니야`, `취소` 같은 명시적 marker를 명령 파싱 전에 거절하고, `홈페이지`/`백업`을 `홈`/`백` 명령으로 처리하던 substring 오탐을 막았다.
- 발화 종료 후 final/error가 오지 않으면 2.5초 watchdog이 recognizer를 재생성하고 command window 안에서 다시 듣는다. recognizer 인스턴스별 generation listener가 폐기된 인식기의 늦은 callback을 버린다.
- 음성 샘플 matcher는 한 exemplar의 최저 거리로 승인하지 않는다. 서로 다른 유효 exemplar 2개가 모두 거리·길이 기준을 통과해야 하고, 길이가 잘못된 저거리 outlier는 consensus 상위 2개에서 제외한다.
- local final에 부정/취소가 있으면 wake word가 남아 있어도 음성 샘플 matcher fallback을 금지한다.

### 2. 속도와 자원 사용

- packaged local ASR asset 존재 검사를 프로세스 동안 한 번만 수행한다.
- 서비스 시작 warm-up은 command recognizer만 생성한다. 현재 기본 UX에서 사용하지 않는 activation recognizer는 debug/legacy 요청 시점까지 lazy load한다.
- warm-up은 atomic gate로 중복 실행을 막고, 초기화 실패 시 다음 요청에서 재시도한다.
- final-only 실행으로 위험한 partial 조기 실행 속도는 포기했다. 대신 기존 90/180ms endpoint hint, command-only warm-up, asset cache, 직접 accessibility dispatch로 오실행 없이 줄일 수 있는 구간을 최적화한다.

### 3. 코드 구조와 동시성

- 잘린 final 사진 후보 복구 정책을 `JarvisCommandExecutor`의 수동 set에서 `CommandCatalog.fastPartial`로 옮겨 UI 설명과 runtime이 같은 source를 사용한다. 현재 허용 대상은 `take_photo` 하나다.
- `LocalCommandSession`, `LocalActivationSession`, Android `SpeechRecognizer` adapter에 공통 `SessionGeneration`을 적용했다. stop/reset 후 느리게 도착한 이전 worker/recognizer callback은 새 세션을 변경하지 못한다.
- local worker callback은 main handler로 전달해 서비스 상태와 latency trace를 worker thread에서 수정하지 않는다.
- command window 종료 후 예약한 `stopSelf()`는 이름 있는 runnable과 window guard를 사용하고, 새 window가 열리면 이전 종료 예약을 취소한다.
- 접근성 명령은 프로세스 내부 callback으로 결과를 전달한다. 카메라 제스처는 `GestureResultCallback` 완료/취소까지 기다리고, generation과 5초 watchdog으로 지연된 물리 동작과 늦은 callback을 폐기한다.
- 명령 실행 tracker는 in-flight 명령을 예약하고 실제 성공 callback 시점부터 cooldown을 계산한다. UI 결과 표시 전의 동일 명령 재진입과 stale completion이 중복 dispatch를 만들지 못한다.

## 회귀 테스트

추가하거나 변경한 주요 사례:

- 잘린 1순위 final보다 완전한 2순위 final 우선
- 어느 순위의 final 부정문이든 다른 긍정 명령을 veto
- partial은 진단만 하고 카메라/촬영 명령을 final에서만 확정
- `촬영하지 마`, `종료하지 마`, `서비스 종료`, `스티브 잡스 카메라 실행`, `홈페이지`, `백업` 거절
- `제이비스 카메라 실행`, `자비서 화면 꺼` 인정
- 서로 다른 2개 음성 exemplar 합의, 길이 outlier, 무음/무효 exemplar 거절
- 중단·재시작된 session token과 중복 completion 거절
- local partial→final 전이와 부정 final의 음성 샘플 fallback 차단
- in-flight 명령 예약, 실패 즉시 재시도, 성공 cooldown, stale completion 격리

기본 품질 게이트:

```bash
./scripts/jarvis-quality-check.sh
```

2026-07-18 최종 실행 결과는 단위 테스트 108개(실패/오류 0), debug/release APK 조립, Android lint, manifest·privacy·shell 계약, `git diff --check`를 기준으로 기록한다. 설치 가능한 검증본은 `dist/Jarvis-1.0.0-2-debug-arm64.apk`에 생성하며, 실제 기기 설치는 사용자 요청에 따라 보류한다.

실기기가 연결된 환경에서는 정확도와 지연을 함께 확인한다.

```bash
scripts/jarvis-photo-live-series.sh 10 12
scripts/jarvis-command-trace.sh 45
```

판정 시 성공률만 보지 않고 `parsed_source`, `parsed_candidate_index`, `partial_command_deferred`, `speech_parse`, `command_shutter`, `final_result_timeout`, false command를 함께 기록한다.

검증 시점에 Xiaomi 15 Ultra가 ADB로 연결되어 있었지만, 사용자의 실제 발화가 필요한 반복 음성 측정과 APK 설치는 자동 실행하지 않았다.

## 남은 위험과 후속 우선순위

1. `CommandVoiceSampleMatcher` feature는 RMS envelope와 zero-crossing rate 중심이다. 2-exemplar consensus로 오탐을 줄였지만 phonetic 구분력이 충분하지 않다. log-mel/MFCC 또는 검증된 음성 embedding으로 교체하기 전에는 threshold를 느슨하게 하지 않는다.
2. 사진 중심 325개 bias와 90/180ms silence timing은 실제 confusion matrix 없이 변경하지 않았다. 조용한 방/TV/차량 소음, 0~700ms 중간 쉼, `WEB_SEARCH`/`FREE_FORM` matrix에서 recall·false accept·P50/P95를 함께 비교해야 한다.
3. `JarvisVoiceService`에는 현재 호출형 UX에서 도달하지 않는 legacy idle wake orchestration이 남아 있다. debug source set으로 이동하거나 제거한 뒤 command session reducer를 추출하는 것이 다음 구조 개선이다.
4. production release 서명 key와 `config/jarvis-release-cert.sha256` fingerprint는 아직 운영 값이 없다. 실제 key를 준비하기 전에는 release 배포 스크립트가 의도적으로 중단된다.

이번 변경의 실기기 음성 성공률·지연 개선 수치는 USB 기기에서 반복 발화 측정을 마친 뒤 기록한다. 단위 테스트와 APK/lint 성공만으로 실제 recall 향상을 단정하지 않는다.

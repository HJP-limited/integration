# 실기기 테스트

arm64 Android 실기기에서 현재 통합앱을 검증하는 절차다.
2026-10-04 통합본은 빌드/PC 검증과 실기기 실행을 구분해 기록한다.
기기 연결이 없으면 기존 실기기 기록을 이번 통합본의 통과로 바꾸지 않는다.

## 무엇이 어디에 있나

OCR·KIE 자산(약 57MB)은 **APK 안에** 들어간다. 따로 넣을 것이 없다.

임베딩 모델과 검색 토크나이저도 APK에 포함한다. 앱 저장소로 복사해 실제 검색에 사용한다.
Gemma 4 E2B만 약관 동의 후 앱에서 다운로드한다. 사용자 계정·HF 토큰 입력은 필요 없다.

```text
/sdcard/Android/data/com.example.hjp/files/models/
  gemma-4-E2B-it.litertlm        2.6G  대화·도구 선택
  embeddinggemma-300m.tflite     179M  벡터 검색  ┐ 한 세트다.
  sentencepiece.model            4.7M  토크나이저 ┘ 둘 중 하나만 있으면 임베더가 안 뜬다
```

생성 모델은 **이름이 무엇이든 된다.** 앱이 `hjp-agent.litertlm` 을 먼저 보고, 없으면 모델
폴더의 `.litertlm` 중 가장 큰 것을 쓴다. 고른 파일이 진짜인지는 바이트 크기와 SHA-256 으로
확인하므로, 엉뚱한 파일을 집으면 "모델 없음"으로 정확히 보고된다.

FunctionGemma 는 더 이상 쓰지 않는다 — 대화/도구호출 모델을 나누던 옛 구조의 잔재이고,
지금은 생성 모델 하나가 도구 선택까지 한다.

앱은 내부 경로(`/data/data/com.example.hjp/files/models/`)도 같이 본다.

이 세 대형 파일은 Git에서 제외한다. 개발용 설치 스크립트는
`HJP_MODEL_DIR` → `<repo>/models` → 형제 `HJP_limitededition-main/models` 순으로 찾는다.
KIE 분류기도 Git에서 제외하므로 빌드 전에 배치해야 한다.
[새 clone 모델 준비](docs/서비스형_모델준비_2026-09-16.md#새-clone의-빌드-준비)를 따른다.

## 개발용 USB 설치·계측

개발자 옵션과 USB 디버깅을 켠 뒤:

```powershell
.\scripts\install_real_device_debug.ps1
```

현재 멀티턴 165개/435턴 전체를 앱에서 재생하려면 `-RunMultiturn165`를 추가한다.
장시간 실제 모델 실행이며, 버전과 채점 범위는 [165 시나리오 실행](docs/165_시나리오_실행.md)을 참고한다.

하는 일 — 모델 폴더 확인(못 찾으면 **설치 전에 멈춘다**) → 앱/계측 APK 빌드 →
APK 안에 `liblitertlm_jni.so`·`libgemma_embedding_model_jni.so`·`kie_minilm_int8.onnx` 가
실제로 들어갔는지 확인 → 설치 → 모델 3개 전송(크기가 같으면 건너뜀).

`-SkipBuild` 로 빌드를 건너뛰고, `-ForceModels` 로 크기가 같아도 다시 민다.

모델 전송이 권한 오류로 실패하면 앱을 한 번 연 뒤 다시 돌린다 — 앱별 외부 폴더는 설치·실행
후에 생긴다.

## 확인

`install_real_device_debug.ps1`는 설치만 하고 끝나지 않는다. 로컬과 기기의 모델 SHA-256을
모두 확인한 뒤 아래 네 계측 테스트를 자동 실행한다. 하나라도 없거나 로드/추론에 실패하면
스크립트가 실패한다. 에뮬레이터에서는 skip하지 않고 명시적으로 실패한다.

```powershell
.\scripts\install_real_device_debug.ps1
```

- `RequiredModelsInstrumentedTest`: 생성·임베딩·토크나이저·det/rec/KIE/방향 모델 로드
- `OcrAssetsInstrumentedTest`: KIE 실제 분류 10/10
- `LiteRtGatewayToolCallInstrumentedTest`: 실제 Gemma 생성과 도구 호출
- `EmbeddingGemmaArm64InstrumentedTest`: 라이브 쿼리 임베딩과 하이브리드 검색

앱 → **설정 탭 → 모델** 에서 대화 모델·도구 호출 모델·검색 엔진 상태를 본다.
**모델 파일 관리** 를 누르면 모델별로 `파일 가져오기` / `동작 확인` 이 있고, 임베딩 모델에는
`토크나이저 가져오기` 가 따로 있다.

임베딩의 `동작 확인`은 실제 추론을 실행하고 성공하면 768차원을 보고한다.
생성 모델의 `상태 확인`은 배포 진단값 갱신이며 실제 생성 테스트와 다르다.

프로덕션 의미검색과 OCR은 필수 임베딩/KIE 모델이 없거나 추론에 실패하면 중단한다.
설치 스크립트와 필수 모델 게이트도 실패를 skip하거나 폴백으로 성공 처리하지 않는다.
개발용 PC 파일 전송은 앱 다운로드 검증과 별도다.
서비스 준비는 APK의 임베딩 두 파일을 검사하고 Gemma 하나만 Android DownloadManager로 받는다.
화면 이탈·프로세스 종료 후 상태를 복구하고 크기·SHA-256 검증을 거쳐 준비 상태로 전환한다.
현재 서비스 흐름에 사용자 토큰 입력이나 수동 강제 종료는 필요 없다.

## USB 없이

1. `app/build/outputs/apk/debug/app-debug.apk` 를 폰으로 보내고 "출처를 알 수 없는 앱" 설치를
   허용한다. 디버그 키로 서명돼 있어 테스트 설치는 된다 — 배포용은 아니다.
2. 첫 실행의 AI 기능 준비 화면에서 모델 약관을 확인하고 사용자가 동의한다.
3. Gemma 약 2.59GB 다운로드·검증이 끝나고 AI 준비 완료 상태가 되는지 확인한다.
   APK에 들어 있는 임베딩·SentencePiece·OCR·KIE를 따로 다운로드하지 않는다.

Gemma 4 E2B 가 2.6GB 라 이 경로는 느리다. 케이블이 있으면 USB 쪽이 낫다.

## 릴리스 APK

`./gradlew :app:assembleRelease` 는 **서명되지 않은** APK를 만든다. 기기에 설치하려면
키스토어를 만들어 `signingConfigs` 를 붙여야 한다. 지금은 설정돼 있지 않다 — 테스트는 디버그
APK(x86_64 포함)로 한다. APK 실제 크기는 해당 빌드 산출물에서 확인한다.

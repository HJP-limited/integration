# 실기기 테스트

arm64 안드로이드 기기에서 앱을 돌리는 절차. **아직 실기기에서 돌려본 적이 없다** — 지금까지
확인은 노트북 러너와 x86_64 에뮬레이터로 했다.

## 무엇이 어디에 있나

OCR·KIE 자산(약 57MB)은 **APK 안에** 들어간다. 따로 넣을 것이 없다.

생성·임베딩 모델(3.1GB)은 APK 에 넣지 않고 설치 후 기기로 민다.

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

이 파일들은 `.gitignore` 대상이라 저장소에 없다. 노트북에서는 `HJP_limitededition-main/models/`
에 있고, 스크립트가 `HJP_MODEL_DIR` → `<repo>/models` → 옆 저장소 순으로 찾는다.

## USB 설치

개발자 옵션과 USB 디버깅을 켠 뒤:

```powershell
.\scripts\install_real_device_debug.ps1
```

하는 일 — 모델 폴더 확인(못 찾으면 **설치 전에 멈춘다**) → `:app:assembleDebug` →
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

프로덕션 의미검색은 임베더가 없거나 추론에 실패하면 중단하며 키워드 검색으로 대체하지 않는다.
OCR의 KIE 휴리스틱 폴백은 남아 있지만 **통합 테스트의 성공 조건으로는 인정하지 않는다.**
설치 스크립트와 필수 모델 게이트는 모든 모델이 실제로 로드되지 않으면 실패한다.
앱의 `필수 모델 다운로드 / 이어받기`로 3개 모델을 직접 받을 수 있다(약 2.8GB).
EmbeddingGemma 배포처의 이용 조건에 동의한 Hugging Face 계정과 읽기 토큰이 필요하다.
토큰은 저장하지 않으며 서명된 CDN 주소로 전달하지 않는다. 다운로드 동안 모델 화면을 유지한다.
화면 이탈·프로세스 종료로 중단되면 다음 시도에서 임시 파일을 이어받고, 크기와 SHA-256을
검증한 후에만 최종 파일로 설치한다. 준비한 파일의 `가져오기`에도 같은 검증을 적용한다.
완료 후 Android 설정에서 앱을 강제 종료하고 다시 연다(단순 화면 전환으로는 반영되지 않음).

## USB 없이

1. `app/build/outputs/apk/debug/app-debug.apk` 를 폰으로 보내고 "출처를 알 수 없는 앱" 설치를
   허용한다. 디버그 키로 서명돼 있어 테스트 설치는 된다 — 배포용은 아니다.
2. 앱 → 설정 → 모델 파일 관리 → 모델 이용 조건 확인·동의 후 읽기 토큰을 입력한다.
3. `필수 모델 다운로드 / 이어받기`를 누르고 3개 파일 검증 완료까지 기다린 후 앱을 강제 종료·재실행한다.
   직접 파일을 보내서 `파일 가져오기`로 설치해도 된다. 임베딩은 `.tflite`와
   `sentencepiece.model`을 **둘 다** 넣어야 한다.

Gemma 4 E2B 가 2.6GB 라 이 경로는 느리다. 케이블이 있으면 USB 쪽이 낫다.

## 릴리스 APK

`./gradlew :app:assembleRelease` 는 **서명되지 않은** APK를 만든다. 기기에 설치하려면
키스토어를 만들어 `signingConfigs` 를 붙여야 한다. 지금은 설정돼 있지 않다 — 테스트는 디버그
APK(2026-09-14 빌드 330.0MB, x86_64 포함)로 한다.

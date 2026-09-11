# 실기기 테스트

arm64 안드로이드 기기에서 앱을 돌리는 절차. **아직 실기기에서 돌려본 적이 없다** — 지금까지
확인은 노트북 러너와 x86_64 에뮬레이터로 했다.

## 무엇이 어디에 있나

OCR·KIE 자산(141MB)은 **APK 안에** 들어간다. 따로 넣을 것이 없다.

생성·임베딩 모델(3.1GB)은 APK 에 넣지 않고 설치 후 기기로 민다.

```text
/sdcard/Android/data/com.example.hjp/files/models/
  gemma-4-E2B-it.litertlm        2.6G  대화 답변
  functiongemma_270m.litertlm    289M  저메모리 폴백
  embeddinggemma-300m.tflite     179M  벡터 검색  ┐ 한 세트다.
  sentencepiece.model            4.7M  토크나이저 ┘ 둘 중 하나만 있으면 임베더가 안 뜬다
```

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
실제로 들어갔는지 확인 → 설치 → 모델 4개 전송(크기가 같으면 건너뜀).

`-SkipBuild` 로 빌드를 건너뛰고, `-ForceModels` 로 크기가 같아도 다시 민다.

모델 전송이 권한 오류로 실패하면 앱을 한 번 연 뒤 다시 돌린다 — 앱별 외부 폴더는 설치·실행
후에 생긴다.

## 확인

앱 → **설정 탭 → 모델** 에서 대화 모델·도구 호출 모델·검색 엔진 상태를 본다.
**모델 파일 관리** 를 누르면 모델별로 `파일 가져오기` / `동작 확인` 이 있고, 임베딩 모델에는
`토크나이저 가져오기` 가 따로 있다.

임베딩이 붙었으면 `동작 확인` 이 `active_embedding_model_backed: true`, 768차원을 보고한다.

**모델이 없어도 앱은 돈다.** 임베더가 없으면 키워드 검색으로, KIE 가 없으면 정규식 폴백으로
내려간다(라인 정확도 98.0% → 85.3%). 대화 모델이 없으면 검색 결과까지만 나온다. 조용히
내려가므로 상태 화면을 보고 판단해야 한다.

## USB 없이

1. `app/build/outputs/apk/debug/app-debug.apk` 를 폰으로 보내고 "출처를 알 수 없는 앱" 설치를
   허용한다. 디버그 키로 서명돼 있어 테스트 설치는 된다 — 배포용은 아니다.
2. 모델 파일을 폰의 Downloads 로 보낸다.
3. 앱 → 설정 → 모델 파일 관리 → 모델마다 `파일 가져오기` 로 고른다.
   임베딩은 `.tflite` 와 `sentencepiece.model` 을 **둘 다** 넣어야 한다.

Gemma 4 E2B 가 2.6GB 라 이 경로는 느리다. 케이블이 있으면 USB 쪽이 낫다.

## 릴리스 APK

`./gradlew :app:assembleRelease` 는 **서명되지 않은** APK(284MB)를 만든다. 기기에 설치하려면
키스토어를 만들어 `signingConfigs` 를 붙여야 한다. 지금은 설정돼 있지 않다 — 테스트는 디버그
APK(394MB, x86_64 포함)로 한다.

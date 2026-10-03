# HJP — 온디바이스 명함 서비스 (OCR + 검색 + 멀티턴 에이전트)

명함을 찍어 저장하고, 자연어로 찾고, 대화로 이어 묻는 앱. **모델 준비 후 네트워크 없이 기기 안에서** 돈다.

따로 개발되던 세 트랙(OCR · 검색 · 멀티턴 에이전트)을 하나로 합친 저장소다.

```
촬영/갤러리 → OCR(PP-OCRv5) → KIE 필드분류(MiniLM) → 확인 → 명함 DB 저장
                                                              ↓
          자연어 질문 → 질의 재작성 → 하이브리드 검색(FTS + 임베딩) → Gemma 답변
```

## 모듈 구조

이 프로젝트의 제약은 **"노트북에서 잰 결과가 폰을 대변해야 한다"** 는 것이다. 그래서 규칙과
상수가 한 곳에만 있도록 갈랐다.

| 모듈 | 내용 | 플랫폼 |
|---|---|---|
| `:agent-core` | 턴 라우팅·도구 호출 루프. `AgentKernel`, `DeterministicTurnRouter`, `AgentWorkflowPolicy`, `AgentSession` | 순수 Kotlin/JVM |
| `:agent-contract` | 모델 경계 계약. `AgentModelGateway`, `AgentEvent`, `ConversationMemory` | 순수 Kotlin/JVM |
| `:tool-contract` | 도구 계약(`ToolPlugin`, `ToolContract`) | 순수 Kotlin/JVM |
| `:tool-contact` | 명함 도구 — `search_contacts` · `get_contact` · `update_business_card` | 순수 Kotlin/JVM |
| `:tool-datetime` | `get_current_datetime` | 순수 Kotlin/JVM |
| `:tool-android-intents` | `create_calendar_event` · `open_compose` (화면을 여는 것까지) | Android |
| `:search-core` | 검색 엔진 — RRF 융합·필드 제약·RAG 컨텍스트 | 순수 Java |
| `:agent-local-gateway` | 모델 없이 도구를 고르는 규칙 게이트웨이 + 공용 시스템 프롬프트·이름 인덱스 | 순수 Kotlin/JVM |
| `:core-ocr` | OCR 검출·인식·KIE. `OcrPipeline`, `KieParser`, `CardParser`, `OcrCardMapper` | 순수 Kotlin/JVM |
| `:desktop` | 실제 Gemma·ONNX 임베딩 CLI 및 브라우저 테스트. 외부 화면 호출은 모의 처리 | Windows/JVM + Python |
| `:app` | Android — Compose UI, Room, LiteRT-LM, 각 런타임 배선 | Android |

에이전트·도구 계층은 `HJP-limited/HJP_dataset_gen_by_v1@Agent_0910` 에서 가져왔다.
**충돌하면 그쪽이 정본이다.**

**로직은 위쪽 모듈에서 고친다.** `:app` 에만 넣으면 화면이 규칙을 들고 있게 되고, 그게 이
구조가 막으려는 실패다 — 검색·라우팅·OCR 규칙은 화면 없이도 시험할 수 있어야 한다.

같은 자바 API 를 안드로이드(aar)와 데스크톱(jar)이 모두 제공하는 점을 이용한다 —
`:core-ocr` 은 데스크톱 jar 에 `compileOnly` 로 컴파일하고 구현은 소비하는 쪽이 준다.
저장소·임베더·모델·자산은 인터페이스(`BusinessCardRepository`·`BusinessCardKeywordIndex`·
`BusinessCardEmbeddingStore`, `EmbeddingEngine`, `AgentModelGateway`, `OcrAssets`)로 주입한다.

## 한 질문이 처리되는 길

```
질문 → DeterministicTurnRouter (규칙 선판정: 되짚기·지시어·capability)
     → 모델이 도구를 고름
         · 앱: Gemma 4 E2B (LiteRT-LM)
         · 노트북 기본 웹 실행 / CLI --gemma: 실제 Gemma (Python LiteRT-LM)
         · CLI --gemma 생략 / 웹 --rules: 규칙 게이트웨이(모델 품질 검증 아님)
     → 도구 실행 search_contacts / get_contact / update_business_card
                 / get_current_datetime / create_calendar_event / open_compose
     → AgentWorkflowPolicy 가 연쇄를 검증 → 답변
```

**검색은 도구 하나다.** 화면도 같은 백엔드를 부른다 — 목록에서 찾은 사람과 채팅에서 찾은
사람이 다르면 사용자는 둘 중 무엇을 믿어야 할지 알 수 없다.

## 빌드와 실행

요구사항: **JDK 21**, Android SDK(`compileSdk 36`), Gradle wrapper 동봉.

```bash
# APK (debug 는 에뮬레이터용 x86_64 를 함께 담는다)
./gradlew :app:assembleDebug

# 단위 테스트 (실제 모델/실기기 검증과 별도)
./gradlew test :app:testDebugUnitTest :tool-android-intents:testDebugUnitTest
```

빌드 전에 Git에서 제외된 KIE 분류기와 검색 모델 두 파일을 준비해야 한다.
정확한 다운로드 파일명·SHA-256·배치 경로는
[모델 준비 절차](docs/서비스형_모델준비_2026-09-16.md#새-clone의-빌드-준비)에 있다.
KIE 누락·짝 불일치 또는 검색 모델 누락·해시 불일치 시 debug/release 빌드가 실패한다.

### 노트북 러너

`desktop/`은 Git 추적 대상이며 `:desktop` 모듈을 항상 포함한다.
실제 Gemma 파일, ONNX 임베딩 export(외부 데이터·토크나이저 포함), Temurin Java 21과
Python LiteRT-LM을 별도로 준비한다. Android TFLite 모델로 노트북 임베딩을 대신하지 않는다.

```powershell
.\scripts\run_desktop_web.ps1                  # http://127.0.0.1:8765, 실제 모델
.\scripts\run_desktop_chat.ps1                 # 실제 모델 대화
.\scripts\run_desktop_chat.ps1 -Regression     # 실제 모델 회귀
```

실행 중 `:desktop:installDist`를 다시 빌드하면 사용 중인 JAR가 바뀐다. 모델 실행과 빌드는
순차 진행한다. 노트북의 메일·문자·일정은 SIMULATED이며 실제 외부 앱을 열지 않는다.
환경변수와 실행 범위는 [노트북 웹 테스트](docs/노트북_웹테스트_2026-09-19.md),
[실제 모델 CLI 검증](docs/검색_에이전트_노트북검증_2026-09-16.md)을 참고한다.

## 검색 구조

**Room 은 SQLite 다.** 별개의 선택지가 아니라 SQLite 위의 계층이고, 우리는 그 위에서 돈다.

- 원본 테이블 `business_cards` + FTS 테이블 `business_cards_fts`
- `@Fts4(tokenizer = unicode61, prefix = {2,3,4})` — 오프라인 평가에서 기본 토크나이저보다
  Recall@1 / MRR 이 뚜렷이 높았고, 특히 전화번호 조회가 0.19 → 1.00 으로 올랐다
- 키워드 4단 티어: 정확 구문 → 전체 단어 AND → 접두어 AND → LIKE 폴백(동의어 확장)
- 하이브리드: 키워드 순위 + 벡터 순위를 **RRF**(`1/(60+rank)`)로 융합. 식별자 질의
  (전화·이메일)는 시맨틱을 빼고 라우팅한다 — 섞으면 P@5 가 1.000 → 0.233 으로 떨어졌다
- 임베딩: EmbeddingGemma-300M, 768차원

4단 티어는 앱의 `RoomBusinessCardRepository.TieredFtsQuery` 와 러너의
`SqliteContactRepository` 두 곳에 **같은 순서·같은 동의어 표**로 있고, 그 위의 RRF 융합과
필드 제약은 `:search-core` 한 곳에 있다. 티어를 고칠 때는 두 곳을 같이 고쳐야 한다 —
한쪽만 늘리면 같은 질의가 양쪽에서 달라진다.

노트북 러너는 raw SQLite(JDBC)를 쓰지만 **같은 SQL·같은 FTS4 설정**을 만든다. 폰이 FTS4 를
쓰니 노트북도 FTS4 를 쓴다 — 토크나이저가 다르면 같은 질의가 양쪽에서 다른 결과를 내고,
그 순간 노트북 지표는 앱을 대변하지 못한다. 바꾼다면 **양쪽을 같이** 바꿔야 한다.

## 모델

| 모델/파일 | 크기(10진 MB/GB) | APK 포함 | Git 포함 | 사용자 준비 |
|---|---|---|---|---|
| PP-OCRv5 `det.onnx` / `rec.onnx` | 4.83 / 13.40 MB | O | O | 없음 |
| `textline_ori.onnx` / 문자 사전 | 1.02 MB / 59 KB | O | O | 없음 |
| KIE `kie_minilm_int8.onnx` | 36.59 MB | O | X | 없음. 개발자가 별도 배치 |
| KIE `kie_tokenizer.onnx` / `kie_labels.json` | 0.85 MB / 208 B | O | O | 없음 |
| EmbeddingGemma `.tflite` | 179.13 MB | O | X | 없음. 빌드 시 HJP_MODEL_DIR에서 가져옴 |
| 검색 `sentencepiece.model` | 4.68 MB | O | X | 없음. 빌드 시 HJP_MODEL_DIR에서 가져옴 |
| Gemma 4 E2B (대화·도구 호출) | 2.59 GB | X | X | 앱에서 약관 동의 후 다운로드 |
| EmbeddingGemma ONNX (노트북용) | 약 1.2 GB + 토크나이저 | X | X | 노트북 검증을 할 때만 준비 |

기본 1,000개 명함과 768차원 사전 벡터 색인은 APK와 Git에 포함한다.
FunctionGemma는 현재 배포 모델이 아니다. 필수 KIE/임베딩/생성 모델의 로드·실행 실패를
모의 모델이나 키워드 폴백으로 통과 처리하지 않는다.
검색용 SentencePiece와 KIE용 축소 XLM-R 토크나이저는 서로 호환되지 않는다.

### 노트북 임베딩

안드로이드는 EmbeddingGemma 를 `.tflite` + AI Edge RAG SDK 로 돌리는데 그 SDK 네이티브가
**arm64 전용**이라 x86_64(노트북·에뮬레이터)에서는 못 쓴다. 그래서 노트북 쪽만 ONNX
런타임을 쓴다. Android 양자화 TFLite와 PC ONNX export의 결과·속도가 같다고 보장하지 않는다.

이전 샘플 정합 실측에서는 앱에 번들된 사전 계산 벡터와 **코사인 1.0000**이었다.
전체 입력 동등성 검증은 아니다. 결정적이었던 두 가지 —
태스크 프리픽스(`"task: search result | query: "` / `"title: none | text: "`)를 직접 붙여야
하고(안드로이드는 SDK 가 자동으로 붙인다), 토크나이저는 `tokenizer.json` 을 그대로 읽는
구현을 써야 한다(`desktop/`의 실제 모델 러너가 사용한다).

## 알려진 한계

- debug에는 arm64와 x86_64 라이브러리가 포함돼 release보다 크다. 실제 크기는 빌드 산출물로
  확인한다. `python scripts/check_apk_payload.py`로 필수 모델 해시·라이브러리·1,000개 색인을 검사한다.
- **전체 모델 검증은 arm64 실기기가 필요하다.** EmbeddingGemma SDK는 arm64 전용이다.
  x86_64 에뮬레이터 화면 확인이나 PC ONNX 추론을 Android 필수 모델 통과로 집계하지 않는다.
- **새로 촬영한 명함의 임베딩은 폰과 노트북이 미세하게 다르다** — 폰은 양자화 tflite,
  노트북은 fp32 ONNX 로 계산한다. 번들된 1000장은 사전 계산본을 공유해 동일하다(코사인 1.0000).
- **순서 지시("첫 번째 사람")는 직전 결과 목록만 본다.** 검색이 한 번 더 돌아 목록이 좁혀지면
  그 좁혀진 목록의 1번을 고른다 — 여러 턴 전 목록으로 거슬러 올라가지 않는다.
  (실측: 2명 → "두 번째 사람 연락처" → "그 사람 회사" → "아니 첫 번째 사람" 이 원래 1번이 아닌
  직전 1장을 가리킴.) 앱·러너 동작은 같다.
- **SCR-01 로그인은 화면만** 있고 인증 백엔드가 없다.
- **도구를 고르는 정확도는 82.12%** (Agent_0910 의 A-15 E-3.2 실측: 400시나리오/1,918턴,
  Gemma 4 E2B, 안드로이드 GPU). 인자 정확도 93.93%, 과제 전체 성공률 41.75% —
  여러 턴에 걸쳐 도구를 이어 쓰는 시나리오에서 중간에 끊긴다. `docs/` 참조.
- 전화·지도 인텐트는 상대 앱이 자기 태스크로 열려 뒤로가기로 돌아오지 않는다(안드로이드
  기본 동작). Gmail 은 외부 호출용 액티비티라 돌아온다.

## 문서

- `docs/통합_인수인계_2026-09-14.md` — **합치면서 빠졌던 것들의 기록.** 다시 밟으면
  또 당하는 함정(색인 재생성·gradle 증분 패키징·모델 파일 짝)과 남은 일이 여기 있다
- `docs/OCR_ASSETS.md` — OCR/KIE 모델 출처와 재생성 절차
- `docs/서비스형_모델준비_2026-09-16.md` — APK 포함/다운로드 구분과 새 clone 빌드 준비
- `docs/노트북_웹테스트_2026-09-19.md` — 브라우저 테스트 실행과 실기기 검증의 차이
- `docs/검색_구조_설명.md`, `docs/멀티턴_인수인계.md` — 검색·멀티턴 설계 배경
- `docs/성능지표.md` — 평가 지표
- `scripts/eval_multiturn.py`, `scripts/hybrid_server.py` — 파이썬 미러. **Kotlin 이 정본이다.**
  `desktop/` 러너가 같은 Kotlin 코드를 돌리므로 새 검증은 그쪽을 쓴다.
  파이썬 쪽은 130시나리오/377턴 평가 자산 때문에 남겨 둔 것이다.

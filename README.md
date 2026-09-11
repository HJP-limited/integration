# HJP — 온디바이스 명함 서비스 (OCR + 검색 + 멀티턴 에이전트)

명함을 찍어 저장하고, 자연어로 찾고, 대화로 이어 묻는 앱. **네트워크 없이 기기 안에서** 돈다.

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
| `:core` | 검색·멀티턴 로직. `CardSearchService`, `CardGazetteer`, `AgentSession`, `TurnLogic`(runChat) | 순수 Kotlin/JVM |
| `:core-ocr` | OCR 검출·인식·KIE. `OcrPipeline`, `KieParser`, `CardParser`, `OcrCardMapper` | 순수 Kotlin/JVM |
| `:app` | Android — Compose UI, Room, LiteRT-LM, 각 런타임 배선 | Android |
| `:desktop` | 노트북 러너 — 같은 `:core`/`:core-ocr` 에 데스크톱 런타임을 물린다 | JVM |

**로직을 고칠 때는 `:core` / `:core-ocr` 에서 고친다.** `:app` 이나 `:desktop` 에만 넣으면
두 실행 경로가 갈라지고, 그게 이 구조가 막으려는 실패다.

같은 자바 API 를 안드로이드(aar)와 데스크톱(jar)이 모두 제공하는 점을 이용한다 —
`:core-ocr` 은 데스크톱 jar 에 `compileOnly` 로 컴파일하고 구현은 소비하는 쪽이 준다.
저장소·임베더·LLM·자산은 인터페이스(`CardStore`, `TextEmbeddingProvider`,
`ChatEngineProvider`, `OcrAssets`)로 주입한다.

## 빌드와 실행

요구사항: **JDK 21**, Android SDK(`compileSdk 36`), Gradle wrapper 동봉.

```bash
# APK (debug 는 에뮬레이터용 x86_64 를 함께 담는다)
./gradlew :app:assembleDebug

# 테스트 (147개)
./gradlew :core:test :core-ocr:test :app:testDebugUnitTest
```

### 노트북 러너

실기기 없이 인식·검색·라우팅을 확인한다. 앱과 **같은 코드**가 돈다.

```bash
./gradlew :desktop:run --args="ocr <이미지>"         # 명함 한 장 인식
./gradlew :desktop:run --args="import <이미지>"      # 인식해서 DB 저장 (OCR→검색 연결)
./gradlew :desktop:run --args="search 판교 개발자"    # 하이브리드 검색
./gradlew :desktop:run --args="turn 손다은|그 사람 회사" # 멀티턴 (| 로 턴 구분)
```

생성 모델은 싣지 않는다. `turn` 이 검증하는 범위:

- **된다** — 기능/자기참조/전체개수 우회, 질의 재작성(담화참조·정정·속성이월·조건누적),
  검색·필드필터·기권
- **안 된다** — followup / context_answer 분기와 focus 인물 치환. `runChat` 이 이 분기를
  LLM 로드 **뒤에** 두어서 모델 없이는 지나가지 않는다. 그 층은 실기기가 필요하다.

## 검색 구조

**Room 은 SQLite 다.** 별개의 선택지가 아니라 SQLite 위의 계층이고, 우리는 그 위에서 돈다.

- 원본 테이블 `business_cards` + FTS 테이블 `business_cards_fts`
- `@Fts4(tokenizer = unicode61, prefix = {2,3,4})` — 오프라인 평가에서 기본 토크나이저보다
  Recall@1 / MRR 이 뚜렷이 높았고, 특히 전화번호 조회가 0.19 → 1.00 으로 올랐다
- 키워드 4단 티어: 정확 구문 → 전체 단어 AND → 접두어 AND → LIKE 폴백(동의어 확장)
- 하이브리드: 키워드 순위 + 벡터 순위를 **RRF**(`1/(60+rank)`)로 융합. 식별자 질의
  (전화·이메일)는 시맨틱을 빼고 라우팅한다 — 섞으면 P@5 가 1.000 → 0.233 으로 떨어졌다
- 임베딩: EmbeddingGemma-300M, 768차원

`:desktop` 은 raw SQLite(JDBC)를 쓰지만 **같은 SQL·같은 FTS4 설정**을 만든다. FTS5 나 다른
토크나이저를 쓰면 같은 질의가 폰과 노트북에서 다른 결과를 내고, 그 순간 노트북 지표는
앱을 대변하지 못한다. (Room 에는 `@Fts5` 애너테이션이 없다. raw SQL 로 만들 수는 있으나
플랫폼 SQLite 의 FTS5 지원이 기기마다 갈려 minSdk 24 에서는 택하지 않았다.)

## 모델

| 모델 | 크기 | 위치 | 저장소 포함 |
|---|---|---|---|
| PP-OCRv5 det / rec | 4.7M / 13M | `app/src/main/assets/ocr/` | O |
| KIE 토크나이저 · 라벨 | 4.9M / 208B | 〃 | O |
| KIE 분류기 `kie_minilm_int8.onnx` | 113M | 〃 | **X** — `docs/OCR_ASSETS.md` 참조 |
| Gemma 4 E2B (대화) | 2.4G | 기기 `files/models/` 에 push | X |
| FunctionGemma 270M (도구) | 276M | 〃 | X |
| EmbeddingGemma 300M (`.tflite`) | 171M | 〃 | X |
| EmbeddingGemma ONNX (노트북용) | 1.2G | `HJP_EMBED_MODEL_DIR` | X |

KIE 분류기가 없으면 `CardParser` 정규식 폴백으로 내려간다(라인 정확도 98.0% → 85.3%).
임베더가 없으면 키워드 검색으로 폴백한다.

### 노트북 임베딩

안드로이드는 EmbeddingGemma 를 `.tflite` + AI Edge RAG SDK 로 돌리는데 그 SDK 네이티브가
**arm64 전용**이라 x86_64(노트북·에뮬레이터)에서는 못 쓴다. 그래서 `:desktop` 만 ONNX
런타임을 쓴다 — 모델과 전처리는 같다.

정합 실측: 앱에 번들된 사전 계산 벡터와 **코사인 1.0000**. 결정적이었던 두 가지 —
태스크 프리픽스(`"task: search result | query: "` / `"title: none | text: "`)를 직접 붙여야
하고(안드로이드는 SDK 가 자동으로 붙인다), 토크나이저는 `tokenizer.json` 을 그대로 읽는
구현을 써야 한다. 자세한 건 `desktop/.../OnnxEmbeddingProvider.kt` 주석에 있다.

## 알려진 한계

- **APK 289MB** (debug + x86_64 는 395MB). KIE 를 vocab trim 본(36.6M)으로 바꾸면 약 203MB.
- **에뮬레이터에서 벡터 검색이 빠진다** — EmbeddingGemma 네이티브가 arm64 전용. 실기기는 정상.
- **SCR-01 로그인은 화면만** 있고 인증 백엔드가 없다. **SCR-08 메일 초안**은 미구현.
- 전화·지도 인텐트는 상대 앱이 자기 태스크로 열려 뒤로가기로 돌아오지 않는다(안드로이드
  기본 동작). Gmail 은 외부 호출용 액티비티라 돌아온다.

## 문서

- `docs/OCR_ASSETS.md` — OCR/KIE 모델 출처와 재생성 절차
- `docs/검색_구조_설명.md`, `docs/멀티턴_인수인계.md` — 검색·멀티턴 설계 배경
- `docs/성능지표.md` — 평가 지표
- `scripts/eval_multiturn.py`, `scripts/hybrid_server.py` — 파이썬 미러. **Kotlin 이 정본이다.**
  `:desktop` 러너가 같은 Kotlin 코드를 노트북에서 돌리므로 새 작업은 그쪽을 쓴다.
  파이썬 쪽은 130시나리오/377턴 평가 자산 때문에 남겨 둔 것이다.

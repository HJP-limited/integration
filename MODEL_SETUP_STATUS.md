# 온디바이스 모델 구성 현황 (2026-07-10 ~ 07-11)

> **과거 작업 기록.** 아래의 FunctionGemma/Gemma 3/5000장 구성은 현재 배포 구성이 아니다.
> 2026-09-14 기준 구성과 실기기 절차는 `README.md`, `REAL_DEVICE_TEST.md`,
> `docs/통합_점검_2026-09-14.md`를 따른다.

브랜치: `llm-integration-work` — `main` 건드리지 않음.

## 최종 모델 구성 (3개 + OCR 예정)

| 역할 | 모델 | 파일명 | 크기 | 상태 |
|---|---|---|---|---|
| 임베딩 (하이브리드 검색) | EmbeddingGemma 300M (int4/int8 mixed-precision, seq256) | `embeddinggemma-300m.tflite` | 179MB | 다운로드 완료, 실기기 검증 중 |
| Tool-calling LLM (에이전트) | FunctionGemma 270M | `functiongemma_270m.litertlm` | 289MB | 보유, 기존대로 유지 |
| Chat LLM (RAG 답변) | Gemma 3 1B IT (dynamic_int4 QAT) | `gemma3-1b-it-int4.litertlm` | 529MB | **HF 게이트 승인 대기** |
| OCR (명함 → 텍스트) | PaddleOCR PP-OCR 경량 계열 (후보) | 미정 | ~20MB 수준 예상 | 7월 마일스톤(M5), 미착수 |

모델 파일 합계 약 1GB (OCR 포함해도 +20MB 수준이라 영향 미미).

## 오늘까지 결정한 것과 근거

### 1. 임베딩: `litert-community/embeddinggemma-300m`의 seq256 mixed-precision
- 원본 체크포인트(`model.safetensors` 1.21GB)는 모바일용 아님 → 양자화 변형 필요했음
- **seq256 선택 이유**: 명함 텍스트(이름/회사/직함/연락처)는 256 토큰이면 충분, 짧을수록 가볍고 빠름
- **접미사 없는 generic 빌드 선택 이유**: `.qualcomm.*`/`.mediatek.*`/`.google.tensor_g5` 빌드는 특정 칩셋 NPU 전용 — 범용 기기 대상이라 generic이 맞음
- 양자화 후 품질 손실 거의 없음 (MTEB multilingual 61.15 → 60.62)

### 2. LLM 역할 분리: FunctionGemma(도구) + Gemma 3 1B(채팅)
- FunctionGemma는 공식 모델 카드에 "not intended for use as a direct dialogue model" 명시 — 채팅 탭 RAG 답변에 쓰면 품질 저하 (실제로 체감했던 그 문제)
- 반대로 tool calling은 FunctionGemma가 훨씬 강함 (Mobile Actions 85% vs Gemma 3 1B BFCL ~31%)
- Google 공식 권장 아키텍처도 "FunctionGemma는 라우터/실행, 복잡한 생성은 더 큰 모델" 구조
- 두 모델 합쳐도 818MB로, 원래 계획이던 Gemma 4 E2B 단일(3.66GB)보다 훨씬 가벼움
- 엔진이 `.use {}`로 열고 닫는 구조라 **RAM에는 한 번에 하나만 올라감**

### 3. 배포 전략: 모델 분리 다운로드 (현행 유지)
- 현행: APK 설치 + Google Drive로 모델 파일 받아서 앱 내 "가져오기" 버튼으로 임포트
- 모델 실험/교체 중에는 이 방식이 압도적으로 빠름 (APK 재빌드 불필요)
- 최종 데모 시점에 `app/src/main/assets/`에 넣고 번들 APK로 전환 가능 — `noCompress` 설정과 asset 폴백 로직이 이미 있어서 코드 수정 거의 없음
- 번들 전환 시 주의: asset → 내부저장소 복사 구조라 **일시적으로 모델 용량의 2배 저장공간 필요**

## 오늘 코드 변경 (빌드 성공 확인됨)

- `LiteRtLmChatEngine.kt` — `LlmRole` enum(`ToolCalling`/`Chat`) 추가, 역할별 모델 파일 로드로 파라미터화
- `MainActivity.kt` — `runChat`이 `LlmRole.Chat` 사용 (핵심 변경), 모델 관리 화면 3모델 체계로 확장 (상태 패널 3개, 가져오기 버튼 3개, 실행 테스트 버튼 2개)
- `REAL_DEVICE_TEST.md`, `EMBEDDING_MIGRATION_NOTES.md`, `scripts/install_real_device_debug.ps1` — 3모델 구성 반영

## 실기기 검증 결과 (2026-07-11, Galaxy S8 / SM-G950N / Android 9 / RAM 4GB)

- **임베딩 모델: 성공.** RAG SDK(`GemmaEmbeddingModel`) + protobuf-javalite 추가 후 정상 로드. "동작 확인" 결과: 768차원, 문장 1개 4866ms (S8 CPU 기준 — 검색당 쿼리 1회만 임베딩하므로 실사용 오버헤드 ~5초)
- **하이브리드(시맨틱) 검색: 성공.** retrieval = `room_fts_plus_embeddinggemma_rrf` 확인. 문서(명함) 벡터는 PC에서 사전 계산해 APK에 번들(assets/cards/cards_embeddings_*), 쿼리만 온디바이스 임베딩
- **키워드 검색: 성공.** 5000장 기준 즉답. RoomDB 실측: 5000장+임베딩+FTS 합쳐 DB 약 34MB, 앱 메모리 ~116MB — 이 규모는 저사양 기기도 여유
- **Chat LLM(Gemma 3 1B): 이 기기에서는 실패 — 메모리 부족.** 로드 중 시스템 전체가 메모리 압박으로 다른 앱들을 대량 킬한 뒤 HJP 프로세스도 사망. 4GB RAM 기기에서는 1B(로드 시 ~1GB)가 무리. **RAM 8GB 이상 기기에서 테스트 필요.** S8 데모용 대안: chat 역할도 FunctionGemma 270M로 낮추거나 LLM 답변 없이 검색 결과만 표시
- 참고: `pm clear`(앱 데이터 삭제)는 앱 전용 외부 폴더(models/)까지 지우므로 모델 파일을 다시 넣어야 함
- 현재 S8에는 실수 방지를 위해 1B 파일을 `gemma3-1b-it-int4.litertlm.disabled`로 꺼둠 — 되살리려면 이름에서 `.disabled`만 제거

## 2026-07-11 오후 추가 작업 로그

### 검색 (S8 실기기 검증 완료)
- **사전 계산 임베딩 번들**: 5000장 문서 벡터를 PC에서 미리 계산해 APK에 번들(`assets/cards/cards_embeddings_*`, 14.6MB). 폰에서는 쿼리만 라이브 임베딩 → 시맨틱 검색이 인덱싱 대기 없이 즉시 동작. OCR 도입 후 새 카드는 온디바이스 임베딩으로 처리 예정 (`scripts/precompute_embeddings.py`)
- **명함 데이터 정리**: 합성 라벨의 프리픽스("Mobile.", "Address.")가 필드 값에 섞여 있던 것 제거. 특히 지역 필드가 전부 "Address."로 오염돼 지역 검색이 무력화됐던 문제 해결 (`scripts/build_cards_json.py` 재생성 + 임베딩 재계산)
- **키워드 랭킹 수정**: 바이그램 인덱스 조각("삼성로"의 '삼성')이 완전 일치 40점으로 둔갑하던 문제 → 점수는 원본 필드 단어 기준으로만 매김 (완전 40 > 전방 25 > 역전방 20 > 포함 12). 바이그램은 FTS 후보 회수 전용
- **필드별 가중치는 넣지 않음 (팀 결정)**: 이름/회사 +30 가중치를 넣었다가 제거. 필드 중요도는 하이브리드의 임베딩 쪽이 암묵적으로 담당 (임베딩 입력에 주소 미포함 → "삼성" 시맨틱 검색은 회사 매치를 자연히 위로 올림). 키워드 전용 탭에서는 회사/주소 매치가 동점(전방일치 25)이며 가나다순
- **전화번호 정규화**: 하이픈 없이("01092514960") 검색해도 완전 일치. 검색어와 인덱스 양쪽에 숫자만 남긴 사본 추가
- **하이브리드 폴백**: 임베딩 모델이 안 뜨는 기기에서도 검색이 죽지 않고 키워드 전용으로 동작 (retrieval 필드에 모드 표시)

### UI
- 채팅 탭을 말풍선 채팅 UI로 전면 개편 (사용자=오른쪽 남색, 답변=왼쪽 회색+모델 라벨, 타이핑 표시, 하단 입력창)
- 결과 카드 탭 → 명함 이미지 + 전체 필드 표 상세 다이얼로그. 이미지는 `files/card_images/{id}.png`에서 로드 (5000장 1.1GB는 APK 번들 불가 → adb/수동 전송, 없으면 "이미지 없음" 표시)
- 결과 카드에 주소 표시 (주소 매치가 왜 나왔는지 보이게), 검색 점수 소수점 표시 수정 (RRF 0.03이 0.0으로 보이던 것)
- 테마: 남색(#3D5A80)/파스텔 팔레트, dynamic color 끔

### Chat LLM 크래시 조사 (S8 4GB)
- 원인: 질문마다 엔진 open/close → 네이티브 메모리 누수 누적 + 로드 순간 ~1.15GB 스파이크 (실측). 스파이크 시점 기기 램 여유에 따라 프로세스 사망
- 수정: **엔진 싱글턴**(로드 1회 후 재사용, 질문마다 대화 세션만 신규) + **maxNumTokens=640**(KV 캐시 제한). 생성 중 메모리 ~600MB로 안정 유지 실측
- **gemma3-270m-it-q8 시도 → 폐기**: 로드/생성은 되지만 degenerate 반복 출력("</h4>" 무한 반복 등)으로 채팅 품질 불가 판정. S8 모델 폴더에서 제거함 (S8 채팅은 "LLM 없음 → 검색 결과만" 경로로 동작). 반복 출력 감지 시 안내 문구로 대체하는 방어는 코드에 유지
- Chat 역할 후보 파일: `gemma3-1b-it-int4.litertlm` → `gemma3-270m-it-q8.litertlm` 순으로 탐색 (코드에 유지, 파일 있으면 자동 사용)

### 결론 및 다음 단계
- **S8(4GB) = 검색 데모 전용**: 키워드+시맨틱 하이브리드, 명함 상세(이미지+필드) 완전 동작
- **LLM 답변 데모는 RAM 8GB+ 안드로이드 기기 필요** (Gemma 3 1B). 요즘 플래그십/상위 중급기(갤럭시 S 시리즈, A5x 등)는 8GB+. 미검증이므로 기기 확보 시 10분 검증 필요 (APK 설치 → 모델 4개 임포트 → 질문 1개)
- 아이폰은 불가: 안드로이드 전용 앱, iOS는 별도 개발+맥 필요

## 남은 일

1. **Gemma 3 1B IT 게이트 승인** → `gemma3-1b-it-int4.litertlm` 다운로드 → Drive 업로드 → 실기기 임포트
2. 실기기에서 임베딩 모델 로드 확인 — 모델 탭에서 "임베딩 모델" 패널이 초록(정상)인지
   - ~~위험 요소: MediaPipe TextEmbedder 호환 미확인~~ → **실기기에서 확인됨: 비호환** ("could not build model from the provided pre-loaded flatbuffer"). 2026-07-11에 런타임을 **AI Edge RAG SDK(`localagents-rag:0.3.0`)의 `GemmaEmbeddingModel`**로 교체 완료. `sentencepiece.model` 토크나이저 파일이 추가로 필요해짐 (같은 HF 저장소, 앱에 "토크나이저 가져오기" 버튼 추가). query/document 프롬프트 프리픽스는 SDK가 자동 적용. RAG SDK는 arm64 전용이라 x86 에뮬레이터에서는 임베딩이 안 돎
3. Chat LLM 실기기 스모크 테스트 ("Chat LLM 실행 테스트" 버튼)
4. OCR/KIE 파이프라인 (7월 마일스톤, 별도 트랙)

## 2026-07-23: Chat 모델을 Gemma 4 E2B로 교체

- `LlmRole.Chat` 1순위 파일을 `gemma3-1b-it-int4.litertlm`(529MB) → `gemma-4-E2B-it.litertlm`(2.5GB, `litert-community/gemma-4-E2B-it-litert-lm`, generic 빌드)로 변경. Gemma 3 파일들은 fallback으로 유지(`fileNames` 순서: E2B → 1B → 270M)
- **주의: 위 "2. LLM 역할 분리" 절에서 정리했던 이유로 Gemma 4 E2B 단일 모델을 한 번 배제한 적 있음** — 그때 근거였던 3.66GB 추정치는 이번에 받은 실제 파일(2.5GB)보다 컸지만, 그래도 기존 Gemma 3 1B(529MB)보다 5배 가까이 큼. S8(RAM 4GB)에서 529MB 모델도 메모리 부족으로 죽었던 전례가 있어 이 교체판은 **RAM 8GB+ 기기에서만 동작할 가능성이 높음** — 실기기 검증 전까지는 가정임
- Gemma 3 1B와 달리 이 저장소는 gate 없이 바로 다운로드됨 (`gated: false`)
- 참고: Google이 문서상 공식 프로덕션 경로로 권장하는 건 LiteRT-LM 번들이 아니라 지원 기기의 Android AI Core(Gemini Nano) — 이 프로젝트는 범용 기기 대상이라 LiteRT-LM 번들 방식 유지

## 알려진 개선 포인트 (다음 작업 후보)

1. **임베딩 프롬프트 프리픽스 미적용**: EmbeddingGemma는 쿼리에 `task: search result | query: `, 문서에 `title: none | text: ` 프리픽스를 붙여야 검색 품질이 제대로 나옴. 지금 `embed(text)`는 원문 그대로 넣고 있어서 retrieval 품질 손해 보는 중 — 쿼리/문서 구분해서 프리픽스 적용 필요 (적용 시 기존 저장된 벡터 전부 재계산 필요)
2. **Chat LLM 매 질문마다 로드**: `runChat`이 질문할 때마다 529MB 모델을 열고 닫음 → 질문당 수 초 로딩. 엔진 인스턴스 캐싱(싱글턴 + 명시적 해제) 고려
3. **첫 화면 진입 시 동기 로드**: `CardSearchService` 생성이 `onCreate`에서 일어나고 임베딩 로드가 `init{}` 동기 실행 — 첫 진입 프레임 드랍 가능. 백그라운드 초기화로 이동 고려
4. Gemma 라이선스: APK/모델 파일을 외부 공유할 때 Gemma Terms of Use 고지 필요 (팀 내 테스트 수준에선 무관)

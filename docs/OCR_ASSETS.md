# OCR / KIE 자산

명함 인식에 쓰는 온디바이스 모델. 인식 로직은 `:core-ocr` 에 있고 여기는 가중치만 둔다.

| 파일 | 크기 | 출처 | 저장소 포함 |
|---|---|---|---|
| `det.onnx` | 4.7 MB | PP-OCRv5_mobile_det (`ilaylow/PP_OCRv5_mobile_onnx`) | O |
| `rec.onnx` | 13 MB | korean_PP-OCRv5_mobile_rec (`monkt/paddleocr-onnx`) | O |
| `korean_dict.txt` | 59 KB | rec charset (자모 단위, CTC 디코드 후 NFC 조합) | O |
| `kie_tokenizer.onnx` | 0.85 MB | XLM-R SentencePiece(어휘 축소본), ortx custom op 그래프 | O |
| `kie_labels.json` | 208 B | index → 필드명 15종 | O |
| `kie_minilm_int8.onnx` | **36.6 MB** | fine-tuned MiniLM 분류기, dynamic int8 + 어휘 축소 | **X (gitignore)** |

### 이 둘은 반드시 한 벌로 쓴다

분류기의 임베딩 행 번호가 곧 토크나이저가 내놓는 id 다. 섞어 쓰면:

- **옛 토크나이저(4.9MB) + 새 분류기(36.6MB)** — id 가 범위를 벗어나 예외로 죽는다. 걸린다.
- **새 토크나이저(0.85MB) + 옛 분류기(113MB)** — id 가 범위 안이라 **그냥 돈다.** 예외도 없이
  결과만 엉킨다. 토크나이저는 저장소에 있고 분류기는 손으로 넣는 파일이라 실제로 일어날 수
  있는 조합이다.

그래서 `KieParser` 가 시작할 때 확실한 문장 하나("010-1234-5678" -> `mobile`)를 넣어 보고,
답이 틀리면 이 경로를 쓰지 않고 `CardParser` 휴리스틱으로 내려간다.

### 어휘 축소본 (2026-09-14 교체)

250,002 조각 중 **82,818 개가 ASCII 여러 글자 조각**(XLM-R 이 유럽어 단어를 담으려고 들고 있는
것들)이다. 명함 필드 분류에는 쓰이지 않으므로 상위 15,000 개만 남긴다
(`OCR/kie/trim_vocab.py --ascii-cap 15000`). 임베딩 행 250,037 -> 37,258, 파일 113MB -> 36.6MB.

**압축이 아니라 안 쓰는 어휘를 잘라내는 것**이라 분류 성능은 보존된다 — 인코더 12층(21.8MB)과
분류기 헤드는 손대지 않는다. 실제로 두 파일의 텐서를 대조하면 헤드와 인코더 130 개가 비트
단위로 같고, 다른 6 개는 position/token_type 임베딩의 양자화 차이(역양자화 후 최대 0.0046)뿐이다.

알려진 손실: **한자 이름(`name_hanja`)**. 축소 규칙이 "여러 글자 조각은 한글이나 ASCII 가 끼어
있어야 남긴다" 인데 한자는 거기 없어서 한자 조각이 잘린다. rec charset 에는 한자가 있으므로
읽히기는 하지만 분절이 달라져 `name_hanja` 를 놓친다(실측: 南多恩 -> `logo_text`).
이 제품에서 한자 이름을 쓰지 않기로 해서 그대로 둔다. 살리려면 `trim_vocab.py` 의
`_hangul_or_ascii` 에 U+4E00–U+9FFF 를 더해 다시 만든다.

## kie_minilm_int8.onnx 재생성

학습 체크포인트 `best.pt`(fp32, 449 MB — MiniLM-L12-H384 + 15클래스 헤드)에서 만든다.
**재학습이 아니라 변환만** 한다.

```bash
python kie_export_classifier.py   # best.pt -> fp32(470.9MB) -> int8(113MB) + labels
```

핵심 절차 (`OCR/kie/export_onnx.py` 와 동일):

1. `AutoConfig.from_pretrained("sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2", num_labels=15)`
   로 아키텍처만 세우고 `best.pt` state_dict 를 전량 로드 (베이스 가중치 다운로드 불필요)
2. `torch.onnx.export(..., opset_version=17, dynamo=False)`
   — torch 2.9+ 기본인 dynamo exporter 가 낸 그래프는 onnx shape inference 에서
   `(384) vs (15)` 로 깨져 `quantize_dynamic` 이 실패한다. 레거시 경로를 써야 한다.
3. `quantize_dynamic(weight_type=QInt8, op_types_to_quantize=["MatMul","Gemm","Gather"])`

검증: fp32 470.9 MB / int8 118.2 MB 가 나오면 `OCR/kie/README.md` 의 기록과 일치한다.

## 토크나이저와 분류기는 짝이 맞아야 한다

지금 들어있는 두 파일은 **vocab trimming 을 하지 않은 원본 쌍**이다(250,037 vocab).

`HJP_limitededition@r1_phase2_sprint_c` 의 `App/android/.../assets/kie_tokenizer.onnx`(0.9 MB)는
vocab 을 37,258 로 자른 **trim 본**이라 이 분류기와 토큰 id 가 맞지 않는다. 섞어 쓰면
분류 결과가 조용히 망가진다 — 둘 다 trim 하거나 둘 다 원본이어야 한다.

trim 본으로 바꾸면 분류기 113 → 36.6 MB, 토크나이저 4.9 → 0.9 MB 로 줄고 정확도 손실은
없다고 기록돼 있다(`OCR/kie/README.md`). 그러려면 `trim_vocab.py --ascii-cap 15000` →
`export_onnx_trim.py` 를 학습 데이터와 함께 돌려야 한다.

## 방향 분류기

원본 파이프라인에는 방향 분류기가 둘 있다. 하나만 넣었다:

| 모델 | 하는 일 | 여기 | 크기 |
|---|---|---|---|
| `PP-LCNet_x0_25_textline_ori` | 글줄 하나가 0/180 중 어느 쪽인지 | **넣음** (`textline_ori.onnx`) | 1.0 MB |
| `PP-LCNet_x1_0_doc_ori` | 이미지 전체가 0/90/180/270 중 어느 쪽인지 | 안 넣음 | 6.5 MB |

둘 다 **파인튜닝 없는 공개 사전학습 가중치**다(학습을 거친 것은 KIE 분류기뿐이다).

`doc_ori` 를 넣지 않은 이유: 사진이 통째로 누운 경우는 EXIF 로 처리한다
(`CaptureScreen.uprightByExif`). 카메라가 방향을 태그로 정확히 남기므로 모델로 추측할 이유가
없다. 원본 데모도 기본값이 꺼져 있고 — 평평한 명함에서 분류기가 잘못 회전시켜 한글을
깨뜨린다고 주석에 적혀 있다 — 사람이 체크박스로만 켠다. EXIF 가 없는 이미지(스캔본·스크린샷)가
거꾸로일 때만 쓸모가 있다.

### textline_ori 재생성

```
pip install paddlepaddle==3.1.0 paddlex
python -m paddlex --install paddle2onnx          # paddle2onnx 2.0.2rc3 을 짝 맞춰 깐다
python -c "from huggingface_hub import snapshot_download; snapshot_download('PaddlePaddle/PP-LCNet_x0_25_textline_ori', local_dir='ori')"
python -m paddlex --paddle2onnx --paddle_model_dir ori --onnx_model_dir out --opset_version 13
cp out/inference.onnx app/src/main/assets/ocr/textline_ori.onnx
```

**버전 조합이 까다롭다.** paddle 3.3.1 은 paddle2onnx 2.x 의 네이티브 확장이 심볼을 못 찾아
DLL 로드에 실패하고, paddle 3.0.0 은 paddle2onnx 가 요구하는 하한(3.0.0.dev20250426)보다
낮다. paddle2onnx 1.3.1 은 로드는 되지만 Paddle 3.x 의 새 PIR 형식(`inference.json`)을 못
읽는다. **3.1.0 + 2.0.2rc3** 이 맞는 짝이다.

**짧은 글줄에는 쓰지 않는다.** 이 모델은 가로세로비가 낮은 크롭에서 확신을 갖고 틀린다.
똑바로 선 "남다은"(비율 2.7)에 P(180)=0.87 을 준다. 넣자마자 이름이 뒤집혀 "긍그" 로 읽혔고,
크기를 바꿔 가며 재보니 크기가 아니라 **길이**가 문제였다:

| 가로세로비 | 글자수 | 똑바로일 때 P(180) | |
|---|---|---|---|
| 1.07 | 1 | 0.752 | 틀림 |
| 2.74 | 3 | 0.866 | 틀림 |
| 3.58 | 4 | 0.893 | 틀림 |
| 5.26 | 6 | 0.609 | 틀림 |
| 5.56 | 7 | 0.001 | 맞음 |
| 12.51 | 17 | 0.000 | 맞음 |

긴 글줄에서 0.001 대 0.999 로 깨끗하게 갈리므로 전처리가 틀린 것은 아니다. 신뢰도로 거를
수도 없다 — 틀린 답이 0.9 다. 그래서 비율 6 이상에서만 묻는다(`TextLineOrientation.MIN_ASPECT`).
이름·직함처럼 짧은 줄은 이 단계가 없던 때와 같게 지나간다.

검증: 입력 `x` `[N,3,80,160]`, 출력 `[N,2]`, opset 13. 그래프에 softmax 가 들어 있어 두 값의
합이 1 이다 — `TextLineOrientation` 이 차이가 아니라 확률로 판정하는 이유다.
합성 한글 글줄로 확인한 분리도: 똑바로 0.88~1.00 / 뒤집힘 0.67~1.00.

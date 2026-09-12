# OCR / KIE 자산

명함 인식에 쓰는 온디바이스 모델. 인식 로직은 `:core-ocr` 에 있고 여기는 가중치만 둔다.

| 파일 | 크기 | 출처 | 저장소 포함 |
|---|---|---|---|
| `det.onnx` | 4.7 MB | PP-OCRv5_mobile_det (`ilaylow/PP_OCRv5_mobile_onnx`) | O |
| `rec.onnx` | 13 MB | korean_PP-OCRv5_mobile_rec (`monkt/paddleocr-onnx`) | O |
| `korean_dict.txt` | 59 KB | rec charset (자모 단위, CTC 디코드 후 NFC 조합) | O |
| `kie_tokenizer.onnx` | 4.9 MB | XLM-R SentencePiece, ortx custom op 그래프 | O |
| `kie_labels.json` | 208 B | index → 필드명 15종 | O |
| `kie_minilm_int8.onnx` | **113 MB** | fine-tuned MiniLM 분류기, dynamic int8 | **X (gitignore)** |

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

## 방향 분류기 — 아직 없음

원본 파이프라인에는 방향 분류기가 둘 있고, 우리 assets 에는 **둘 다 없다**:

| 모델 | 하는 일 | 원본에서 |
|---|---|---|
| `PP-LCNet_x0_25_textline_ori` | 글줄 하나가 0/180 중 어느 쪽인지 | 항상 켜짐 |
| `PP-LCNet_x1_0_doc_ori` | 이미지 전체가 0/90/180/270 중 어느 쪽인지 | 사람이 체크박스로 켬 |

없어서 생기는 일은 하나다: **세로로 쓴 글줄이 뒤집혀 들어가도 바로잡을 수단이 없다.**
그래서 `OcrPipeline.cropQuad` 가 세로 크롭을 어느 쪽으로 돌리는지가 그대로 결과가 된다 —
원본과 같은 반시계 방향으로 맞춰 뒀다(원본은 뒤에 분류기가 있어서 방향 선택이 덜 중요했다).

촬영 사진이 통째로 누운 경우는 분류기가 아니라 EXIF 로 처리한다(`CaptureScreen.uprightByExif`).
카메라가 방향을 태그로 남기므로 추측할 필요가 없고, 그게 더 정확하다. `doc_ori` 가 필요한
경우는 EXIF 가 없는 이미지(스캔본·스크린샷)가 거꾸로일 때뿐이다.

넣으려면 KIE 와 같은 절차를 밟는다: HuggingFace `PaddlePaddle/<모델명>` 에서 받아 ONNX 로
변환하고 `app/src/main/assets/ocr/` 에 둔다. 두 모델 다 수 MB 수준이라 크기는 문제가 아니다.

"""토크나이저 ONNX + int8 분류기가 실제로 맞물리는지 확인.

KieParser.kt 의 classify() 와 같은 순서로 돈다 — flat tokens -> MAX_LEN 자르기 ->
<pad>=1 로 우측 패딩 -> (input_ids, attention_mask) -> argmax.
"""
import json
import sys
from pathlib import Path

try:
    import numpy as np
    import onnxruntime as ort
    from onnxruntime_extensions import get_library_path
except ModuleNotFoundError as error:
    raise SystemExit(
        f"필요 패키지가 없습니다: {error.name}. "
        "onnxruntime, onnxruntime-extensions, numpy를 설치하거나 "
        "Android의 OcrAssetsInstrumentedTest를 실행하세요."
    ) from error

DEFAULT_MODEL_DIR = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "ocr"
MODEL_DIR = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else DEFAULT_MODEL_DIR
MAX_LEN = 48

labels = json.loads((MODEL_DIR / "kie_labels.json").read_text(encoding="utf-8"))

so = ort.SessionOptions()
so.register_custom_ops_library(get_library_path())
tok = ort.InferenceSession(str(MODEL_DIR / "kie_tokenizer.onnx"), so,
                           providers=["CPUExecutionProvider"])
cls = ort.InferenceSession(str(MODEL_DIR / "kie_minilm_int8.onnx"),
                           providers=["CPUExecutionProvider"])

# 명함에 실제로 찍히는 형태의 라인들. 오른쪽이 기대 필드.
samples = [
    ("홍길동", "name_ko"),
    ("Gildong Hong", "name_en"),
    ("대표이사", "title"),
    ("AI사업부", "department"),
    ("(주)에이비씨", "company_ko"),
    ("010-1234-5678", "mobile"),
    ("02-555-1234", "tel_office"),
    ("gildong@abc.com", "email"),
    ("www.abc.com", "website"),
    ("서울특별시 강남구 테헤란로 123", "address_ko"),
]
texts = [s[0] for s in samples]

ids_list = []
for t in texts:
    r = tok.run(None, {"inputs": np.array([t])})
    ids_list.append(r[0].astype(np.int64)[:MAX_LEN])

L = max(len(x) for x in ids_list)
ids_arr = np.ones((len(texts), L), dtype=np.int64)  # 1 = XLM-R <pad>
mask_arr = np.zeros((len(texts), L), dtype=np.int64)
for j, ids in enumerate(ids_list):
    ids_arr[j, :len(ids)] = ids
    mask_arr[j, :len(ids)] = 1

logits = cls.run(None, {"input_ids": ids_arr, "attention_mask": mask_arr})[0]
pred = [labels[i] for i in logits.argmax(-1)]

ok = 0
print(f"{'텍스트':<32} {'예측':<14} {'기대':<14} 결과")
print("-" * 72)
for (text, want), got in zip(samples, pred):
    hit = got == want
    ok += hit
    print(f"{text:<32} {got:<14} {want:<14} {'OK' if hit else 'MISS'}")
print("-" * 72)
print(f"일치 {ok}/{len(samples)}")
if ok != len(samples):
    raise SystemExit(1)

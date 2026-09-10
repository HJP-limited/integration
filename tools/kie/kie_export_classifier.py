"""KIE 분류기(best.pt) -> ONNX fp32 -> int8.

OCR/kie/export_onnx.py 의 export_classifier() 와 같은 절차다. 다른 점은 베이스
가중치를 내려받지 않고 config 만으로 아키텍처를 세운 뒤 체크포인트로 전부 덮어쓴다는 것
(어차피 201개 텐서를 전량 로드하므로 결과는 같고, 470MB 다운로드가 빠진다).
"""
import json
import sys
from pathlib import Path

import torch
from transformers import AutoConfig, AutoModelForSequenceClassification

CKPT = Path(r"C:\Users\babie\Downloads\best (1).pt")
OUT = Path(__file__).resolve().parent / "kie_out"
MODEL_NAME = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
MAX_LEN = 48

# OCR/kie/dataset.py 의 FIELDS_FULL 과 순서까지 같아야 한다 — 라벨 인덱스가 곧 분류기 출력이다.
FIELDS_FULL = [
    "name_ko", "name_en", "name_hanja", "title", "department",
    "company_ko", "company_en", "tel_office", "mobile", "fax",
    "email", "website", "address_ko", "slogan", "logo_text",
]

OUT.mkdir(parents=True, exist_ok=True)

cfg = AutoConfig.from_pretrained(MODEL_NAME, num_labels=len(FIELDS_FULL))
model = AutoModelForSequenceClassification.from_config(cfg)

state = torch.load(CKPT, map_location="cpu", weights_only=True)
missing, unexpected = model.load_state_dict(state, strict=False)
print("missing:", missing)
print("unexpected:", unexpected)
if missing:
    sys.exit(f"체크포인트에 없는 파라미터가 있다 — 아키텍처 불일치: {missing[:5]}")
model.eval()

fp32 = OUT / "kie_minilm_fp32.onnx"
dummy = {
    "input_ids": torch.ones(1, MAX_LEN, dtype=torch.long),
    "attention_mask": torch.ones(1, MAX_LEN, dtype=torch.long),
}
# dynamo exporter(torch 2.11 기본)가 낸 그래프는 onnx shape inference 에서
# "(384) vs (15)" 로 깨져 quantize_dynamic 이 실패한다. 레거시 TorchScript 경로는
# BERT 계열에서 검증된 평탄한 그래프를 내고 449MB 는 protobuf 2GB 한도 안이라
# external data 없이 한 파일로 떨어진다.
torch.onnx.export(
    model, (dummy,), str(fp32),
    input_names=["input_ids", "attention_mask"],
    output_names=["logits"],
    dynamic_axes={
        "input_ids": {0: "batch", 1: "seq"},
        "attention_mask": {0: "batch", 1: "seq"},
        "logits": {0: "batch"},
    },
    opset_version=17,
    dynamo=False,
)
print(f"fp32 onnx: {fp32.stat().st_size / 1e6:.1f} MB")

from onnxruntime.quantization import QuantType, quantize_dynamic

int8 = OUT / "kie_minilm_int8.onnx"
quantize_dynamic(
    str(fp32), str(int8),
    weight_type=QuantType.QInt8,
    op_types_to_quantize=["MatMul", "Gemm", "Gather"],
)
print(f"int8 onnx: {int8.stat().st_size / 1e6:.1f} MB")

(OUT / "kie_labels.json").write_text(
    json.dumps(FIELDS_FULL, ensure_ascii=False, indent=1), encoding="utf-8")
print("labels:", OUT / "kie_labels.json")

"""XLM-R SentencePiece 토크나이저를 ONNX 그래프로 export.

OCR/kie/export_onnx.py 의 export_tokenizer() 와 동일하다. 분류기를 vocab trimming 없이
내보냈으므로 토크나이저도 **원본(untrimmed)** 이어야 id 가 맞는다 — 저장소에 들어있는
kie_tokenizer.onnx(0.9MB)는 trim 본이라 이 분류기와 짝이 맞지 않는다.
"""
from pathlib import Path

from onnxruntime_extensions import gen_processing_models
from transformers import XLMRobertaTokenizer
import onnx

MODEL_NAME = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
OUT = Path(__file__).resolve().parent / "kie_out"
OUT.mkdir(parents=True, exist_ok=True)

hf_tok = XLMRobertaTokenizer.from_pretrained(MODEL_NAME)
print("vocab size:", hf_tok.vocab_size)

tok_onnx, _ = gen_processing_models(hf_tok, pre_kwargs={})
path = OUT / "kie_tokenizer.onnx"
onnx.save_model(tok_onnx, str(path))
print(f"tokenizer onnx: {path.stat().st_size / 1e6:.1f} MB")
print("outputs:", [o.name for o in tok_onnx.graph.output])

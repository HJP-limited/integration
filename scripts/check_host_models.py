"""Strict Windows CPU model smoke test. NOT Android AgentKernel or 435-turn evaluation."""
import argparse
import hashlib
import json
from pathlib import Path
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
HASHES = {
    "gemma-4-E2B-it.litertlm": "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    "embeddinggemma-300m.tflite": "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5",
    "sentencepiece.model": "d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7",
}


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--extension-jar", type=Path, required=True)
    args = parser.parse_args()
    models = args.model_dir.resolve()
    ocr = ROOT / "app/src/main/assets/ocr"
    embedding = models / "embeddinggemma-300m-onnx"
    output = ROOT / "build/host-model-smoke"
    output.mkdir(parents=True, exist_ok=True)
    report_path = output / f"run-{time.time_ns()}.jsonl"
    with report_path.open("w", encoding="utf-8") as report:
        def record(kind, **values):
            report.write(json.dumps({"type": kind, **values}, ensure_ascii=False) + "\n")
            report.flush()
            print(kind, values.get("stage", ""), flush=True)
        record("run_start", scope="host CPU smoke only; ONNX embedding export, not Android TFLite runtime",
               android_pipeline_verified=False)
        try:
            import numpy as np
            import onnxruntime as ort
            import litert_lm
            from tokenizers import Tokenizer
            from litert_lm._ffi import _get_lib
            required = [models / name for name in HASHES] + [
                ocr / name for name in ("det.onnx", "rec.onnx", "textline_ori.onnx", "korean_dict.txt",
                                       "kie_minilm_int8.onnx", "kie_tokenizer.onnx", "kie_labels.json")
            ] + [embedding / "onnx/model.onnx", embedding / "onnx/model.onnx_data",
                 embedding / "tokenizer.json", args.extension_jar]
            for path in required:
                if not path.is_file() or not path.stat().st_size:
                    raise RuntimeError(f"Required model/runtime missing: {path}")
            for name, expected in HASHES.items():
                if sha256(models / name) != expected:
                    raise RuntimeError(f"Required model hash mismatch: {name}")
            _get_lib()  # Verify generation runtime before testing other models. No silent skip.
            dll = output / "onnxruntime_extensions4j_jni.dll"
            with zipfile.ZipFile(args.extension_jar) as archive:
                data = archive.read("ai/onnxruntime/extensions/native/win-x64/onnxruntime_extensions4j_jni.dll")
            if not dll.exists() or dll.read_bytes() != data:
                dll.write_bytes(data)
            options = ort.SessionOptions()
            options.register_custom_ops_library(str(dll))
            record("preflight_pass", artifacts={str(path): sha256(path) for path in required})

            record("stage_start", stage="ocr")
            for name, shape in (("det.onnx", (1, 3, 64, 64)), ("rec.onnx", (1, 3, 48, 320)),
                                ("textline_ori.onnx", (1, 3, 80, 160))):
                session = ort.InferenceSession(str(ocr / name), providers=["CPUExecutionProvider"])
                outputs = session.run(None, {session.get_inputs()[0].name: np.zeros(shape, dtype=np.float32)})
                if not outputs or not all(np.isfinite(x).all() for x in outputs):
                    raise RuntimeError(f"Invalid OCR output: {name}")
                record("model_pass", stage=name, output_shapes=[list(x.shape) for x in outputs],
                       input_kind="synthetic blank tensor; not OCR accuracy")
                del session
            tok = ort.InferenceSession(str(ocr / "kie_tokenizer.onnx"), sess_options=options,
                                       providers=["CPUExecutionProvider"])
            classifier = ort.InferenceSession(str(ocr / "kie_minilm_int8.onnx"), providers=["CPUExecutionProvider"])
            labels = json.loads((ocr / "kie_labels.json").read_text(encoding="utf-8"))
            samples = [("홍길동", "name_ko"), ("Gildong Hong", "name_en"), ("대표이사", "title"),
                       ("AI사업부", "department"), ("(주)에이비씨", "company_ko"), ("010-1234-5678", "mobile"),
                       ("02-555-1234", "tel_office"), ("gildong@abc.com", "email"),
                       ("www.abc.com", "website"), ("서울특별시 강남구 테헤란로 123", "address_ko")]
            rows = [tok.run(None, {"inputs": np.array([text], dtype=object)})[0].reshape(-1)[:48] for text, _ in samples]
            ids = np.ones((len(rows), max(map(len, rows))), dtype=np.int64)
            mask = np.zeros_like(ids)
            for i, row in enumerate(rows):
                ids[i, :len(row)], mask[i, :len(row)] = row, 1
            logits = classifier.run(None, {"input_ids": ids, "attention_mask": mask})[0]
            predictions = [labels[int(i)] for i in logits.argmax(axis=-1)]
            record("kie_predictions", expected=[label for _, label in samples], actual=predictions)
            if predictions != [label for _, label in samples]:
                raise RuntimeError("KIE smoke labels mismatch")
            del tok, classifier

            record("stage_start", stage="embedding_onnx")
            tokenizer = Tokenizer.from_file(str(embedding / "tokenizer.json"))
            encoded = tokenizer.encode("task: search result | query: 판교 AI 개발자")
            session = ort.InferenceSession(str(embedding / "onnx/model.onnx"), providers=["CPUExecutionProvider"])
            vector = session.run(["sentence_embedding"], {
                "input_ids": np.array([encoded.ids], dtype=np.int64),
                "attention_mask": np.array([encoded.attention_mask], dtype=np.int64),
            })[0]
            norm = float(np.linalg.norm(vector))
            if vector.shape != (1, 768) or not np.isfinite(vector).all() or not 0.99 < norm < 1.01:
                raise RuntimeError(f"Invalid embedding: shape={vector.shape}, norm={norm}")
            record("model_pass", stage="embedding_onnx", dimension=768, norm=norm)
            del session

            record("stage_start", stage="gemma_litertlm")
            engine = litert_lm.Engine(str(models / "gemma-4-E2B-it.litertlm"),
                                     backend=litert_lm.Backend.CPU(), max_num_tokens=4096,
                                     cache_dir=str(output))
            try:
                conversation = engine.create_conversation(sampler_config=litert_lm.SamplerConfig(top_k=1, temperature=0.0, seed=42))
                try:
                    response = conversation.send_message("2 더하기 3은 얼마인가요? 숫자 하나만 답하세요.")
                    record("generation_response", response=response)
                    if not any(part.get("text", "").strip() == "5" for part in response.get("content", [])):
                        raise RuntimeError("Generation smoke answer was not 5")
                finally:
                    conversation.close()
            finally:
                engine.close()
            record("run_end", complete=True, passed=True, android_pipeline_verified=False)
        except Exception as error:
            record("run_end", complete=False, passed=False, error=f"{type(error).__name__}: {error}")
            raise
        finally:
            print(f"report: {report_path}", flush=True)


if __name__ == "__main__":
    main()

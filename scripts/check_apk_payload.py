"""Verify the built APK contains current OCR assets and the full bundled 1000-card vector index."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--report", type=Path, default=ROOT / "build/apk-payload-check.json")
    args = parser.parse_args()
    apk = args.apk
    assets = ROOT / "app/src/main/assets"
    checks = []
    with zipfile.ZipFile(apk) as package:
        bundled = {
            "embeddinggemma-300m.tflite": "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5",
            "sentencepiece.model": "d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7",
        }
        for name, expected in bundled.items():
            if digest(package.read("assets/models/" + name)) != expected:
                raise RuntimeError("Missing/stale bundled service model: " + name)
        # Check required KIE entries even when the local classifier is missing/ignored.
        kie = {
            "kie_minilm_int8.onnx": "3fd1287d76151f463e0328a0c7250c75e89e16e1c666808e03cfe3a83919e389",
            "kie_tokenizer.onnx": "bbfd74e2ee719b44f0101e6c08fad6276c5a38ce849cd0e56defac25ab32f874",
            "kie_labels.json": "482de0d2a2037ef30907259d20fd02ffdf473a96fd899dfeb88528c0e9c6888a",
        }
        for name, expected in kie.items():
            if digest(package.read("assets/ocr/" + name)) != expected:
                raise RuntimeError("Missing/stale KIE artifact: " + name)
        for name in ("Gemma-Terms.txt", "Gemma-Prohibited-Use.txt", "Apache-2.0.txt", "NOTICE.txt"):
            if package.read("assets/legal/" + name) != (assets / "legal" / name).read_bytes():
                raise RuntimeError("Missing/stale model legal notice: " + name)
        for folder in ("ocr", "cards"):
            for path in sorted((assets / folder).iterdir()):
                if not path.is_file():
                    continue
                entry = "assets/" + path.relative_to(assets).as_posix()
                packed = package.read(entry)
                if not packed or digest(packed) != digest(path.read_bytes()):
                    raise RuntimeError(f"Missing/stale APK asset: {entry}")
                checks.append({"entry": entry, "sha256": digest(packed)})
        for name in ("liblitertlm_jni.so", "libgemma_embedding_model_jni.so",
                     "libonnxruntime.so", "libonnxruntime4j_jni.so",
                     "libonnxruntime_extensions4j_jni.so", "libopencv_java4.so"):
            entry = "lib/arm64-v8a/" + name
            if package.getinfo(entry).file_size <= 0:
                raise RuntimeError(f"Empty native library: {entry}")
        cards = json.loads(package.read("assets/cards/cards_seed.json"))
        ids_bytes = package.read("assets/cards/cards_embeddings_ids.json")
        ids = json.loads(ids_bytes)
        vectors = package.read("assets/cards/cards_embeddings.bin")
        fingerprint = json.loads(package.read("assets/cards/cards_embeddings_fingerprint.json"))
        if len(cards) != 1000 or len(ids) != 1000 or len(set(ids)) != 1000:
            raise RuntimeError("Expected all 1000 seed cards and unique vector IDs")
        if set(ids) != {card["id"] for card in cards} or len(vectors) != 1000 * 768 * 4:
            raise RuntimeError("Bundled vector IDs/dimensions do not match the seed")
        if digest(ids_bytes) != fingerprint["ids_sha256"] or digest(vectors) != fingerprint["vectors_sha256"]:
            raise RuntimeError("Bundled vector fingerprint mismatch")
    report = {"passed": True, "scope": "packaged files and seed/vector consistency, not inference",
              "seed_count": 1000, "vector_dimension": 768, "native_libraries": 6, "bundled_service_models": 2, "kie_artifacts": 3, "legal_documents": 4, "assets": checks}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"APK payload passed: 2 bundled service models, 4 legal documents, {len(checks)} OCR/card assets, 6 arm64 libraries, 1000 x 768 vectors")


if __name__ == "__main__":
    main()

"""Verify the built APK contains current OCR assets and the full bundled 1000-card vector index."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    assets = ROOT / "app/src/main/assets"
    checks = []
    with zipfile.ZipFile(apk) as package:
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
              "seed_count": 1000, "vector_dimension": 768, "native_libraries": 6, "assets": checks}
    (ROOT / "build/apk-payload-check.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"APK payload passed: {len(checks)} current assets, 6 arm64 libraries, 1000 x 768 vectors")


if __name__ == "__main__":
    main()

# 명함을 EmbeddingGemma로 미리 임베딩해서 앱에 번들한다.
# 폰에서 라이브 모델을 못 띄워도 시맨틱 검색이 바로 동작하도록 하기 위함 (OCR 붙기 전 임시 조치).
#
# 사용법:
#   python scripts/precompute_embeddings.py                      # 앱 시드(cards_eval1000) 재생성
#   python scripts/precompute_embeddings.py --cards data/cards_5000.json \
#       --out-ids data/cards_5000_ids.json \
#       --out-vectors data/cards_5000_vectors.bin                # 구 프로덕션 셋(비교용)
#
# 데이터를 다시 만들었으면(build_eval_dataset.py) 반드시 임베딩도 다시 만들 것 —
# ids 순서와 카드 순서가 어긋나면 검색 결과가 통째로 뒤섞인다.
import sys
from pathlib import Path as _P
sys.path.insert(0, str(_P(__file__).resolve().parent))
import card_fingerprint
import argparse
import hashlib
import json
import struct
from pathlib import Path

from sentence_transformers import SentenceTransformer

REPO = Path(__file__).resolve().parent.parent
CARDS_PATH = REPO / "data" / "cards_eval1000.json"
MODEL_PATH = REPO / "models" / "embeddinggemma-300m"
# 기본 출력은 data/ 다 — eval_search.py 가 여기서 읽는다.
# 앱 에셋(app/src/main/assets/cards/)에는 --copy-to-assets 로 같이 복사한다.
# (예전엔 기본 출력이 에셋이라, data/ 의 오래된 벡터로 에셋을 덮어쓰는 사고가 났다.
#  카드 id 는 그대로라 무결성 검사도 통과해서 눈치채기 어려웠다.)
IDS_PATH = REPO / "data" / "cards_eval1000_ids.json"
VECTORS_PATH = REPO / "data" / "cards_eval1000_vectors.bin"
ASSETS_DIR = REPO / "app" / "src" / "main" / "assets" / "cards"
RUNTIME_MODEL_SHA256 = "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5"
RUNTIME_TOKENIZER_SHA256 = "d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7"

# CardSearchService.cardEmbeddingInput()과 반드시 동일한 필드 순서/구분자를 써야
# 나중에 온디바이스에서 재계산될 때(OCR 카드 등) 같은 입력 형식이 된다.
FIELDS = ["name", "nameEn", "company", "title", "department", "industry", "location", "memo", "tags"]


def card_text(card: dict) -> str:
    parts = [card.get(f, "") for f in FIELDS]
    return ", ".join(p for p in parts if p)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cards", default=str(CARDS_PATH))
    ap.add_argument("--out-ids", default=str(IDS_PATH))
    ap.add_argument("--out-vectors", default=str(VECTORS_PATH))
    ap.add_argument("--copy-to-assets", action="store_true",
                    help="카드/ids/벡터를 앱 에셋에도 복사한다(배포 시드 갱신).")
    args = ap.parse_args()

    cards_path = Path(args.cards)
    ids_path = Path(args.out_ids)
    vectors_path = Path(args.out_vectors)

    cards = json.loads(cards_path.read_text(encoding="utf-8"))
    model = SentenceTransformer(str(MODEL_PATH))

    texts = [card_text(c) for c in cards]
    print(f"encoding {len(texts)} cards from {cards_path.name}...")
    vectors = model.encode_document(texts, batch_size=64, show_progress_bar=True)

    ids_path.parent.mkdir(parents=True, exist_ok=True)
    vectors_path.parent.mkdir(parents=True, exist_ok=True)
    ids = [c["id"] for c in cards]
    ids_path.write_text(json.dumps(ids, ensure_ascii=False), encoding="utf-8")

    with open(vectors_path, "wb") as f:
        for vec in vectors:
            f.write(struct.pack(f"<{len(vec)}f", *vec.tolist()))

    # 벡터를 만든 '그때의 카드 내용'을 지문으로 남긴다. 이게 없으면 나중에 카드만 바꾸고
    # 임베딩을 안 돌렸을 때 아무도 눈치채지 못한다(id 는 그대로라 무결성 검사도 통과한다).
    stamp_path = ids_path.parent / card_fingerprint.STAMP_NAME
    fp = card_fingerprint.write_stamp(stamp_path, cards, cards_path.name)
    stamp = json.loads(stamp_path.read_text(encoding="utf-8"))
    stamp["runtime_model_sha256"] = RUNTIME_MODEL_SHA256
    stamp["runtime_tokenizer_sha256"] = RUNTIME_TOKENIZER_SHA256
    stamp["ids_sha256"] = hashlib.sha256(ids_path.read_bytes()).hexdigest()
    stamp["vectors_sha256"] = hashlib.sha256(vectors_path.read_bytes()).hexdigest()
    stamp_path.write_text(json.dumps(stamp, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote fingerprint {fp[:16]}... -> {stamp_path}")

    print(f"wrote {len(ids)} ids -> {ids_path}")
    print(f"wrote {vectors_path} ({vectors_path.stat().st_size / 1024 / 1024:.1f} MB, dim={vectors.shape[1]})")

    if args.copy_to_assets:
        import shutil
        ASSETS_DIR.mkdir(parents=True, exist_ok=True)
        shutil.copy(cards_path, ASSETS_DIR / "cards_seed.json")
        shutil.copy(ids_path, ASSETS_DIR / "cards_embeddings_ids.json")
        shutil.copy(vectors_path, ASSETS_DIR / "cards_embeddings.bin")
        shutil.copy(stamp_path, ASSETS_DIR / card_fingerprint.STAMP_NAME)
        print(f"copied to assets -> {ASSETS_DIR}")


if __name__ == "__main__":
    main()

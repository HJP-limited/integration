"""Check real OCR -> SQLite -> persisted embedding -> live search in an isolated web test DB."""
import argparse
import json
import sqlite3
import time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--db", required=True, type=Path)
    parser.add_argument("--image", required=True, type=Path)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--expect-phone")
    parser.add_argument("--expect-mobile")
    parser.add_argument("--expect-website")
    parser.add_argument("--wait-seconds", type=int, default=300)
    args = parser.parse_args()
    deadline = time.monotonic() + args.wait_seconds
    while True:
        try:
            with urlopen(args.url + "/api/status", timeout=5) as response:
                before = json.load(response)
            break
        except HTTPError:
            raise  # Server execution failures must not be retried or hidden.
        except URLError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(2)
    assert Path(before["db"]).resolve() == args.db.resolve(), "Select the isolated server database"
    assert before["model"]["sha256"] == (
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    ), "The actual verified Gemma deployment is required"
    assert "ONNX" in before["embedder"], "Actual host embedding is required"
    with urlopen(Request(args.url + "/api/ocr?save=true", data=args.image.read_bytes(),
                         headers={"Content-Type": "application/octet-stream"}), timeout=240) as response:
        ocr = json.load(response)
    assert "error" not in ocr, ocr
    assert ocr["regions"] and ocr["fields"], "Actual OCR must produce usable fields"
    card_id = ocr["savedCardId"]
    assert card_id, "Save must not report completion before required embedding refresh"
    with sqlite3.connect(args.db.resolve().as_uri() + "?mode=ro", uri=True) as connection:
        connection.row_factory = sqlite3.Row
        row = connection.execute("SELECT * FROM business_cards WHERE id = ?", (card_id,)).fetchone()
        assert row is not None, "Saved card is missing"
        card = dict(row)
        for column in ("phone", "mobile", "website"):
            expected = getattr(args, "expect_" + column)
            if expected is not None:
                assert card[column] == expected, (column, expected, card[column])
        # Compare the actual recognized fields with the persisted record across the save boundary.
        for label, column in (("전화", "phone"), ("휴대폰", "mobile"), ("웹", "website")):
            values = [field["value"].strip() for field in ocr["fields"]
                      if field["label"] == label and field["value"].strip()]
            assert card[column] == (values[0] if values else ""), (label, values, card[column])
        assert card["updated_at"], "Original save timestamp must be retained"
        vectors = [dict(row) for row in connection.execute(
            "SELECT model_name, dimension, length(vector_blob) AS bytes, source_text_hash "
            "FROM card_embeddings WHERE card_id = ?", (card_id,))]
    assert vectors and all(v["dimension"] == 768 and v["bytes"] == 3072
                           and v["source_text_hash"] for v in vectors), vectors
    with urlopen(args.url + "/api/status", timeout=30) as response:
        after = json.load(response)
    assert after["cards"] == before["cards"] + 1, "Exactly one card must be registered"
    assert card["name"], "This integration image must yield a searchable name"
    with urlopen(Request(args.url + "/api/reset", method="POST"), timeout=30) as response:
        assert json.load(response)["ok"]
    question = card["name"] + " 명함 찾아줘"
    events = []
    with urlopen(args.url + "/api/turn?" + urlencode({"text": question}), timeout=240) as response:
        for line in response:
            if line.startswith(b"data: "):
                events.append(json.loads(line[6:]))
    done = next((event for event in reversed(events) if event.get("type") == "done"), None)
    assert done and not done["failed"], done
    assert "search_contacts" in done["tools"] and card_id in done["cardIds"], done
    args.log.parent.mkdir(parents=True, exist_ok=True)
    args.log.write_text(json.dumps({
        "passed": True, "ocr": ocr, "card": card, "vectors": vectors, "question": question,
        "search": done, "android_verified": False,
        "boundary": "Real PC OCR/KIE and ONNX embedding; external apps remain SIMULATED",
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"PASS: real OCR saved {card_id}, preserved fields, persisted 768D vector and live search")


if __name__ == "__main__":
    main()

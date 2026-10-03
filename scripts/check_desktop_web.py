"""Verify real-model HTTP/SSE evidence. External compose/calendar calls remain SIMULATED."""
import argparse
import json
import re
import time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import URLError

ROOT = Path(__file__).resolve().parents[1]
GEMMA_SHA = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8765")
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--wait-seconds", type=int, default=300)
    args = parser.parse_args()
    cards = json.loads((ROOT / "app/src/main/assets/cards/cards_seed.json").read_text(encoding="utf-8"))
    by_id = {card["id"]: card for card in cards}
    suites = [ROOT / "eval/web_integration_regressions_2026-10-04.json",
              ROOT / "eval/device_tool_transitions_2026-09-16.json"]
    args.log.parent.mkdir(parents=True, exist_ok=True)
    with args.log.open("w", encoding="utf-8") as log:
        def record(value):
            log.write(json.dumps(value, ensure_ascii=False) + "\n")
            log.flush()

        deadline = time.monotonic() + args.wait_seconds
        while True:
            try:
                with urlopen(args.url + "/api/status", timeout=5) as response:
                    status = json.load(response)
                break
            except URLError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(2)
        record({"type": "status", "value": status})
        assert status["cards"] == 1000, "Expected the default 1000-card database"
        assert status["model"]["sha256"] == GEMMA_SHA, "Actual verified Gemma is required"
        assert "ONNX" in status["embedder"], "Actual host embedding is required"

        completed = 0
        for suite in suites:
            with urlopen(Request(args.url + "/api/reset", method="POST"), timeout=30) as response:
                assert json.load(response)["ok"] is True
            previous_ids = []
            for case in json.loads(suite.read_text(encoding="utf-8")):
                done = None
                endpoint = args.url + "/api/turn?" + urlencode({"text": case["question"]})
                with urlopen(endpoint, timeout=240) as response:
                    assert response.headers.get_content_type() == "text/event-stream"
                    for line in response:
                        if line.startswith(b"data: "):
                            event = json.loads(line[6:])
                            record({"type": "event", "question": case["question"], "value": event})
                            if event.get("type") == "done":
                                done = event
                assert done is not None, "Missing completed SSE turn"
                assert done["failed"] is False, done["answer"]
                tools, ids, answer = done["tools"], done["cardIds"], done["answer"]
                assert set(case.get("required_tools", [])).issubset(tools), tools
                assert set(tools).issubset(case["allowed_tools"]), tools
                if "gold_ids" in case:
                    assert set(ids) == set(case["gold_ids"]), ids
                if "previous_result_index" in case:
                    assert ids == [previous_ids[case["previous_result_index"]]], ids
                for value in case.get("contains", []):
                    assert value in answer.replace(",", ""), answer
                if "surname" in case:
                    assert ids, "Surname search returned no contacts"
                    assert all(by_id[card_id]["name"].startswith(case["surname"]) for card_id in ids), ids
                if case.get("card_predicate") == "data_role":
                    assert ids, "Data-role search returned no contacts"
                    for card_id in ids:
                        card = by_id[card_id]
                        fields = " ".join(str(card.get(field, "")) for field in ("title", "department", "tags"))
                        assert re.search(r"데이터|분석|data|analytics|scientist", fields, re.I), card_id
                if case.get("grounded_names"):
                    names = {by_id[card_id]["name"] for card_id in ids}
                    for card in cards:
                        name = card["name"]
                        if len(name) >= 3 and name in answer and name not in case["question"]:
                            assert name in names, "Ungrounded contact: " + name
                if set(tools) & {"open_compose", "create_calendar_event"}:
                    assert done["drafts"] and all("SIMULATED" in draft for draft in done["drafts"])
                previous_ids = ids
                completed += 1
                print(f"PASS t{completed}: {case['question']} | tools={tools} | ids={ids}", flush=True)
        record({"type": "pass", "turns": completed, "android_verified": False,
                "external_calls": "SIMULATED"})
        print(f"PASS: {completed} actual-model web turns; Android/external apps unverified", flush=True)


if __name__ == "__main__":
    main()

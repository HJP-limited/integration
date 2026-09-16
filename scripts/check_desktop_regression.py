"""Check actual desktop turn evidence, not just plausible answer wording."""
import argparse
import json
import re
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("log", type=Path)
    parser.add_argument("--suite", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    cases = json.loads((args.suite or root / "eval/device_user_regressions_2026-09-16.json").read_text(encoding="utf-8"))
    cards = json.loads((root / "app/src/main/assets/cards/cards_seed.json").read_text(encoding="utf-8"))
    by_id = {card["id"]: card for card in cards}
    data = args.log.read_bytes()
    raw = data.decode("utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")
    turns = re.split(r"(?m)^\[t\d+\] ", raw)[1:]
    failures = []
    if len(turns) != len(cases):
        failures.append(f"Expected {len(cases)} completed turns, got {len(turns)}")
    for index, (block, case) in enumerate(zip(turns, cases), 1):
        question, _, body = block.partition("\n")
        def fail(message):
            failures.append(f"t{index}: {message}")
        if question.strip() != case["question"]:
            fail("Question/order mismatch")
        tool_line = re.search(r"도구: (.*)", body)
        id_line = re.search(r"명함ID:([^\r\n]*)", body)
        answer_line = re.search(r"답변: ([\s\S]*)", body)
        if not tool_line or not id_line or not answer_line:
            fail("Missing tool/ID/answer evidence")
            continue
        tools = [] if tool_line[1].strip() == "(없음)" else tool_line[1].strip().split(" → ")
        ids = [value for value in id_line[1].strip().split(",") if value]
        answer = answer_line[1]
        if not set(case.get("required_tools", [])).issubset(tools):
            fail(f"Missing required tool: {tools}")
        if not set(tools).issubset(case["allowed_tools"]):
            fail(f"Wrong tool executed: {tools}")
        if "gold_ids" in case and set(ids) != set(case["gold_ids"]):
            fail(f"Wrong contact IDs: {ids}")
        for expected in case.get("contains", []):
            if expected not in answer.replace(",", ""):
                fail(f"Missing grounded answer value: {expected}")
        if case.get("card_predicate") == "data_role":
            if not ids:
                fail("Search returned no IDs")
            for card_id in ids:
                card = by_id.get(card_id)
                fields = " ".join(str(card.get(field, "")) for field in ("title", "department", "tags")) if card else ""
                if not re.search(r"데이터|분석|data|analytics|scientist", fields, re.I):
                    fail(f"Contact has no data-role evidence: {card_id}")
        if case.get("grounded_names"):
            allowed_names = {by_id[card_id]["name"] for card_id in ids if card_id in by_id}
            for card in cards:
                name = card["name"]
                if len(name) >= 3 and name in answer and name not in allowed_names and name not in question:
                    fail(f"Answer mentions a contact outside this turn's tool results: {name}")
    if failures:
        print("FAIL\n" + "\n".join(failures))
        return 1
    print(f"PASS: {len(cases)} turns; tools, exact IDs, answer values and search grounding checked")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

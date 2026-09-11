#!/usr/bin/env python3
"""Create E-3.3 by normalizing every static-card ordinal Gold expectation.

Only ordinal contract expressions are changed. E-3.2 is read-only; no trace or
physical output is used to choose a card id.
"""
import copy, json, re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e32.json"
DST = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e33.json"
CHANGES = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e33_ordinal_changes.json"

ORDINALS = {
    "첫 번째": 1, "두 번째": 2, "세 번째": 3, "네 번째": 4,
    "다섯 번째": 5, "여섯 번째": 6, "일곱 번째": 7, "여덟 번째": 8,
    "아홉 번째": 9, "열 번째": 10, "첫째": 1, "둘째": 2,
    "셋째": 3, "넷째": 4,
}

def ordinal_position(user: str):
    for phrase, position in sorted(ORDINALS.items(), key=lambda x: -len(x[0])):
        if phrase in user:
            return position
    return None

def main():
    source_bytes = SRC.read_bytes()
    data = json.loads(source_bytes)
    out = copy.deepcopy(data)
    changes = []
    for scenario in out["scenarios"]:
        ordinal_turns = []
        for turn in scenario["turns"]:
            pos = ordinal_position(turn["user"])
            if pos is None:
                continue
            ordinal_turns.append((turn, pos))
            for call in turn.get("expected_calls", []):
                card = call.get("args", {}).get("card_id")
                if card and card.get("cmp") == "exact":
                    old = copy.deepcopy(card)
                    card.clear(); card.update({"cmp": "ordinal_candidate", "position": pos})
                    changes.append({"scenario_id": scenario["scenario_id"], "turn": turn["index"],
                                    "tool": call["tool"], "position": pos, "old": old})
        # Generalize scenario-level final target only where the ordinal turn is
        # the target-selection turn. Existing ordinal policy is retained.
        later_explicit_target = bool(ordinal_turns and any(
            t["index"] > ordinal_turns[0][0]["index"] and
            ("찾아" in t["user"] or any(c.get("tool") == "search_contacts" for c in t.get("expected_calls", [])))
            for t in scenario["turns"]
        ))
        if ordinal_turns and not later_explicit_target and "final_target_card_id" in scenario.get("success", {}):
            turn, pos = ordinal_turns[0]
            old = scenario["success"].pop("final_target_card_id")
            scenario["success"]["final_target_policy"] = {
                "kind": "ordinal_candidate", "turn": turn["index"], "position": pos
            }
            changes.append({"scenario_id": scenario["scenario_id"], "field": "final_target_policy",
                            "turn": turn["index"], "position": pos, "old": old})
    out["contract"] = dict(out.get("contract", {}), ordinal="persisted_retrieval_candidate_order")
    DST.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    CHANGES.write_text(json.dumps({"source": str(SRC.relative_to(ROOT)), "changes": changes}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"source_sha256": __import__("hashlib").sha256(source_bytes).hexdigest(),
                      "changes": len(changes), "output": str(DST), "change_log": str(CHANGES)}))

if __name__ == "__main__":
    main()
